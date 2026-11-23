# 功能补齐冻结契约

冻结日期：2026-11-22。本文由本轮主控审查后确定，补充 `production-contract.md`，不把计划写成已验收能力。实现与证据登记在 `docs/reviews/full-audit-ledger.md`。

## 1. 来源、优先级与增量边界

本轮以 `dev@7c757c83` 为集成基线。完整核对后的 `main@c04e3b46b0e9da8fef26284bdf3405740b4305db` 已有 V1–V56 和成品域，不能把当前工作树缺失误写为整个仓库没有实现。成品后端、API 07 可复用 `e74a2b8`，前端起点为 `2b5ea73`，并逐项审查后续修正。

本次用户明确要求成品采购/外发加工、分批收发货、公共标签与首期主线闭环，覆盖旧分期中把它们留待后续的安排。PRD §6.12 的 396 件分成 300＋96 入库有效；RFC 008 中“不能少入”“一回货一有效入库”的残留说明作废。main 的 V48 曾按历史答复收回普通角色成品权限；本次新增成品能力按下列权限显式接入，不能既隐藏入口又宣称闭环。

不整包替换主干认证、资料包或生产模块。主干后来新增的产品线 `LineScope`、取消 DEV 资料包等有独立数据迁移和业务影响，不能用空实现、默认放行或改旧迁移解决依赖。当前资料包与已经修复的加载/错误态保持原有契约。

字段清单原件仍不可见，不能声称完成全部非标列逐项复刻；但现有 PRD/RFC、源码和本次明确要求足以实施的功能继续补齐。质检、预约、账单核销、财务月结、独立订单 BOM 快照与完整库存维度仍是待交付项，不能用缺少原件一概搁置，也不能以工具类或未接线表代替业务验收。

## 2. 成品数据与事务

### 2.1 模型

复用 `fg_doc` / `fg_doc_item` 主子表及成品类型：`RECEIPT_IN`、`OTHER_IN`、`SHIP_OUT`、`OTHER_OUT`，状态 `DRAFT`、`APPROVED`、`VOIDED`。款式沿用 SPU—颜色×尺码 SKU，不再建一份款式宽表。

成品余额唯一键为 `(company_id, warehouse_id, style_sku_id, location_id)`；当前没有明细库位模型，`location_id` 只能为 0；核心和数据库拒绝非 0，不能用虚构库位绕开每版每SKU一次过账。历史非 0 记录须先对账，不能自动改成 0。同仓同 SKU 的不同主体余额分开，任何读写必须显式传 `companyId`。来源订单/回货单、成品单头、余额、流水主体一致，并与 `X-Company-Id` 校验。请求头不能代替权限。

`fg_stock_log` 为只追加事件，数据库拒绝 UPDATE、DELETE、TRUNCATE。反审核只新增原事件的精确逆向事件，`reversal_of_log_id` 唯一并有外键；不从后来编辑过的草稿重建历史事件。余额有 `movement_revision`；流水记录变动后余额，数据库校验来源、主体和冲销关系。跨 SKU/仓汇总用 long，单格数量仍为受正整数校验的 int，计算上限先使用 long，禁止 int 溢出。

### 2.2 统一锁序与动作

所有来源相关写入按相同顺序：**来源回货单或需求订单 FOR UPDATE → 成品单头锁 → 按 SKU、主体、仓、库位升序的余额键**。修改、过账、反审核、作废、删除及来源作废检查不能各自倒置锁序。来源单据作废先锁/CAS 本身，再检查下游引用；引用存在则整笔回滚，避免先查后锁的 TOCTOU。

审核在同一事务中完成：锁来源和草稿 → 校验 version、权限、状态 → 按最新可信来源重算明细/单价/金额/额度 → CAS `WHERE id AND status AND version` 改 APPROVED → 原子改余额并追加流水 → 同事务业务日志/字段审计。FG 当前没有必须异步完成的副作用，不插入无人消费的事件；ApiQueue/TaskNotice 的真实生产者、消费者和处理界面另列明确待交付项，已有缓存/库存导出 Outbox 不能冒充这些功能。数据库明细保护仅允许草稿修改，重算不能移到 CAS 后。草稿改动按真实唯一键 `(doc_id,style_sku_id)` 保留已有明细 ID，新增/移除分别处理；批量 UPDATE 生成准确 `old→new` 字段 Diff，不能无谓删光再插。明细不可换单头，直接 SQL 与审核抢锁必须受控拒绝。

出库使用条件原子扣减，不足时全单回滚，不能先读余额后无条件覆盖。多行缺货返回现有 1001 明细；源单超额返回 1004。建单、审核/反审核/作废分别使用稳定 operation 的 `Idempotency-Key` 和动作收据；动作空结果重放也重新验证当前身份、主体、对象权限，重复同键返回原成功结果；不能让相同 version 的第二次请求静默成功。裸 PUT 只编辑草稿，不能设置状态/审核人/金额。

领域动作保留现有 API 07 的 POST `/audit`、`/unaudit`、`/void`，表示审核、反审核、作废；页面统一调用动作 API。若增加 `:approve` / `:reverse-approve` 别名，必须共用同一实现与幂等动作身份，不能出现两套状态更新。

### 2.3 分批入库、发货与逆向

回货良品为每行 `qty-defectQty`。草稿与已审核且未作废的入库单均占用回货可入数；分别返回 `reservedQty`（草稿）、`postedQty`（已审核）和 `remainingQty`。编辑/审核计算上限时排除本单。允许部分 SKU、部分数量；0 行不落库，至少一个正数，负数拒绝。`items:null` 表示当次加锁后全部剩余量，不使用前端旧快照。

由回货单带出、保存和审核都重新校验真实来源状态与公司。生成多张草稿不能超占良品。删除未过账草稿/作废已反审草稿释放占用；反审回到草稿仍保留占用。来源作废在所有有效引用释放前拒绝。

发货订单允许 `APPROVED/RUNNING/DONE/CLOSED`。每 SKU 上限沿用成品 RFC 的 `floor(orderQty*103/100)`，减去其他已审核发货量；不是生产回货后来采用的另一条容差。审核按订单排队重新计算。单价从订单可信行读取，`Num.money`/BigDecimal HALF_UP 到分，未知单价保持 NULL，客户端总额不参与入账。发货后才能反审的库存实际条件由余额扣减保证；禁止篡改原流水抵消。

main 已确认“制单人与审核人分离”的规则用于本次新增成品域：制单人不能审核自己的成品单，反审仍按权限与库存约束。前端由回货生成默认保存草稿；旧 `post:true` 需审核权限且仍经过制单人校验，不能作为绕过入口。此增量不宣称既有全部单据已迁移到该规则，后续统一评估。

## 3. 成品 API 与跨模块接线

API 07 现有字段、路由和视图作为起点，以下条目覆盖其旧定义。ID 始终十进制字符串；日期始终 `YYYY-MM-DD`。所有列表都有加载、空态、失败/重试；失败不能伪装为“待入库”或库存 0。

| 接口/能力 | 冻结差异 |
|---|---|
| `/api/fg-docs`、详情、来源、库存查询及导出 | 明确主体参数/上下文，目标和来源校验；批量整组可见性检查 |
| `/api/fg-docs/by-receipts?receiptIds=` | 最多 100 个，返回 `[{receiptId,documents:[{id,no,status,qty}],documentsTotal,hasMoreDocuments,reservedQty,postedQty,remainingQty}]`；每来源按 `(create_time,id)` 降序最多预览 20 张。任一来源不可见整批 404；预览/总数遵守 FG DataScope，真实数量汇总仍计算全部有效占用，不能用不可见单据导致的假剩余允许重复入库 |
| `/api/fg-docs/from-receipt/{id}` | 返回实际剩余与每行 `maxQty`；支持 300＋96；过期响应不能覆盖新来源 |
| `/api/fg-docs/style-options`、`/fg-stocks/qty` | 单请求最多 100 个 ID；查询 SKU 主体/仓维度明确，大矩阵分批须全部成功才发布结果 |
| `/api/fg-stock-logs` | 使用现有 `SeekPage {list,nextCursor,hasMore}`，按 `(create_time,id)`，游标绑定主体、筛选、权限；独立统计，不附带 COUNT |
| 库存格子流水弹窗 | 同样使用 seek 与“继续加载”，不能固定前 100 条而隐藏余量 |
| 普通库存/单据/待入库列表 | 现有有界 PageResult；不取 10000 行冒充全量。选择限当前页，换页/筛选/主体清空 |
| Excel 导入 | 文件魔数、体积/行数/Hash、公式注入和全量行列预校验；解析在业务事务外，写入再锁定 version/主体/状态 |
| Excel 导出 | 沿用已验证的权限列剥离与主子行查询预算，禁止复用主干未限制子行的 export；生成/下载的全流式与断连必须另有实际证据 |

跨模块 DTO（A2 拥有）沿用 API 07 同义字段：

```java
OrderShipView(id, no, status, companyId, deliveryReq,
    List<Line(orderItemId, styleId, styleSkuId, qty, price)>);
ReceiptForStock(id, no, companyId, prdOrderId, prdOrderNo, prdOrderKind,
    styleId, factoryId, receiptDate, warehouseId,
    List<Item(id, styleSkuId, qty, defectQty)>);
```

成品详情、列表和导出中的已用 SKU 资料只查询实际引用的 SKU 与必要颜色，不将一行展开为整款数万色码。完整矩阵元数据采用固定服务端预算，超限明确失败，不能静默截断。现有 `/styles/pick` 是登录即可读取的有界白名单；公开吊牌价 `tagPrice` 沿用原规则，成本、供应商底价和成交价不能出现在该投影。

`by-receipts` 是只读历史关联接口，按来源真实公司与 DataScope 接受所有可见状态；非 APPROVED 回货的可入数量为 0，不能让一条 VOIDED 历史记录令整页合法关联失败。写入来源接口仍只接受 APPROVED。来源引用检查统计全部有效下游，存在不可见引用则用通用1005拦截，不泄露其ID/单号/数量；全部可见时最多100个预览，超过明确引导到来源精确筛选分页，不能过滤成“无引用”。

`OrderService.shipView`、`ProductionReceiptService.forStock` 在事务中锁真实来源，再做 FG 来源专用授权。仓管具备 FG 新建能力但没有上游完整列表权限时，可读取生成所需的白名单来源字段；不得经此接口取得上游全详情或成本。使用当前真实 DataScope/业务权限，不引入无效 LineScope 放行器。普通上下游查询仍校验原模块权限；来源读取例外不继承到标签写入。

价格权限域为 **ORDER**，使用 `order:price:query`，包括成品详情、列表、预填、Excel 和 DataLog。没有权限时 `price/amount` 物理移除，不默认归到 COST，不把未知价改为 0。库存数量查询不携带价格。

接入 `BizType.FG_DOC`、ReferrerProvider、DocGuard、公共附件/日志/引用、Home `FG_PENDING`、CommandPalette、DocRoute；订单显示逐 SKU 已发量，生产回货展示多张相关入库单，并在有余量时提供“查看全部 n 张”。`GET /fg-docs` 新增 `receiptId` 精确筛选，通过现有分页访问全部可见关联。预览用有界批量 SQL，不逐来源 N+1，也不先取全量再截断；数量/计数与预览解耦。

`DocRef` 新增可选 `companyId`，FG 引用必须填写；FG 路由带 `?companyId=`。没有主体参数的直接深链显式选择主体，不能猜测来源公司。所有这些派生读取必须保护主体切换、身份切换、关闭弹窗和请求取消。

成品余额按主体分账并不等同于全域租户/公司成员 ACL 已完成。当前部署命名空间和共享公司业务规则必须如实记录，M-01 仍需全域方案与验证；不允许仅靠 `X-Company-Id` 宣称防住任意公司授权越权。

## 4. 公共标签契约（在成品独立增量之后实现）

### 4.1 规范与持久化

可信命名空间使用 `DeploymentNamespace.tenant()`，与数据库持久化命名空间守卫一致，不接受客户端指定 tenant。三张标签表的 `tenant_key` 均外键引用 `sys_deployment_namespace(tenant_id)`，关联同时使用 tenant 与 ID 复合外键。当前部署只支持一个持久命名空间；验收应证明非法第二命名空间和混命名空间关联被拒，不能伪造两个合法共享库租户或据此销掉 M-01。V58 不在启动命名空间初始化之前插入标签种子。标签库跨当前部署中的公司共享，目标对象访问仍走实际领域权限。

输入做 NFKC、Unicode strip、移除一个前导 `#` 后再次 strip，显示保留大小写；canonical 使用 Java Locale.ROOT 小写，不由数据库 lower 重算。显示名 1–64 Unicode 码点，拒绝 Cc 控制字符、逗号及双向覆盖/隔离控制符 U+202A–U+202E、U+2066–U+2069；保留 ZWJ emoji，其余前导 `#` 作为普通字符。canonical 最长 256 字符以容纳大小写转换扩展。每对象最多 30 标签。搜索为字面前缀，默认 50、最大 100；批量 includeIds 最大 100。

已保存名称回填请求时使用 `"#" + displayName`，保证一次规范化往返不改变真实名称。例如历史名称 `#蓝` 应提交 `##蓝`；普通名称 `蓝` 提交 `#蓝`。重命名编辑框显示真实名称，提交时同样补一个标记；用户新输入的原始标签文本只规范化一次，不能反复剥离 `#`。

| 表 | 关键约束 |
|---|---|
| `biz_tag` | id、tenant_key、name、canonical、version、deleted_token=0、审计列；UNIQUE(tenant_key,canonical,deleted_token)，前缀索引 |
| `biz_tag_object` | id、tenant_key、object_type、object_id、company_id、version；UNIQUE(tenant_key,object_type,object_id) |
| `biz_tag_link` | id、tenant_key、object_ref_id、tag_id、seq；唯一(ref,tag)/(ref,seq)，tenant+ID 复合外键 |

首批目标固定白名单：STYLE、MATERIAL、SUPPLIER、TECH_PACK、ORDER、MRP_CALC、PURCHASE_ORDER、INV_DOC、INV_COUNT、PRODUCTION_ORDER、PRODUCTION_RECEIPT、FG_DOC；SAMPLE 在真实样衣域存在后添加。数据库固定 CASE 触发器校验目标存在，父行锁须同时阻挡删除和主体变更（FOR SHARE 或更强；仅 FOR KEY SHARE 不足以阻挡非键主体更新）。目标删除在同事务清理 object/link，保留标签库和审计。不得使用拼接任意表名的动态 SQL 或仅有字符串类型却允许孤儿关系。全局主数据 company_id 为 NULL；有主体的对象取实际父头主体。父头合法变更主体时同事务同步 registry，不改变标签内容 version，后续访问仍以当前父头授权。

`TagObjectProvider` 放 biz 公共接口，每个真实领域实现可读/可编辑/主体/锁定检查，biz 不向上依赖具体模块。写入锁序：领域对象 → tag_object → 涉及名称的数据库事务锁 → 已存在标签按稳定 id 升序行锁 → 版本 CAS → 替换关联。名称集合包含当前关联和本次输入的 canonical；命名空间参与64位锁键，去重后按有符号锁键升序，避免 hash 碰撞导致锁序反转。改名先锁旧/新名称再锁单个标签，归档只锁单个标签；两者不反向锁 tag_object。拿锁后重查版本/名称，变化用1002拒绝；常规同名并发自动创建应合并为同一活跃标签。缓存只能 commit 后失效，回滚不污染缓存。

### 4.2 接口和界面

| 路径 | 契约 |
|---|---|
| `GET /api/tags?keyword=&size=&includeIds=` | 返回可选标签，登录且有 `biz:tag:list`；不从目标关联反推不可见对象信息 |
| `GET /api/tags/objects/{type}/{id}` | 先按领域查可见性；`{version,tags:[{id,name,archived,version}]}`，未建 object 时 version=0、tags=[]；GET 不落库 |
| `POST /api/tags/objects/{type}/{id}/replace` | `{version,names:string[],retainIds:string[],tagVersions:Record<string,number>}`，Idempotency-Key +真实目标编辑权限；tagVersions 必须精确匹配所有当前关联标签 ID/版本（新空对象传 `{}`），retainIds 显式选择保留的当前 link 对应标签 ID（新空对象传 `[]`）；规范化去重、自动创建不存在标签、CAS替换并返回新版本；过时使用统一 STATE_CHANGED=1002 |
| `POST /api/tags/{id}/rename`、`/archive` | `biz:tag:manage`，version 与幂等；归档后历史关联仍可读，不能新增归档关系 |

选项接口对 `includeIds` 先按输入顺序去重并完整验证；其中可包含已经归档的历史标签，任一不存在或不属于可信命名空间的 ID 均整批 404。总返回预算为 `max(size, 去重 includeIds 数量)`，最多100；先返回显式 includeIds，再以稳定 `(canonical,id)` 顺序查询前缀命中项填充余量并排除已包含 ID。`size=50` 加100个 includeIds 最多返回100条，不能加成150条；余量为0时不执行搜索查询。

已关联的归档标签可保留原 link 或仅调整 seq，不能新增归档 link；从对象移除后再次输入同名，创建/选择新的活跃标签，旧历史继续引用旧 ID。`retainIds` 不允许重复或对象当前没有的 ID，每个保留项的当前 canonical 必须出现在 names 中。同一 canonical 若有显式保留项，则保留所选精确 ID；没有保留项才自动选取或创建活跃标签。关联按 names 的顺序排列，同 canonical 的多个历史项按 retainIds 顺序排列，最终实际关联数不能超过30。此规则允许全局改名后同一对象保留两个同名但不同 ID 的历史标签，不会因普通编辑静默删除其中一条；完整 tagVersions 校验同时阻断过时名称重建。公共标签页位于资料目录，路由 `/tags`、组件 `biz/tag/index`、图标 `PriceTag`、排序 60；现有 dev/dev_lead/sales/merchandiser/buyer/warehouse/finance/manager 模板可读，prod_manager 仅角色已经存在时授予可读，不创建新角色；manage 仅 manager 和既有超级管理员。

标签选择输入、`#` 前缀、自动入库、移除、搜索和回显复用原型视觉；停用、403、1002、503和取消有独立状态。已有 `legacy_attrs`/`style_tag` 不自动解释为公共标签，避免把未知文本静默迁成生产关系。筛选目标列表时关联过滤与原 DataScope 取交集。

### 4.3 目标编辑边界

每次读取、修改和幂等重放均重新使用真实领域权限与 DataScope；只在原领域确实支持时保留当前审批人的读取例外，标签写入不继承该例外。所有带实际主体的对象强制校验请求头与父头一致；共享主数据不把请求头变成对象归属。MRP 单头允许实际 NULL，继续检查全部来源订单可见性与原 CompanyContext，不能猜测或回填主体。

| 对象 | 读 / 写权限后缀（沿现有前缀） | 标签可编辑状态与附加条件 |
|---|---|---|
| STYLE | `style:style:list/edit` | 沿现有款式编辑规则 |
| MATERIAL | `basic:material:list/edit` | 沿现有物料编辑规则 |
| SUPPLIER | `supplier:supplier:list/edit` | 沿 SupplierRules，排除 ADMITTING |
| TECH_PACK | `techpack:pack:list/edit` | DRAFT，所属款式未停用 |
| ORDER | `order:order:list/edit` | DRAFT；制单/业务/跟单普通范围 |
| MRP_CALC | `purchase:mrp:list/edit` | DRAFT/CONFIRMED，沿改备注范围；拒 CLOSED |
| PURCHASE_ORDER | `purchase:order:list/edit` | DRAFT；制单/采购普通范围 |
| INV_DOC | `inventory:doc:list/edit` | DRAFT；制单普通范围 |
| INV_COUNT | `inventory:count:list/edit` | DRAFT；制单/盘点普通范围 |
| PRODUCTION_ORDER | `production:order:list/edit` | DRAFT；制单/跟单普通范围 |
| FG_DOC | `fg:doc:list/edit` | DRAFT；制单普通范围 |
| PRODUCTION_RECEIPT | 读 `production:order:list`；写 `production:receipt:add` | 单独标签元数据动作：回货 APPROVED、父生产 APPROVED/RUNNING/DONE，普通父生产 DataScope |

回货标签按生产单→回货单锁序，后续不反向获取需求订单锁；只修改标签 registry/link，不改变回货状态、数量或版本。公共标签库改名/归档使用独立 `BizType.PUBLIC_TAG` 操作时间轴且不生成业务单号，日志读取要求 list 权限与可信命名空间下真实标签；不借此开放任意附件或引用写入口。

## 5. 迁移、物理边界与真实验收

V46 从已发布 main 原样引入；新增 **V57 成品加固、V58 公共标签**，保留 V1–V56 原文件不改写。新增版本前再次查远端占用，发生冲突由主控重新分配。当前 dev 缺少 main 的其他低版本迁移，不能因此直接宣称未来主干升级兼容：后续整合须审查原 V37–V56 的全部依赖/业务变化，在停止写入的受控升级窗口先预演，再按审批后的迁移方案处理低版本；不得默认打开 outOfOrder，更不能伪称零停机。

V57 对已有共享成品库存只迁移能从完整流水唯一证明主体且余额/历史顺序一致的数据。多主体混账、缺少原流水、来源主体矛盾、未冲销事件与单据状态不一致一律中止并给出安全诊断；不偷偷指定第一公司或重写原余额。新库、干净 V46、可证明历史和不可证明历史分别测试。

| 负责方 | 物理写边界 |
|---|---|
| A1 | FG entity/Mapper/XML/core、V46/V57/V58、必要来源锁 Mapper、s1 数据库测试；SupplierMapper 追加预算查询 |
| A2 | FG DTO/Controller/Service/导入导出/字段投影、来源 DTO/业务服务/权限接线、API 07、s2 HTTP 测试；不覆盖 A1 core |
| A3 | 11 个成品前端文件、必要路由/类型/现有详情局部接线、s3 浏览器；保留已修复认证与资料包 |
| A4 | FG 双审计/日志脱敏、已有 CI 门禁、s4 验证及生产 RFC |
| 主控 | 本契约、跨阶段协调、真实 API/SQL/UI 独立复核、总台账和 PR |

先暂存在独立工作目录并对齐 DTO；冻结修复验收后一起接入可构建工作树。不能让一方在另一方 Maven 门禁期间注入缺类文件。

最低新增验收包括：396→300＋96 与一对多关联；20 并发同键只建一单；并发抢同来源不超占；50 并发出库和反序多 SKU 不负库存/不死锁；公司余额隔离；旧 version 冲突；直接改原流水/伪造冲销失败；入库已消耗后反审失败；源单作废与入库竞争仅一方成功；价格物理剥离与真实字段 Diff；101 个来源 ID 受控拒绝；来源批次混入不可见 ID 整体失败；流水超过 100 条继续翻页；真实生产 UI、矩阵、浮窗、输入法与中断/失败恢复截图。标签另外验证规范化竞争、删后重建、CAS、孤儿保护和删除/关联并发。

每条只有看到真实 SQL、HTTP JSON、测试原始结果和截图后才标“通过”；计划、代码存在、历史 main 通过均不能替代本次验证。
