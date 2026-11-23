# 全量审查与修复总台账

本轮日期：2026-11-22。目标分支 `dev@7c757c83fbd794e051347cb8edff04d346294e75`，工作分支 `fix/full-audit-arch-prod-hardening`。本文件记录本轮观察和实际验收；历史报告中的“通过”不自动成为本轮结果。

## 1. Ground Truth 与证据边界

1. `README.md`、`CLAUDE.md`、PRD、架构、数据库设计、RFC 001–010、API 01–06、原型及源代码构成可访问基准。仓库没有 `AGENTS.md`。本轮用户的 dev/PR/目录隔离要求覆盖旧文档的直接提交 main 约定。
2. 两份原始业务表未随任务提供，公开仓库将它们排除在 `docs/private/`；文件检索两次均未命中。PRD 引述的 160 页/2985 列不能当成已核对原件。本轮不能签发原系统全页、全字段复现证明。
3. 主代理先核对 dev 的需求分期、数据模型、迁移、核心事务/查询、安全/文件和前端公共实现，建立问题和接口边界；全文件静态清单与阶段人工代码审查分开记录，不能用扫描文件数冒充逐行人工审查完成。
4. `main@c04e3b46` 比 dev 多 105 个提交，四阶段加固分支仍在。`perf/a4-observability-lifecycle@02f69ba` 的树与 main 上已整合的 `892afa7` 相同，且均承接同一 dev 基线。本轮首先复用这组加固实现，再审查后续业务增量；main 已有成品域和 V1–V56，不能把当前 dev 工作树缺失写成全仓不存在。成品后端/API 起点为 `e74a2b8`、前端为 `2b5ea73`；助手等无关增量不整包导入。原始四阶段 SHA：A1 `1aebc97`、A3 `2f69ccd`、A2 `dac2a7a`、A4 `b574e5f`；后续已审整改 `0e9c885`、文档同步 `02f69ba`。
5. 本次用户要求优先于旧分期。成品、样衣、公共标签等已有足够 PRD/RFC 或明确任务规则的能力继续实施；缺少私有字段原件不能作为停止这些工作的依据。财务收付/月结、质检预约的缺口分别记录，不能用库存期间或金额工具冒充完成。新增成品/标签的实际事务、权限与迁移契约见 `docs/architecture/functional-completion-contract.md`，当前计划不算验收通过。

### 1.1 完整业务链覆盖与明确断点

下表是源码与迁移的覆盖判断，不代替第 3–5 节的真实运行验收。Java 路径均相对 `src/main/java/com/lingmao/scm/module/`；版本化 SQL 均在 `src/main/resources/db/migration/`。

| 链路 | 已有真实入口/模型 | 必须保留的断点 |
|---|---|---|
| 主数据、供应商、价格 | `basic/controller/*`、`supplier/controller/SupplierController.java:56`、`price/controller/PriceController.java:35`；V10/V12/V13/V15 | 原始字段清单未取得；`bas_customer` 有表但没有完整客户业务 Controller，TRADE 是受控预留。 |
| SPU / 色码 SKU | `style/controller/StyleController.java:181,225,232`、`style/service/impl/SkuGenerator.java`；V14 的款式、款色和唯一色码 SKU | 真实矩阵逐SKU往返已通过；不能用有表或单条链路证明全部原件字段一致。 |
| 样衣 | dev 基线只有 V14 的 `sample_no`；隔离候选新增 V59、样衣/打样单/节点/评审/转款与 SAMPLE 资料包 | 新域正进行根集成，不能再称源码完全缺失；尚未完成完整后端、真实前端及云端验收。寄样/完整原件字段一致性仍未证明。 |
| 标准资料包 / BOM | `techpack/controller/TechPackController.java:72–116`、V20 的版本/尺寸/BOM/工艺/成本子表 | DEV/PROD 生效和修订存在；仅可对实测路径验收。 |
| 订单生产资料包 | `order/service/impl/OrderServiceImpl.java:874,904`、`production/service/impl/ScheduleServiceImpl.java:224,252`；V22/V25 的 `tech_pack_id` | 当前实现是冻结标准生产 BOM 版本引用；没有独立 ORDER 级可编辑 BOM 快照表。`mrp_calc.input_snapshot` 不能替代它。 |
| 订单、拆分排产 | `OrderController`、`OrderChangeController`、`ScheduleServiceImpl`；V22/V25 主子表 | 可按款/工厂/件数拆分排产并回写已排量；没有把一张需求订单拆成多张新需求单的独立动作和父子谱系。 |
| FOB / CMT | `production/controller/ProductionOrderController.java:77`；V25；`ProductionKind` 的 BUY / OUTSOURCE | 共用路由、两种视图和真实排单/审核/节点已存在；成品库存与财务结算尚未接通。 |
| 算料、物料采购、分批收退 | `MrpCalcController`、`PurchaseOrderController`、`InvDocController`；V21/V23；`PurchasePostingPrecision` | 已有双单位和累计尾差平账，不能据此声称含税反算、税额分摊和财务核销完成。 |
| 物料库存、齐料 | `StockController`、`CountController`、`InvDocController`、`SupplyController`；V21/V26、库存不可变 repeatable | 余额与流水分离；`inv_stock` 只有实物 qty / movement_revision。可配、占用、在途由 MRP/采购派生，没有完整四维余额和统一预留域。 |
| 质检、预约、成品仓、报次 | 当前分支 `ProductionReceiptServiceImpl.java:150,256`；V25:141,170 的回货/次品数量/照片；main 的 `module/fg`、API07、V46 | 成品域已在本地集成，512 单测/55 真实 IT 通过；真实生产UI分批收发及顺序反审至零库存已通过，676cdac完整云端512单测/331IT及22集成已通过，完整 QC/预约仍缺。97%/103% 规则仅适用相应领域，不能泛化成所有短溢装结案。 |
| 账单、对账、收付款、核销、月结 | 只有 `settle_method`、账期、税率/含税标记、银行字段和定点工具 | 无完整财务域、会计日期、关账期间硬锁、独立财审/结算状态或财务红冲凭证。MONTHLY 字典与库存估值状态不能代替月结。 |
| 公共标签 | dev 基线 `legacyAttrs` 只读；隔离候选新增 V58 标签/对象/关联表、自动入库、编辑/CAS、删后重建、真实权限及审计 | 已有独立真实 PG/HTTP 与前端组件证据，尚待根集成/云端；列表标签筛选仍未闭环。风格字典与吊牌价均不作为标签库实现。 |
| 逆向流程 | 单据反审/引用阻断、物料退货/退料、回货作废、`StockLedger` 追加反向事件 | 无成品库存退货、完整次品处置、财务红冲；库存冲销不可冒称财务红冲。 |

状态维度现状：业务状态、审批级别与 version 存在；齐料为 MRP 派生结果，生产领料 `issue_status` 独立。财审/结算/月结状态不存在；BOM 只有 DEV/PROD 的 status/version_lock 与订单锁版引用。完整多维状态解耦和订单级快照要求保持未通过。

## 2. 冻结契约与编排

公共契约见 `docs/architecture/production-contract.md` 的“2026-11-22 本轮覆盖”。已有实现必须先审后复用，新增修复按以下边界执行。所有代理共享工作树，不切分支、不提交别人的改动；主控逐阶段审查后统一提交。

| 阶段 | 物理写边界 | 本轮目标 |
|---|---|---|
| S1 | 新迁移、Mapper/Repository、common 发号/锁/精度/查询、必要领域核心调用、`tests/audit-fix/s1-*`、PLAN/进度 | 复核 A1；100 编码上限；索引、并发和金额真实回归 |
| S2 | Service/Controller/DTO、安全/缓存/Outbox/文件、API 文档、`tests/audit-fix/s2-*` | 补首页/订单评审真实 API；复核安全与异步，修复实际剩余问题 |
| S3 | `web/`、`tests/audit-fix/s3-*` | 对齐真实 API，100 编码、网络与矩阵、实际截图验证 |
| S4 | 日志/审计/生命周期、已有 CI、生产 RFC、`tests/audit-fix/s4-*` | 完整执行门禁，修复可观测性/CI问题，区分历史与本轮证据 |
| 主控 | 本台账、公共契约、pom 测试接入、跨模块接线、终审和 PR | 实测、返工、提交 SHA、最终未通过项 |

数据库迁移只追加，不改已经在 main 发布的 V1–V56。当前分支 V1–V36 与 main 相同不代表 main 最高只有 V36；新增成品原样复用 V46，V57/V58 预留给加固/标签，发布前再次检查占号。缺少低版本迁移的未来整合风险按功能补齐契约处理，不能声称零停机兼容。运行日志、EXPLAIN、临时文件和截图只放忽略目录/CI Artifact。

## 3. 功能与模型台账

行号以 dev 基线为准，后续新增位置另注。状态只用“通过 / 未通过 / 云端无法验证”；“未通过（待本轮执行）”不等于断言实现必然有错。

| ID | 级别 | 证据位置与问题 | 修复/验收方案 | 本轮状态 | 修复提交 |
|---|---|---|---|---|---|
| F-01 | P1 | `web/src/mock/home.ts:36`、`web/src/mock/board.ts:5`；`docs/进度.md:73,188`：首页/评审只有前端契约，正式接口缺失 | 实现真实查询、权限交集及错误/空态；删除相应 mock；真实主线 UI | 通过（实际首页/评审API及错误/空态，真实UI） | 8d2e04f / 1d78ebf |
| F-02 | P1 | `docs/rfc/010-审查整改契约.md:81`：公共标签仅附加属性只读，缺标签编辑、关联与筛选闭环 | 按冻结规范实现标签/对象/关联、领域权限、自动入库、筛选、CAS与删后重建；缺原件不阻挡明确需求 | 未通过（标签库/对象关联及full05标签/共享审计通过；UI04+Tags02真实创建、冲突保留、改名、归档历史、重建与拒写通过，P61字形已本地修复。列表标签筛选仍开放；此候选待完整云端） | — |
| F-03 | P0 | `docs/PRD.md:72–79,1923,2112`：当前dev缺样衣、成品、完整QC/预约、财务；main已有成品，不得误报全仓缺失 | 审查复用main成品并加固；样衣按RFC007继续实施；其余链路分别补齐和验证，不能用工具代替主线 | 未通过（成品676cdac完整CI及真实收发/反审通过；样衣、QC/预约及财务链仍待完成） | 676cdac（成品）；其余— |
| F-04 | P1 | `module/production`、API05：FOB/CMT 共路由、排单、回货已有实现 | 真实数据库走订单→采购/外发→分批收退→回货与逆向，不以源码存在判通过 | 通过（既有物料采购/外发/分批收退路径；成品/财务断点仍见F03） | 1aebc97 / dac2a7a；本轮复验8d2e04f / 1d78ebf |
| F-05 | P1 | `TechPackTab.vue`、原型 `3-ui.js/5a-home-style.js`：BOM 浮窗/图像/权限矩阵 | 真 API 页面逐张看截图；保留视觉、加载/空/失败态 | 通过（真实BOM浮窗/成本权限与矩阵，逐张查看截图） | 2f69ccd / 1d78ebf |
| F-06 | P1 | `module/system/service/impl/OutboxStore.java`、`OutboxDispatcher.java`：现有持久任务只消费缓存失效与库存流水导出，不能等同领猫ApiQueue/TaskNotice | 补真实接口投递/任务提醒的生产者、幂等消费者、受控查看/处理与重推；禁止空handler假完成 | 未通过（已明确分离既有状态机与缺失业务消费） | — |
| F-07 | P2 | 新样衣 `module/dev/dto/Sample*Req.java`、`SampleFields.java:25`、`SampleQuery.java:14–20` 的约束缺字段明确的提示；异常层只返回 message，用户无法辨别错误字段 | 保持全部约束和阈值，仅补中文字段提示，保留既有全库 ValidationMessageTest 门禁 | 未通过（11 DTO 仅补提示；第五轮536单测及145 IT全绿，提示约束门禁已通过，尚未提交及云端验收） | — |
| M-01 | P0 | `security/DataScope.java:43–142`、`common/entity/DocEntity.java:43`：原模型为共享公司数据，不是共享库多租户 ACL | 保留拒绝未支持部署模式的硬守卫；明确全域 tenant/company ACL 尚未完成 | 未通过（完整租户隔离要求） | 继承 A2 的部署守卫 |
| M-02 | P1 | V14/V20/V22/V25：已有 SPU/SKU、Header-Items、冻结 BOM 版本 | 验证真实色码/旧订单锁版及旧字段往返；不重复建宽表或假可编辑订单 BOM | 未通过（SPU/主子表/锁版已实测；独立订单BOM快照确实不存在） | dev已有 / 1aebc97；未实现部分无修复提交 |
| M-03 | P1 | V21/V26：库存余额与流水已分离；财审/结算域不存在 | 验证 append-only、反向记录及状态 CAS；未实现状态维度保持缺口 | 未通过（余额/不可变流水/反向事件已过；完整四维库存及财审/结算域缺失） | 1aebc97 / b574e5f / 132ffaf；未实现部分无修复提交 |
| M-04 | P1 | `docs/进度.md:195` 仍写迁移“在途需求订单”，与 RFC010 方案 A 冲突 | 修正当前 PLAN/切换拓扑；不回改历史记录 | 通过 | 132ffaf |
| M-05 | P1 | 历史 review-a1～a4 引用本地不可解析 SHA、旧“尚未创建 PR”及过时验证结论 | 保留历史证据出处并显著注明不可替代本轮；本表只用实际可解析提交 | 通过（历史文档已标注不可替代本轮） | 03ba1ac |

## 4. 生产风险与复验台账

| ID | 级别 | dev 证据/继承实现 | 必须观察的真实结果 | 本轮状态 | 修复提交 |
|---|---|---|---|---|---|
| P-01 | P1 | `common/page/Keywords.java:25`；继承整改仍 MAX_CODES=200，与本轮≤100冲突 | 100 接受/101 拒绝；混合分隔、精确命中、真实索引 | 通过（100/101、Unicode与真实索引；前后端同约束） | 132ffaf / 90ba037 / 1d78ebf |
| P-02 | P0 | dev 仅两类 clientActionId；继承 HttpIdempotencyAspect | 20 并发同键仅 1 次落库，回放重新授权、换体/主体拒绝 | 通过（本轮 S1 真实库专项） | 1aebc97/dac2a7a |
| P-03 | P0 | SerialNoGenerator、StatusCas、StockLedger | 20 发号、双 CAS、50 抢库存、反序多 SKU 同时事务 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-04 | P0 | `Num`、采购累计差额与 Decimal、IdJsonResponseAdvice | 0.01 尾差、负数拒绝、伪造金额拒绝/重算、>2^53 ID 字符串 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-05 | P1 | dev 软删部分唯一索引、已引用主档 RESTRICT | 删后同编码重建与历史引用拒删 | 通过（本轮 S1 真实库专项） | dev/A1 |
| P-06 | P1 | dev StockLog OFFSET、无查询时限；继承 seek、超时和复合索引 | EXPLAIN 实际访问量、无隐式 COUNT、所有池连接时限 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-07 | P1 | dev DictCache 固定 TTL、无单飞；继承命名空间/栅栏 | 100 同起仅一次回源、回滚/慢填充/跨命名空间隔离 | 通过（真实双JVM100请求1回源；回滚与命名空间专项） | dac2a7a |
| P-08 | P1 | dev 无 Outbox；继承持久租约状态机 | 超时接管、旧持有者拒 ack、退避/DLQ/手动重推 | 通过（缓存/库存导出真实DB租约、DLQ及重启接管；ApiQueue/TaskNotice业务另见F06） | dac2a7a |
| P-09 | P1 | dev ExcelWriter:190 byte[]、全量行 List；继承 seek 流/导出任务 | 真实 TCP 断连停下一页；限额、清理、中文文件名 | 未通过（库存流水已实测；全部旧导出要求见P37） | dac2a7a |
| P-10 | P0 | dev ExcelReader 无公式阻断、FileServiceImpl:65 只验扩展名 | =CMD 精确行列拒绝、魔数/ZIP 限制、整批零写 | 通过（真实导入/文件边界专项） | dac2a7a |
| P-11 | P0 | 继承 FieldPolicy/DTO/Batch 范围复查 | 无权限 JSON 物理无价格/银行、Batch 混入不可见 ID 拒绝 | 通过（当前角色/数据范围；共享库多租户缺口仍见M01） | dac2a7a / 8d2e04f |
| P-12 | P0 | dev 每次请求现查权限；继承 session_version/轮换 | 停用/降权即时失效，固定家族寿命、同站 Cookie 校验 | 通过（真实HTTP与生产双实例会话） | dac2a7a / 8d2e04f |
| P-13 | P1 | dev OperLogAspect 直接信 XFF；继承 TrustedProxyResolver | 伪造 XFF 不能绕限流；可信代理/非默认端口同源正确 | 通过（真实HTTP、Nginx及代理边界故障） | dac2a7a |
| P-14 | P1 | dev request.ts 401直接登出、useTable 只丢弃晚响应 | 8/16并发单刷新、真 Abort、无键写请求不重放 | 通过（真实后端16请求单刷新；当前前端105项门禁） | 2f69ccd / dac2a7a / 1d78ebf |
| P-15 | P1 | 继承 IME/广播/固定主体/日期/草稿/局部边界 | 拼音 Enter、跨页签换主体、3时区、故障恢复真实浏览器 | 通过（浏览器IME/广播/三时区与故障回归） | 2f69ccd / 1d78ebf |
| P-16 | P1 | 继承 VirtualTable/SkuMatrixRow/缩略图/存储容错；矩阵增量快路径未核对色码映射变化 | ≥30列及多色码真实渲染，固定列截图和输入局部更新；来源/轴变化重新计算列合计 | 通过（原矩阵轴变化真实失败；主控最终根集成12项矩阵回归、类型/Lint/build通过并亲看截图。1180回货表越界已红绿；换主体截图总计0未稳定复现，不归因于此。成品/矩阵676cdac完整云端已通过；其余最终云端见P21） | 2f69ccd / 1d78ebf / 676cdac |
| P-17 | P0 | dev X-Trace-Id、明文参数日志；继承 SafeLog/ObservedDataSource | HTTP→SQL→Outbox 同 ID、敏感哨兵不漏、500无栈/SQL | 通过（应用HTTP/SQL/Outbox追踪与脱敏；CI原生日志另见P36） | b574e5f |
| P-18 | P1 | 继承 28 表 sys_data_log old/new；用户/角色关系无字段审计 | 真改价 diff、回滚不留事实、只增保护；权限管理缺口单列 | 通过（业务Diff及新增权限审计5项真实HTTP/DB） | b574e5f / 03ba1ac |
| P-19 | P1 | 继承 live/ready、真实借连接探针与排空 | 真断库、SIGTERM在途事务/导出、租约恢复；不以Mock代替 | 通过（本轮S2云端真实进程、DB、导出排空和恢复） | b574e5f |
| P-20 | P0 | 继承 ProductionEnvironmentGuard、产物隔离门禁 | 实际生产 JAR 弱秘钥拒启、无测试入口/Mock；一命令启动保留 | 通过（生产JAR产物/启动守卫；增强凭据另见P27） | b574e5f |
| P-21 | P1 | 已有 `.github/workflows/ci.yml` 综合门禁 | 干净 ubuntu runner 实际结论与 Artifact，不能引用旧本地通过 | 未通过（S1–S4冻结提交全部云端已绿；后续追加修复和功能增量仍需最终门禁） | 90ba037 / 8d2e04f / 1d78ebf / 03ba1ac |
| P-22 | P0 | 当前候选 `web/src/utils/request.ts`：等待旧写入回执后没有二次共享 Promise 检查；核对回执跨越会话切换后仍会改旧收据 | 真 Chromium 复现同体双 POST/双键，以及换账号后旧查询/误删；修复后必须只 1 POST/1 键，保留原身份回执 | 通过（修前真实失败、修后1POST/1键及旧身份回执保留） | 1d78ebf |
| P-23 | P1 | 当前候选 `web/src/utils/orderSku.ts:30`：`Math.round(price * 10000)` 仍先经过二进制浮点乘法，金额合计又转 Number 累加；`TechPackTab.vue:680,691,747,774` 存在 BOM/工艺/差额浮点预览 | 统一十进制定点边界、整数分逐行汇总及显示；覆盖 `1.00185` 舍入反例、大额合计及 `0.018 × 7.5 = 0.14` BOM 成本预览 | 通过（十进制定点反例、逐行汇总与真实BOM预览） | 1d78ebf |
| P-24 | P0 | `V36__append_only_field_audit.sql:53–65` 只保护审计表；`inv_stock_log` 缺 DB 不可变守卫，`InventoryIT:325`、`A1PurchasePrecisionIT:143` 可直接改原事件 | 追加独立 repeatable 迁移，拒 UPDATE/DELETE/TRUNCATE；真实反审只追加；历史夹具改 INSERT，清理限定迁移所有者/测试事务，不能在生产留下绕过开关 | 通过（3项本地专项；首轮CI的流水、大夹具清理均通过，整体另有搜索测试错误） | 132ffaf |
| P-25 | P1 | 当前 `TechPackTab.vue:888,1081` 的尺寸指示和按颜色 BOM 随尺码动态扩列，≥30列时仍普通表；55列容量 fixture 不能代表页面已接入 | 宽表分支复用虚拟表，保留编辑/排序/删除/悬浮；真实 TechPackTab 大尺码夹具截图和交互复验 | 通过（实际TechPackTab宽表编辑/排序/浮窗与截图） | 1d78ebf |
| P-26 | P0 | 当前 `request.ts` 对全部 `/api/auth/` 免会话检查，`stores/user.ts:27` 在 await 后直接写 info/subject | 主控提出慢 `/auth/me` 跨账号风险，真实 request+Pinia 已复现：晚到 200 改回旧用户/权限/subject，晚到业务/HTTP 401 清除新 token；收窄免检、先验 epoch 再触发失败副作用，补 3 项定向回归 | 通过（真实request与Pinia慢200/401跨身份回归） | 1d78ebf |
| P-27 | P1 | `ProductionEnvironmentGuard.java:25` 对数据库/Redis 密码仅 require 非空；`A4EnvironmentTest.java:38–39` 用弱口令作为有效配置 | 增强生产凭据校验并同步匿名测试；保留真实 JAR 启动 Fail-Fast 证据，错误不得回显凭据值 | 通过（本地主控全量单测及4项实际生产JAR拒启） | 03ba1ac |
| P-28 | P1 | 待复用的 `27991d7` HomeService/UpstreamRows 先截断后排序、全量读通知，不能原样导入 | SQL 先授权/排序后 LIMIT；上游默认50/max100游标页、独立total、每单最多100提醒并返回noticesTotal，前端显式翻页及处理余量 | 通过（真实首页与分组游标HTTP；容量风险仍见P33） | 8d2e04f / 1d78ebf |
| P-29 | P1 | `ApprovalServiceImpl.java:146` 的无候选 `waitingForMe` 读全部待审 ID；Order/Purchase/Production 列表据此拼大 IN，首级判断仅 `perm IS NOT NULL` | 三域列表/COUNT/合计使用可信会话构造的 CURRENT 审批 EXISTS，权限码精确匹配；动作补齐只查当前页 ID；1025 待审和实际 EXPLAIN，旧请求注入审批范围无效 | 通过（1025待审/精确权限/真实SQL；夹具顺序修正另记） | 8d2e04f / 03ba1ac（夹具清理） |
| P-30 | P0 | 认证 HTTP 响应的 Set-Cookie 不受前端 epoch 控制：旧退出/刷新/改密晚到可覆盖新登录 Cookie；单飞刷新锁未覆盖认证切换 | 同源 Web Locks 串行认证响应与会话发布；无 Web Locks 时协商 access-only，服务器不读写刷新 Cookie，旧身份任务发出前检查 epoch；真实浏览器 Cookie/HTTP 回归及真实后端模式测试 | 通过（S4实际非安全HTTP源、未注入能力；两页签16个401只刷1次并全部重放） | 8d2e04f / 1d78ebf / 03ba1ac |
| P-31 | P0 风险核查 | 本轮认证锁候选 `web/src/api/auth.ts:16` 持锁后执行改密，需排除 401 刷新二次申请同名锁 | 主控亲读真实浏览器结果：业务/HTTP 401 分别 21/11ms 结束，均 0 刷新、0 残锁；现有非幂等写保护已阻断嵌套刷新，无需增加代码分支 | 通过（排除假设风险，定向回归入库） | 1d78ebf |
| P-32 | P1 | 本轮 `PurchaseOrderServiceImpl.java:1057` 接受空白 tab，但 SQL 返回空列表；`ProductionQuery.cutRiskFirst` 可 GET 绑定，列表与 counts/export 使用不同筛选 | 页签统一归一化；真正拒绝或隔离内部查询参数，不能只依赖 JSON 注解；实测 HTTP 参数与列表/计数一致 | 通过（内部GET参数不参与绑定，注入不改变结果；列表/计数一致） | 8d2e04f |
| P-33 | P1 | `mapper/biz/DocNoticeMapper.xml:27–57` 在全量可见未处理通知 GROUP BY/MAX 后 seek，每页仍聚合完整集合 | 已测六种分布、19查询点、171份真实EXPLAIN；主控完整profile重测：计数后移在10万单组从81.599ms退化到201.343ms，LATERAL在10万空单头从50.234ms退化到192.205ms。保留原SQL，完整聚合的O(pending)容量边界与演进阈值写入RFC | 未通过（实际规模边界已确认；未改变生产聚合SQL） | 8d2e04f / 03ba1ac（测量与边界文档） |
| P-34 | P1 | `PurchaseOrderServiceImpl.java:1064` 当前页来源仍读全部关联算料使用；`SupplyServiceImpl.java:53` 原补货列表仍全量 SKU/SupplyBook，单SKU草稿也无界 | 补货正式改PageResult（默认50/max100），SQL完整计算后分页；来源/草稿SQL预览20条加真实总数，前后端同步并验证真实分页与业务数值 | 通过（真实235SKU/125来源及9项分页浏览器） | 8d2e04f / 1d78ebf |
| P-35 | P0 | `SupplyController.java:28`、`MrpServiceImpl.kitting`：登录即可批量读取任意orderIds的齐料与calcIds，且逐张算料selectByCalc形成N+1 | 混入不存在/不可见ID整批404；订单/生产列表与看板分别授权；看板仅APPROVED/RUNNING普通数据范围，无审批例外；一次批量预加载，真实HTTP混批和SQL次数验收 | 通过（独立看板授权、混批拒绝/1次SQL及6项浏览器失败恢复） | 8d2e04f / 1d78ebf |
| P-36 | P1 | `tests/prod-readiness/a4-lifecycle/ci_two_instances.py:141–184` 对整个 PostgreSQL 服务启用 SQL 日志并通过 docker stdout 采集；删除本地副本后 Actions 停容器仍输出原始 SQL | 私有文件采集、测试库级配置、finally 恢复与清理；匿名 SQL 哨兵在私有日志可见且容器日志不可见，保留实际回源次数核对 | 通过（S4实际Docker、双JVM和最终容器清理日志均复核；100请求1次PG回源；未发现真实商业数据泄露证据） | 03ba1ac |
| P-37 | P1 | `MaterialImport.java:183–190`、`StyleExcel.java:157–164`：旧多表导出只限制主档 50000 条，色码子行先完整加载再检查 Excel 限额；`ExcelWriter.java:102` 最终仍构造 byte[] | 对主子表实际导出行数实施查询前/查询中边界，并按真实内存预算验证；库存流水的 seek/异步/断连证明不可外推到所有旧导出 | 未通过（主数据/四类单据/供应商关联的SQL预算及超长单元格已修；全链路流式、峰值/并发/断连仍待完成） | 03ba1ac / 104f632（修订后完整云端已通过；原479/484失败保留） |
| P-38 | P2 | `web/src/components/SupplierSelect.vue:38–71` 仅展开才查选项，`InvDocFormDialog.vue:214–220` 自动选工厂只改ID | 根终审实际截图退料弹窗显示“供应商 3”；同一选择器更换ID还可能沿用旧label。使用既有无敏感字段options/includeId补名并隔离过期请求，真页面不展开下拉即显示实际简称 | 通过（修前真实失败；主控修后实际API/UI名称回显及11项组件回归，截图亲看） | 104f632（完整云端已通过；原479/484失败保留） |
| P-39 | P1 | `web/src/views/inventory/doc/InvDocFormDialog.vue:103`、`web/src/views/purchase/order/form.vue:114,535` 仍用浮点相乘与求和 | 定点逐行HALF_UP到分再相加，保留未知估值；`0.018×7.5`行价0.14，两行合计0.28，不能先合计再舍入成0.27 | 通过（修前真实UI .13/.27；修后真实保存API与生产表单.14/.28一致，未知价仍空） | 104f632（完整云端已通过；原479/484失败保留） |
| P-40 | P2 | `web/src/views/purchase/order/form.vue:493,551` 序号44px、删除56px，受全局首尾cell padding挤压 | 主控在1440含真实侧栏页面看到序号及“删”省略号；局部调整宽度/内边距，补1180内容宽和三位序号DOM测量及按钮可点验证 | 通过（两个窗口真实组件红绿、9图亲看；真实后端页面DOM和截图复核无截断） | 104f632（完整云端已通过；原479/484失败保留） |
| P-41 | P1 | 新增FG暂存 `ReceiveDialog.vue:68`、`FgDocFormDialog.vue:37` 与 `useUnsavedChanges.ts`：回货草稿缺来源/公司身份隔离；getter临时数量数组不能可靠恢复实际矩阵 | 真实Vue持久化→重载→恢复；按账号/公司/来源或单据版本隔离，恢复确认后再次校验对象，按可见SKU安全重建实际表单 | 通过（676cdac完整云端17项草稿、21真实UI＋1API权限专项、7会话；改动相关12张成品图已亲看） | 676cdac |
| P-42 | P0 | 新增FG暂存 `FgDocServiceImpl` 的保存/删除幂等返回标量，与 `ck_biz_action_result` 仅允许JSON对象约束冲突 | 实际PUT/DELETE成功、同键同体重放、异体1002、当前权限/公司重新授权；保留第一轮500及数据库错误 | 通过（676cdac完整云端；保存/删除幂等、换体/当前权限与公司守卫，实际报文已亲读） | 676cdac |
| P-43 | P1 | `OperLogAspect.java:40,70–72` 默认切面顺序落在幂等事务内：数据库异常后事务尚未回滚，finally查询用户/保存失败日志触发aborted事务 | 操作日志切面置于鉴权之后、幂等事务之外；实测注入数据库失败后业务/库存/字段审计全回滚，失败操作日志仍恰好一条 | 通过（676cdac完整云端；真实数据库失败后全回滚且失败操作日志恰好一条，请求ID一致） | 676cdac |
| P-44 | P1 | 新增FG暂存 `FgStockServiceImpl.byStyle/exportByStyle` 静默忽略库存明细 `ids`；`FgDocServiceImpl.pending` 未拒绝反向回货日期 | 按款汇总明确拒绝不适用的明细ID筛选；明细导出混合跨主体ID整批拒绝；日期范围入口强校验 | 通过（676cdac完整云端；真实400/404与余额不变，原失败证据保留） | 676cdac |
| P-45 | P1 | `InvDocServiceImpl.java:251–253`、`InvDocMapper.xml:73` 合计可超过单行金额上限；DTO总额输出JSON数值，前端普通解析丢分 | 对可增长的精确小数合计使用稳定十进制字符串，保持后端BigDecimal和价格投影；用真实合法2000行创建、SQL SUM、列表/详情与页面往返验收 | 通过（64f4fcf完整云端512单测/334真实IT全绿；524份JSON可解析，主控重新精确求和三份实际响应并亲看18张改动图。极大金额/数量末位、未知NULL/零0.00、窄栏失败重试均通过；原失败保留） | 64f4fcf |

| P-46 | P2 | 新增成品 `web/src/views/fg/doc/detail.vue:290,340`：真实生产页回货号与日期粘连，300px侧栏嵌760px日志表截断动作/状态/备注；库存单、盘点、生产详情同类窄侧栏也已亲看确认 | 日志增加可选紧凑布局，保持原表格默认；真实组件红绿与生产页逐图核对 | 未通过（成品真实生产页和最终库存专项已过；库存/盘点/生产三页独立9项组件检查及12图亲看通过。后三页根集成9项复验、12张最终图亲看通过；待最终云端） | — |
| P-47 | P0 | `DataLogServiceImpl.java:48–72` 仅成品域有父对象权限守卫，其他子行可凭全局审计权和价格权绕过所属单据查看权 | 每种根/子行从可信审计/真实父头解析，逐领域与主体鉴权；未知或删除对象默认拒绝 | 未通过（原真实越权已复现；补齐五类共享根后11单测/50真实IT全过，主控亲读真实拒绝/脱敏JSON及全部XML，冻结10文件与实际运行源码哈希一致；根集成/云端待验） | — |
| P-48 | P0 | 新标签审计候选 `DataLogServiceImpl.list` 默认READ_COMMITTED：公司A授权检查与历史查询之间，另一事务可把单据迁到公司B | 有界审计读取在同一REPEATABLE_READ快照授权、查询、记录访问；真实双连接屏障交错红绿 | 未通过（真实越权交错先红；最新50真实IT全绿，reader502/tx3075为RR、writer505/tx3076提交公司54→55后，原响应不含新公司Diff。根集成/云端待验；样衣资料包多查询同类风险正在另行修复） | — |

| P-49 | P1 | 新增成品 `web/src/views/fg/fgRead.ts:10` 把用户对象替换和Token值变化均视作身份变化；实际同身份fetchMe清空未保存表单 | 比较账号/会话epoch/主体/角色/权限的实际归属；同值资料刷新保留输入，权限收窄与换主体仍中止旧请求 | 通过（676cdac完整云端11会话、24成品、17草稿；同身份刷新保留输入，范围变化仍清理） | 676cdac |

| P-50 | P1 | 新增样衣 `SampleReviewReq` 意见允许1000，而REVISE复制到下一版reason仅500；超长改版意见可能触发数据库错误 | REVISE按UTF-16最多500、PASS/ELIMINATE最多1000；后端入事务前拒绝，前端切结果保留原文并提示，禁止静默截断 | 未通过（前端501字先红、修后评审回归全绿，5图亲看；full04留档引用污染见P60；full05重新取得真实501字code400、500字code0及独立精确快照，536单测+145 IT通过，未提交/云端待验） | — |
| P-51 | P1 | 新增样衣主体迁移PUT的旧主体header/query与新主体body，被通用幂等层误判为冲突 | 注解显式指向可信Controller原主体参数，body仍完整参与指纹；重放按对象当前主体与SELF范围再授权 | 未通过（full04真实公司151→152、同键同体重放一致、变体1002、旧公司404及失权403均通过；主控亲读报文，第五轮536单测及145 IT重复通过，未提交/云端待验） | — |
| P-52 | P0 | 新标签 `TagServiceImpl.object` 的样衣/样衣资料包授权、owner判别与标签查询可跨快照；缓存不能代表当前SELF权限 | 同一有界REPEATABLE_READ读取授权及结果，SAMPLE和TECH_PACK对象标签绕过对象缓存；保留其他缓存与提交后失效 | 未通过（full04两连接真实屏障下SAMPLE/TECH_PACK各冷/暖4用例通过：旧快照仅见旧标签、迁移后404、每次1次DB读取；主控亲读，第五轮完整选择组重复通过，未提交/云端待验） | — |
| P-53 | P0 | `common/file/FileUrlSigner.java:44` 签名有效至7天后的零点，`SignedFileFilter.java:24`与生产Nginx仅验签名；已发出URL不随账号/对象权限变更撤销；旧裸路径还能重新挂入引用 | 按 `file-access-contract.md` 实现当前会话/真实引用读取、关闭旧公开路径、单次待绑定资格与全量旧新路径排序锁；覆盖应用及Nginx | 未通过（契约冻结，V60/DAO与业务SPI隔离实施中；未执行真实撤权下载复验） | — |
| P-54 | P1 | `TechPackSaveReq.java:24–31` 四类明细无条数预算，`PackContent.java:79–157` 子表与批量IN无总量上限，样衣复制/PDF也复用 | 写入前约束总行/单元格预算，查询前及SQL内限额，历史超限明确失败；不先全量装内存后检查 | 未通过（源码确认；待独立有界读取修复及真实大夹具验证） | — |
| P-55 | P1 | 新样衣共用 `useEntityContext.ts` 在KeepAlive离开时取消读取；资料包核价、来源样衣、下游引用及取消上传后返回可能停留失效状态 | 实际慢读取→切菜单→返回；只恢复受影响读任务并保留未保存编辑；同时核对初次激活、来源/引用/品类及等待上传取消 | 未通过（独立7文件修复已终审；真实Vue组件31/31、type/lint/build通过。原生产品类在正确缓存入口实证取消后不重读，修后可选择；来源/引用/上传残留亦恢复。初载3 GET原本各1次，不列漏洞。真实后端样衣UI通过，但专项KeepAlive路径仍以前述组件证据为准；云端待验） | — |
| P-56 | P1 | 新样衣 `mapper/dev/SampleMapper.xml` 批量码过滤位于多LATERAL组装之后；真实5001行列表计划命中索引仍把当前版本/节点各循环5001次，最后仅命中2行 | 先按命名空间/公司及≤100编码定位有界候选头，再按原授权/筛选组装；测试同时校验真实列表和COUNT的实际处理行数与循环 | 未通过（同5001行真实红绿：LATERAL 5001→2次、头访问5002→3、shared hits15166→38；100码/SELF/主体/日期/状态通过。主控亲读全部原始计划与报告并叠入候选，第五轮完整选择组通过，未提交/云端待验） | — |
| P-57 | P1 | 新 `R__zz_sample_field_audit.sql:13–50` 的审计 CHECK 调用两个 SQL wrapper，wrapper 内 helper 依赖连接 search_path；真实 pg_restore 空路径装载时报函数不存在 | 仅在未发布的新样衣 repeatable 固定两个纯值 wrapper 的函数级 search_path；不改旧迁移、恢复连接或放松 CHECK | 未通过（独立真实恢复先红后绿；第三轮受旧重放测试污染再次失败；第四轮隔离重放后恢复成功，36样衣+2安全审计hash相等、空search_path与原CHECK守卫通过。未提交/云端未验） | — |
| P-58 | P1 | 新 `mapper/techpack/TechPackMapper.xml:98–107` 的转款批量 UPDATE，在真实50000资料包头上触发30秒SQL超时、25秒HTTP超时 | 对当前固定来源的整组version边界检查去相关；保留50k、全部触发器与原超时，用真实旧/新计划和HTTP复验 | 未通过（旧/新真实非ANALYZE计划亲读；专项18单测及13不同IT通过，50000行Mapper实际12683ms；25秒HTTP/30秒SQL不变。full05完整536单测+145 IT通过，未提交/云端待验） | — |
| P-59 | P1 | `dev/ci/redaction.py:54–75` 把DataLog的deleted_token对象替换为裸占位符而留下内部JSON；真实样衣UI02虽业务通过，两个证据JSON无法解析 | 结构化处理敏感值并保持JSON完整，正确保留业务软删除标识；原JSON/XML/文本凭据测试与新增对象/数组/数值边界均须通过，再重跑真实UI | 未通过（旧源先红、修后22新旧检查全绿；UI04+Tags02真实生产两项通过，8份JSON可解析、49报文亲读。本地通过，当前整合候选待完整云端） | — |

| P-60 | P1 | 新样衣 `S2SampleFixture.java:129–132` 将可变请求Map直接留档，后续501→500修改反向污染已发送请求证据 | 发送前只序列化一次，同一字符串用于HTTP与精确留档；独立JSON树快照，嵌套Map/List、64位ID和Decimal字节回归 | 未通过（2纯Java回归与full05真实HTTP501/400、500/0精确留档通过；536单测/145 IT全绿。旧错误原件保留，当前整合候选待完整云端） | — |
| P-61 | P2 | 真实UI03 `TechPackTab.vue:1172,1230` 的BOM右侧来源可见部分被裁，Tags01 `TagHistoryDialog.vue:56` 改名说明出现缺字；原HTTP中文正常 | 检查横滚/固定列可达性与字体加载；独立窄修后真实DOM与截图复验，不删列或改业务数据 | 未通过（原14图问题保留；窄修2生产/2测试文件后10项组件检查通过，主控亲看10图；UI04+Tags02真实生产49报文/14图再验通过。BOM横滚/来源列与中文历史已本地验收，待完整云端） | — |

## 5. 本轮执行记录

- 环境准备：前端锁定依赖已安装；PostgreSQL16.16、Redis8.0.2 和 Chromium headless-shell 已实际启动验证。当前 exec 的代理端口动态变化，运行 wrapper 会即时生成 Maven settings；prod-readiness 依赖已解析。首次编译发现仅 Java21 运行时；现已补齐完整 JDK21 并验证 `javac --release 21`。无 Docker；本地 Redis8 不能冒充 CI 的 Redis7 验收，真实引擎门禁不降级到 SQLite 或 Mock。
- 前端先观察到 P-22 三项故障均失败，再报告修复后的 3/3 通过；匿名前后证据在忽略目录 `test-artifacts/full-audit/s3/`，尚待主控复验，不能替代全套浏览器验收。
- 主控读取了本轮实际并发 JSON：20 个唯一连续单号；20 同键请求仅 1 个 200、19 个处理中 409，回放/落库断言通过；50 抢 10 件库存得到 10 个成功、40 个库存不足。已亲见 API 的 `id="9007199254740993"`、数字 `version=0`，以及 seek 计划使用 `idx_inv_log_seek`、实际读取 101 行。主控还读取了 150 份实际查询计划汇总及 Unicode 精确查询原始计划：22 张每表 10 万行级夹具下，测试覆盖的查询未扫描大表，12 份 Unicode 混合分隔计划精确命中 2 行并使用编码索引；这不覆盖所有任意短关键字，也不是生产 SLA。完整测试收尾和新库存不可变守卫仍在验收中。
- 主控已逐张打开当前前端宽表中/末段、矩阵输入、局部错误边界及 4 张权限表单截图；这些是生产组件 + 标注故障注入证据，不能当成真实后端全链路页面验收。真实基线已使用独立 dev 构建与新建匿名测试库成功运行；主控打开订单列表发现截图停留加载态，已退回补拍稳定结果。重新捕获的 7 页均确认真实 API 成功、可见加载/骨架和 pending API 为零，console/pageerror/request failure 为零；主控已逐张复看全部 7 张稳定基线图。基线逐 SKU 保存值相同，不能称作本轮复现的损坏。稳定截图脚本纳入 S4 CI整改。
- S1 主控终审：亲自运行 `-Pprod-readiness -Dtest=KeywordsTest,S1KeywordContractTest -Dit.test=S1InventoryLedgerIT,InventoryIT,A1DatabaseProductionIT,A1HttpCreationIT,A1PurchasePrecisionIT verify`，exit 0，7 单测 + 38 IT 零失败/错误/跳过，耗时 74 秒。亲见流水原事件 `qty=2, amount=6` 与只追加的冲销 `qty=-2, amount=-6, reversal_of_log_id=原事件`，净额/净量为零。证据 `test-artifacts/full-audit/s1/root-guard-proof/results.json`；新守卫下的大规模清理路径仍留最终完整 profile。首轮 7 单测 + 36 IT（150 份 EXPLAIN）证据单独归档为 `baseline-proof`，不得混称包含新守卫。
- 基准表：未取得。原系统访问只允许只读；未使用账号、凭据或真实商业数据。
- 首轮云端 CI [45055022366](https://github.com/xiaoliangao/lingmao/actions/runs/45055022366) 对应 `132ffaf`：497单测通过；243 IT中242通过、1错误，唯一错误为旧 `OrderIT.listCapacityExplainRecordsRealSql` 仍传200搜索词。前端类型/Lint/构建/浏览器、真实Nginx/PostgreSQL日志隐私、备份只读与恢复演练通过；依赖后端的双JVM/进程排空/真实UI步骤未执行，不能记为通过。主控修正旧容量测试后亲跑7单测+12 OrderIT，exit0，保留万行数据及真实EXPLAIN；发布修正 `90ba037891ab108577df63ec39a584f435cb1fad`。
- S1 修正云端 CI [45057802619](https://github.com/xiaoliangao/lingmao/actions/runs/45057802619) 全部成功：497单测、243 IT零失败/错误/跳过；实际双JVM的100请求只回源1次；19项生产JAR/探针/进程排空检查、20项真实UI和7项真实会话检查通过。双页签16个401只发1次刷新并重放16次成功。主控下载并核对 Artifact SHA256 `69c955f7741ee21ece6e9f521b8a4a4f31405a0803e1c6fbff470282e8162d88`，亲读实际报告；这些证据对应S1，不能代替后续阶段。日志复核另发现P36，说明该次全绿不覆盖CI采集日志边界。
- S2 已发布 `8d2e04fc1743ab57dbc2a0f721406849c29a0c8d`，tree `220a9647b30d39d5440c300459b565652384d08c`；独立工作树构建后端835主源码/181测试源码通过，前端类型/Lint/build、19单测、18核心测试、9项有界列表浏览器检查通过。云端 CI [45060621823](https://github.com/xiaoliangao/lingmao/actions/runs/45060621823) 已成功：497单测、270真实DB/HTTP IT零失败/错误/跳过，双JVM100请求实际回源1次，19项生产进程和7项真实会话检查通过。主控下载Artifact并核对SHA256 `82eac1dd0cc7c29c870fe878fc433dde3a36a3e24f357b8e02e5f76158424f2a`，读取实际XML和JSON；该提交不含S3/S4后续修复。补货/首页API与调用端按同一提交原子发布，避免阶段中间契约不一致。
- S2主控独立运行 `-Pprod-readiness -Dtest='*Test' -Dit.test='S2*IT,ApprovalServiceImplIT' verify`：497单测+18真实DB/HTTP IT，exit0，1分42秒。实际类别XML与汇总保存在 `test-artifacts/full-audit/s2/root-review-reports/`，只归档该轮运行类别，不混入target遗留报告。该阶段不含之后的P34/P35与S4改动。
- S3阶段主控独立执行 typecheck、Lint、23单测、19核心测试与生产build，全部exit0。另亲读60项A3、22项write-recovery和8项双页签认证报告，打开真实TechPackTab宽表/色码悬浮、百行序号、首页/评审降级、上游50→2→50分页及处理后剩余5/10条截图；已采集的pageerror为0，console仅已采集子集无非预期错误，未采集的认证/写入子用例不作零错误声明。该阶段报告对应源码指纹 `d8a4e6826104cc268b2483faec60dc3bd5f316bfda58ceeb2165733ea72a1e4d`，不能替代后续P34页面改动验收；实际非安全HTTP测试域在当前云环境ERR_EMPTY_RESPONSE，只有无WebLocks能力注入通过，CI强制实际源检查。
- P34/P35主控独立运行 `-Dtest=ValidationMessageTest,ArchitectureTest -Dit.test=S2BoundedSupplyHttpIT,S2KittingHttpIT,S2SupplyRiskHttpIT,PurchaseIT verify`：8单测+17IT，exit0，42秒；只归档本轮类别到 `s2/root-p34-reports/`。亲见真实235SKU分页、20/25可见草稿、订单号和款号各20/126预览、125个算料来源、24个confirmed算料一次在途读取；原采购180缺口→生备货草稿→审核后不再缺货仍通过。P34前端待当前页面回归，不能只用后端结果销项。
- P33专项：7单测+6IT，exit0，19查询点、171份实际EXPLAIN；主控亲读汇总和两种退化的原始执行计划，确认测试保留真实约束/审计且不强制规划器。候选优化均有偏斜分布回退，因此没有改动生产SQL，不能销掉全局通知聚合的容量风险。
- P35二次终审：原17IT未覆盖仅有生产看板权限的用户。新增真实HTTP先得到APPROVED/RUNNING两行，再查齐料返回404，测试exit1；据此增加独立看板授权分支，并补禁止草稿/待审/已完结、错主体和混合ID的回归，修后8单测+4真实HTTP/DB IT通过，主控亲读红绿报文；前端4个消费者的加载/失败/重试仍待终审。
- S4专项实测：新5IT与3单测在纠正夹具权限名后全绿，首轮错误保留；主控亲读用户停启/改密、角色code/data_scope、授权联合键长ID、500安全响应和真实回滚JSON。验证事务只持ShareUpdateExclusiveLock时另连接业务和审计写入成功；4项独立生产JAR弱DB/Redis配置均在连接池/HTTP启动前退出1，日志无口令值。完整profile及进程生命周期保留最终门禁。
- 最终主控本地完整后端 profile：`./mvnw -B -ntp -Pprod-readiness verify`（通过本地服务 wrapper），运行2206秒，498单测通过；275 IT中274通过、1失败、0错误/跳过。唯一失败 `S2BoardHttpIT` 预期1025、实际1026。主控保留完整日志SHA256 `93af17ed0baf6f17b9fae9ea9c813d2087db82f71e67991ff6b73d19a34f9a57`、本轮113/59类XML于 `final-root/`。原因是先执行的 `S2KittingHttpIT` 留下自己创建的共享审批权限步骤；该额外单据确为合法待办。修复限定为 finally 删除该用例自己的 ORDER/doc_id 步骤，保留1025/最旧20/普通列表无越权全部断言，随后在新库强制 Kitting→Board 顺序复验；不能把这次全量执行写成成功。
- 最终主控前端复验：类型检查、Lint、23单测、19核心测试、生产构建均exit0；60 A3 +22写入恢复/页面 +8认证 +9有界列表 +6齐料，共105项浏览器检查通过。源码219文件指纹 `a5c0b72ed1f8ea19a5df11b2a36a170eeaf80aa2d239faf605d0322decd4c0e1`，构建183文件指纹 `5ea6b99c83ad9f9fc90fad145db256ff424097b887b6df6e5ab0b7ea79ab97c9`；主控逐张打开当前19张A3图及P34/P35页面图。所有已收集pageerror为0；A3与齐料的预期故障console分别43/5，非预期为0。部分写入/认证子用例未收集console，不能扩大为105项全部console零。实际非安全HTTP源本地仍不可达，能力注入不替代最终CI的严格源验证。
- S3冻结提交 `1d78ebf6332792f163ed2a38d43e95bf814a8092`，tree `9a8aa612aef47db46731bfbeb09664d8a46a0939`，44文件；通过临时验证引用 `audit/validate-s3-1d78ebf` 运行独立云端门禁，避免取消S2验证。工作分支尚待该次结果后推进；测试文档、最终CI和后端追加整改归S4。
- P36主控复验11个私有日志helper测试和4个文件/进程测试均通过；正常/取消清理共享最多7秒预算，取消中途不得重启预算。真实Docker collector/OS取消/双JVM回源必须以最终CI验收，本地helper与受控时钟不冒充真实容器证据。
- P37实际红绿：旧S2代码在新库 `scm_p37_red_test` 跑5单测+1IT，单测通过、IT按预期失败，实际子行SQL没有ORDER/LIMIT；失败并非响应文案。当前代码在新库 `scm_p37_green_test` 按 Master→Kitting→Board 跑5单测+14IT全绿，原1025/最旧20严格断言通过；3种50001子行均在有界SQL后拒绝，4个正常匿名工作簿验证公式转义和独立成本列权限。另在 `scm_p37_boundary_test` 定向5单测+1IT通过，真实下载1430933字节，工作表50000行/50000唯一SKU，缺失和多出均0，生产导入ZIP单项阈值仍16777216字节。首次边界验证器受该阈值失败，随后一轮测试编译错误；两次失败均保留，未放宽生产阈值，最终改用测试独立有界ZipFile+安全SAX核对。首次验证器失败未保存下载原件，不能补造该轮文件；最终实际文件已保存。所有专用库分别清理，根scm_test未广删。主控已亲读红绿SQL、HTTP JSON、列权限结果与最终精确行数证据。
- 主控核对 main 已发布的 V1–V36，共36个SQL文件内容全部未变；本轮只追加独立repeatable。全web/src的TS/Vue扫描未发现裸any。最终CI YAML保留原push/pull_request/workflow_dispatch触发，30步骤的24个脚本引用均存在，没有绑定阶段分支。
- S3独立云端 CI [45062133112](https://github.com/xiaoliangao/lingmao/actions/runs/45062133112) 已全绿，准确head为 `1d78ebf6332792f163ed2a38d43e95bf814a8092`。主控下载并独立核对18,800,379字节Artifact的SHA256 `86a7377d99ff69de992c8ad2d2689f954f4bb52ecd1edf518228f243cf70a416`，亲读497单测/270 IT原始XML、60项A3、19项生产进程、2JVM100请求1次PG回源、20项集成（19真实UI+1 API专项）、7真实会话JSON。所有JUnit零失败/错误/跳过，A3仅43个预期console、会话仅19个预期401，均无已采集的意外错误。完整1154文件和job日志原样保留；该提交不含S4新增门禁和P37。
- S4冻结提交 `03ba1acbe08893363d1078a82e1c73d2d988e46d`，tree `8012e5d3eb293392b798ea41eb537c2a094816ee`，39文件，通过独立验证引用运行 [45064641469](https://github.com/xiaoliangao/lingmao/actions/runs/45064641469) 已全绿。主控下载 Artifact 14772200442，21,992,770字节/1228文件，SHA256 `f93b2223af3177756fa68fc3147a3183ef1ca5c55a9f89b1f019d68c81597d0e`；亲读原始JUnit：503单测、279 IT零失败/错误/跳过，23项生产JAR进程检查全部通过；真实非安全HTTP源verified=true且capability_injected=false，双JVM100请求仅1次PG回源，最终停止容器日志无原始statement/execute输出。20集成（19UI＋1API）/119图与7会话通过；notice的19查询点/171计划只证明P33测量，未冒充其修复。主控曾对本地1481个跟踪文件逐个计算Git blob，全部与该树一致，随后才开启P38/P39和P37追加修复；此次CI不覆盖后续代码。
- S4本地主控新库真实联调 `python3 ../audit-runtime/local-smoke.py --phase after --artifacts test-artifacts/full-audit/after` exit0：20集成（19真实Vue UI +1 API权限专项）及7真实会话通过，真实生产JAR SHA256 `8f035ddf8dde33273f9ebc434904c0eb1fc3e9fa3989988a8b9931fc0788feae`，未使用Docker或冒充CI。主控亲自逐张打开全部119张截图，读取20份实际API断言与角色响应；已采集console/page/external错误为0。无成本角色 `/api/tech-packs/1` 的实际JSON摘录含字符串ID，物理不含unitCost/buyPrice/BOM refPrice，保存于匿名证据。第20项是仅API专项且未枚举所有最终状态，不能冒充第20个UI页面。该轮运行通过仍发现P38显示缺陷与P39代码缺陷，已退回修复，未用测试绿代替终审。
- 已建立 [Draft PR #9](https://github.com/xiaoliangao/lingmao/pull/9)，base `dev`，工作分支已推进至 `03ba1ac`，主控重新读取GitHub确认可合并且未合并；继续修复，不宣称全量完成。所有新增进展须记录具体命令、退出码、结果数量、证据位置和实际SHA。
- P38/P39主控独立执行typecheck、Lint、24单测、19核心、生产build、11组件浏览器全部exit0；11张组件截图逐张亲看，1个预期503、无意外console/pageerror。另跑新库真实生产JAR/UI `--artifacts test-artifacts/full-audit/after-p38-p39`：21集成（20UI＋1API）及7会话全过、125张图、console/page/external全0。JAR SHA256 `1e63ede24abc983d3198b0e710e76834ad2409f2fb3c20a8fbe48c728beab04d`，此时工作树有未提交修复，不能冒充03ba1ac产物。实际仓库预选工厂简称正确；真实存库采购两行0.14/总0.28，库存0.14/NULL与生产表单一致。根亲看新增金额、名称及横向滚动图仍发现P40首尾列截断并打回。


- P37单据追加：采购/物料出入库/需求订单/生产导出均在SQL查询中限制主行和关联行；红基线实际记录无LIMIT，修后5单测+12IT通过，选择器纠正另跑5单测+1IT通过。真实101父行/50001子行、251订单/50001款式关联受控拒绝，8个正常工作簿顺序、公式转义和列权限未变。主控已逐行审查相关diff、实际绑定SQL和工作簿结果；证据 `s2/p37-documents-runs/`。父表50001 LIMIT以真实绑定SQL验证，不冒充50000父行容量验证。
- P37供应商追加：冻结旧03ba在独立专库跑5单测+5IT，其中4项预期失败（两类关联无SQL LIMIT/继续读第三批，超长格500，DTO缺长度白名单）。修后9单测+13IT全绿；每类2001父档/51000关联，实际SQL剩余预算50001→2且不读第三批。700个品类名称合计35699字符，HTTP业务码1000准确给出供应商管理第2行第6列，不回显内容、不发送下载头。32767字符完整保留、32768拒绝、公式前导转义计入长度、失败回收SXSSF临时文件。主控亲读红绿XML/原始SQL/HTTP JSON及正常工作簿列名，记录于 `s2/p37-supplier-runs/root-review.json`；新JAR SHA256 `4420a891a34d8b10ba1d750b8da3674bf851b7fe27515aee6410e57874f95982`。仍为有界List和byte[]，不销掉全流式要求。
- P40组件终审：主控独立运行typecheck、Lint、24单测、19核心、生产build和新增布局在内12项组件浏览器，全部exit0；亲自打开全部9张布局修复前后图。1440/1180窗口下，100的可用宽28px、文本宽21.703px，只有1行；删除按钮30px小于可用36px，真实本地删行100→99且0写API。源码/构建指纹分别 `a78313acef0043e52aa1cfba719ad3619d784d3f2d34407079a698ec048aaa63` / `c72de9139d839c927c7529a070205fa34ed2f55d24ebff0f1e73f880350abb39`；仅预期503一个，无意外console/pageerror。生产后端页面另行验收，组件合成GET不替代真实业务。


- P37–P40最终本地联调：主控新建独立匿名库运行 `local-smoke.py --phase after --artifacts test-artifacts/full-audit/after-p37-p40`，21集成（20真实UI＋1API权限专项）及7会话全通过，169秒真实UI，125张截图，已采集console/page/external为0。使用上述4420a891新JAR及P40生产dist，工作树未提交；不是03ba1ac既有CI。主控亲读实际API/DOM/会话报文并逐张打开本轮7张改动相关生产页截图：自动工厂简称正确、采购行金额0.14/合计0.28、库存未知价NULL显示—；首列可用26px、文本7.234px，删除可用34px、按钮30px，全部完整可见。两页签16个401只刷1次并成功重放；旧119张全页逐图证据仍单独注明为S4版本，不混称本轮已逐图查看全部125张。

- 成品根集成后端：在独立干净源码/新库以PG max_connections=100、Hikari=12运行，512单测+55真实IT零失败/错误/跳过，158.538秒；生产JAR SHA256 `493008737370e4ea98199282445c8cdcbee851901ed3e5110997e4d22f2a0ab3`。主控亲读50工人/12真实PG后端抢库存25成功25不足且最终0、反序多SKU50成功、2000流水seek每页实际访问101行、角色价格剥离与实际回滚日志。第一次max_connections=35的7个连接错误原件保留，不算成功或产品断言失败。证据 `fg/root-integrated-runs/second-ci-connection-limit/`。
- 成品根前端：23项成品、17项草稿、12项矩阵实际Vue浏览器检查及类型/Lint/24单测/19核心/build通过；首次真实后端UI的前21项通过，第22项先后因测试错误选择器、以无订单查看权账号调用订单汇总接口失败。第二次真实发货200、库存396→196、价格字段物理剥离已亲读；后续反审链尚未执行完，整轮不计通过。真实页面发现P46后已退回，紧凑日志4文件源码指纹 `a7990ca47f3624fa269657b077ea420520358331a33313091755cc54c7abcdd5`，主控复验类型/Lint/单测/build与6项浏览器检查通过，7张修后图亲看。证据 `fg/root-real-ui-first/`、`fg/root-real-ui-second/`、`fg/root-web-compact/`。
- P45真实精度：原代码3项HTTP/SQL测试按预期失败，合法2000行SQL金额 `1999999999999979.99` 经JS解析成 `1999999999999980.00`，数量 `199999999999997.999` 变 `199999999999998.000`，未知金额错误合计为0或仅已知部分。修后3项全部通过，列表/详情精确字符串与SQL SUM一致，纯未知/混合未知均NULL，无成本权限JSON物理无价格/总额。主控亲读原始响应。组合回归512单测通过，22IT为21通过/1错误：旧50000行sty_style_sku清理触发30秒statement_timeout，整轮exit1；后续已将夹具建数/清理分批且保持50001边界和全部生产约束，最终执行待完成。证据 `p45/root-runs/original-http/`、`fixed-http-and-export-seed/`。
- 审计权限真实红基线：无order:order:list但有data:all/system:audit:query/order:price:query的账号通过子行ID读到订单价89、金额890；独立受控交错中reader在公司1校验后暂停，另一真实PG事务将订单与标签注册主体改2并提交，原请求读到这两条更新Diff。主控亲读HTTP/两连接PID/事务号/数据库事件；未手造审计行。两轮都以断言失败保存原件，RR修后全量回归仍在运行，证据 `tags/s4-runs/child-old-guard-red/`、`read-committed-interleaving-red/`。

## 6. 放行判定

PR事件门禁 [45068526291](https://github.com/xiaoliangao/lingmao/actions/runs/45068526291) 再次验证 `03ba1ac` 全绿：503单测、279IT零失败/错误/跳过，23生产进程检查、7真实会话及双JVM100请求1次回源通过。主控下载22,789,344字节Artifact 14773666630，SHA256 `b29e096bfccea16d5a290a8ab2772eea431371c45de377b50b9d5b2984be9b08`，亲读原始XML与JSON，并逐张打开此次同浏览器捕获的7张原始PR基线页面图；原始基线API真实成功、console/pageerror均0。这次仍不覆盖后来P37–P40和FG增量。追加代码审查确认SKU矩阵只有行级隔离，P16已恢复未通过，直到实际单元格渲染计数验收完成。

存在未通过 P0/P1 或业务基准缺口时，PR 保持 Draft，并在正文保留全部未通过项。代码可以提交供审阅，不等于允许生产上线。运行截图必须由主控逐张打开；历史截图或历史报告不能代替当前页面。不得声称“100% 完成”“隐患清零”或远端 CI 全绿，除非相应范围有本轮真实证据。

- P37–P40独立候选 `479c43d365300ab1ea4dcdf1608dfa940ebffedf` 的云端运行 [45069911409](https://github.com/xiaoliangao/lingmao/actions/runs/45069911409) **失败，未推进PR**。主控下载并核对Artifact 14774087425（23,080,465字节，1267文件，SHA256 `c275ac58bcf752fb5212286d99b26fd7de495562c7a19818bfb069577b01c1e7`），亲读507单测/290IT零失败原始XML及真实业务21集成、7会话结果。T11在44项后因安全测试桩遗漏新增supplier/options调用失败；T12完成18项后在停机PG会话残留断言失败，旧测试未记录残留PID归属，尚不能断言是假阳性或真实泄漏。完整日志与失败原件保留，补严格options授权桩及精确JVM会话归属后重新完整验收；其他步骤通过不替代整轮门禁。

- 对479失败门禁的修订已本地真实复验：主控在根工作树执行完整T11，61项全部通过，112.405秒、pageerror和非预期console为0（43个故障注入console单列）；供应商/金额/布局12项全部通过，28.580秒、仅一个预期503。两脚本SHA分别bd7dd03d与c5a80996，修订只补严格supplier/options白名单和有界响应读取，未屏蔽未知请求。T12使用精确4420a891生产JAR和2eae42f8脚本，23项全过、231.788秒；实际180000行18578102字节XLSX完整排空，10条精确JVM客户端会话全关闭，第三条真实无关观察连接仍存活且仅旧选择器误计。主控逐行审diff并亲读所有会话元数据。原479失败的具体残留归属仍未知；以上本地结果不替代新候选的完整云端门禁。

- 修订候选 `48448031d571c87625ef60737b62832ac4cd9e90` 的父提交是 `03ba1ac`，没有把失败候选479纳入PR历史；27文件通过独立引用 `audit/validate-final-review-4844803` 触发云端 [45075744879](https://github.com/xiaoliangao/lingmao/actions/runs/45075744879)。已失败，未推进PR：507单测通过，290IT为289通过/1错误，唯一错误是主数据导出测试建50000条style_color夹具时触发30秒statement_timeout；T11 61项及供应商金额12项通过，T08/T12/真实UI未执行。主控下载并核对Artifact14776492399，8,576,577字节/1086文件、SHA256 `cf4fb699afc990b42275a8d7515210679145e22fe22ddce07a4e91e244bf1a80`，亲读原始XML/日志。修订夹具仅分批建数/清理，不提高生产超时、不降边界、不关闭约束；须新候选完整云端重跑。成品、标签及之后的聚合精度修复不在这份候选内。
- P44主控真实红绿：原服务在新私有库中11单测通过、2IT均按预期断言失败；第一次补丁复验因保留源文件旧mtime导致Maven复用旧classes，23IT中仍2失败，该次保留为失败而非成功。运行器改为每轮删除完整隔离target后，实际重新编译887个主源码，`S2FgQueryContractsHttpIT,S2FgHttpIT,SchemaConventionsIT` 共23IT和11单测全部通过，86.915秒。主控亲读原始XML/HTTP JSON：四个不适用ids筛选返回业务400，混主体明细导出HTTP404且没有下载头，反向日期返回业务400，合法主体库存7与另一主体111未改变。证据 `fg/root-query-runs/`；最终API07文字澄清另行记录，不伪称测试修改了接口文档。
- FG前端候选 `c7249c50171d` 的实际Vue测试：23成品、15草稿、12矩阵均通过，类型/Lint/24单测/19核心及生产build退出0。主控阅读了表单、详情、取消控制和矩阵实现，亲看仓库503/恢复、换轴后行列合计10等截图；这些使用合成HTTP，只证明组件行为。继续审出 `ReceiveDialog.restart()` 未决确认可能跨来源生效，已要求实际复现；候选尚未验收合入，不覆盖真实后端联调要求。

- 28文件候选的干净快照本地复验：`root-export-fixture-run.py bounded-fixtures-frozen-28` 退出0，6架构单测＋4真实HTTP/SQL/完整XLSX测试全部通过，113.358秒；PG100/Hikari12，statement_timeout仍30秒，全部外键/审计约束保留。实际50001子行受控拒绝，50000 SKU全部导出且不重不漏；建数每条SQL≤5000行、清理实际分批至0，PG/Redis正常退出。生产JAR SHA256 `93238f40abf43524f80efe43ee50328ac8f43257a517474b1f80e790bacdf214`。主控亲读原始JSON、SQL、运行结果和停库日志；此范围不等于整轮云端或全流式导出通过。证据 `exports/root-runs/bounded-fixtures-frozen-28/`。

- 28文件独立候选已发布为 `104f6328a607f53235f5c57c880d1fbb06bc49c2`，父提交03ba1ac，验证分支 `audit/validate-bounded-fixtures-104f632`，云端 [45082492915](https://github.com/xiaoliangao/lingmao/actions/runs/45082492915) 已全绿，并以expected-head保护推进PR到104f632；未继承失败479/484候选。

- 标签审计修订独立验收：11架构/调度单测＋39真实IT零失败/错误/跳过，106.91秒，PG100/Hikari12/context-cache4；主控亲读全部XML计数、子行403、RR双连接与Outbox原始JSON。reader PID473/tx2830 repeatable read，writer PID476/tx2831已提交公司40→41的真实两条Diff，reader只返回原公司快照；未手造审计行。隔离队列目标141实际成功/attempts1并删除Redis键，原队列139PENDING及sentinel140 attempts0保持不变，数据库业务namespace仍single；HTTP500、slow_sql、outbox_started/completed同trace且不漏数据库错误。证据 `tags/s4-runs/repeatable-read-complete-isolated-queue/`，原失败轮仍保留。其他5类共享审计根、根集成和云端仍待验。

- 成品真实生产UI第三轮完成：`fg/root-real-ui-third/` 新库/4930087373生产JAR，22集成与7真实会话均通过，179.228秒UI；28份成品实际HTTP、300＋96入库/200发货、阻止制单自审403/下游作废1005/负库存反审1001，顺序反审后0库存，5原流水和5一一对应反向流水，ID字符串和requestId完整。主控亲读全部成品报文与10条流水，逐张打开12张新增成品图；137张总图不伪称本轮全部逐张查看。22份报告console/page/external为0，真实会话两页签16个401仅1刷新并全部重放。成品侧栏日期/日志已完整；库存流水时间仍被截断且反审缺可见标识，主控继续修，两处布局的组件3检查和type/lint/24unit/19core/build已通过，生产UI专项待复验。

- 成品库存最终布局专项：`fg/root-real-ui-ledger-layout/` 使用同一4930087373生产JAR，实际执行第22项成品链（23.787秒）及7真实会话，全部通过；不是再次执行整套22项。主控复核28份成品原始HTTP与10条流水，最终库存0；10个时间戳宽89.953125px小于166px可用宽、5条反向流水均有可见“反审核”标记，亲看本次最终库存图。其余11张新增成品图的逐图依据仍是第三轮，不混称此次全部查看。根类型/Lint/24单测/19核心/build和3组件检查均已通过。

- P45最终真实页面：`p45/root-real-ui-item-layout/` 实际仅执行第23项精度测试，26.562秒通过；JAR SHA256 `4fad3153d17ae47b8310186de43e3b34d84c9167ab8d157f12126f5846c71512`，web index SHA256 `afaa47b5bf65498a7ff520412107db1b75e10707a9182c78a28424e5feb5d07b`。20色×100规格真实API建2000 SKU及两张2000行单据、一张2行混合未知价单据；主控亲读三份完整实际响应的头/行值及全部DOM指标、逐张打开6张最终截图。合计金额 `1999999999999979.99`、数量 `199999999999997.999` 保持精确字符串，混合未知金额NULL、已知零0.00；列表和明细数字全部宽度足够。上一轮虽合计断言通过，主控看图发现明细单价/金额截断，已扩列后真实重跑；原图保留。上一轮summary误称全套执行，另存元数据更正且没有覆盖原件，当前运行器明确selectedTestExecuted/afterSuiteExecuted=false。该补丁仍在隔离暂存，待根集成及云端。

- P46窄侧栏补充：`inv-compact-stage` 的6文件只在库存单/盘点/生产详情启用既有紧凑日志组件；9项实际Vue组件检查、类型/Lint通过，主控逐行审补丁并亲看全部12张1440/1180/503/恢复截图。源码指纹 `de0c5a6c8a7d782ba0cccedbbbaf15dd8ac0cf7b60fd0a3a75428bc9191ed9b0`；使用合成只读HTTP，不冒充真实后端。标签标题4文件补丁指纹 `7260ed898fc17310f5e4df0834ebd12ab77eb15c282ac130df5fe765bc9f636c`，5项标题与1项日志检查、全部5图已亲看，明确款式/资料包对象；同样未根集成。

- P47/P48五类共享审计根：`shared-audit/s4-runs/shared-five-domain-guards-fixture-aligned/`，11单测+50真实IT零失败/错误/跳过，127.404秒。原5项真实越权红基线及中间旧价格夹具1失败均保留；旧价格测试已按实际领域查看权修正，未放松生产守卫。主控亲读全部XML、公司/仓库/客户/库存/价格拒绝JSON和物理字段投影、>2^53字符串ID；并核对冻结10文件与实际测试镜像SHA完全一致。实际两连接RR读者502/tx3075仅见公司54快照，写者505/tx3076提交公司55新Diff；隔离Outbox目标141成功一次，原139条待处理与sentinel140未动，数据库业务namespace仍single，HTTP500/慢SQL/任务日志trace一致且不泄露SQL。完整租户/公司会员ACL缺口仍见M01。

- 样衣新增候选审查：V59及Repository/Mapper/30项计划中的真实PG测试代码已审，当前仅27主类/8测试独立编译及12纯规则/实际MyBatis BoundSql检查通过，不能写成30项PG通过。主控在服务暂存代码发现“新颜色先写单据子行、后写主档颜色”会触发真实即时外键/守卫，并发现资料包授权读与正文/日志读取不在同一快照；已要求先修落库顺序、整体短RR读事务及真实双连接交错用例。样衣8表字段审计6文件已静态逐行审查，尚未编译/PG/HTTP/备份恢复验收，不计通过。

- 104f632完整云端终审：507单测＋290真实IT均零失败/错误/跳过，T11 61项、供应商/金额12项、23项真实生产进程检查、21真实UI集成和7会话检查通过；双JVM100请求实际仅1次SQL回源。主控下载Artifact14779570671，24,039,286字节/1293文件，SHA256 `c28725df27bb0ca6cdc02dc11411b5f5c2344d06013c433afa8584b8f7c9e81a`，亲读XML、生命周期的180000行XLSX排空与10个实际会话关闭/无关会话保留、16个401只刷新1次及全部21UI报告，并逐张打开7张改动相关图。125总图不混称全部重看；独立验证分支跳过原始PR基线捕获，既有PR运行的基线图仍单独保留。原始证据 `cloud-104f632/`。当前PR包含该提交；成品、P45、标签、样衣增量均不在此轮云端范围内。

### 成品身份范围补充复验

主控复核实际 `useUserStore.fetchMe()` 同值资料返回：修前17件未保存入库弹窗被关闭，修后数量与备注保留。`fgRead.ts` 仅在账号、会话epoch、主体、角色或权限集合实际变化时清理；实际自动401刷新修前已通过，显式store Token同步另标为组件边界注入。最终类型/Lint/生产构建、11项会话/24项成品/17项草稿均通过，原始红绿及主控亲看5图记录在 `test-artifacts/full-audit/fg/session-scope/`，均不提交Git。冻结源码聚合SHA `60d19747916a0dfac7e377cc7a62105342e5eb14b14cb579ba70ff202cdca30d`；该聚合基于根已集成P45/窄栏，成品发布树仅纳入此2文件增量，最终完整云端须按发布SHA再验。

### 样衣根收口与窄栏根集成

样衣领域首轮12规则单测、30真实PostgreSQL集成通过后，主控发现原批量编码 EXPLAIN 选择了 COUNT 包装而非顶层列表。P56 已用真实顶层列表与 COUNT 的绑定参数重做红绿：同5001行数据，两个 LATERAL 各由5001次降为2次，主头处理5002行降为3行；原始 JSON、文本计划和全部边界报告均亲读。此证据不替代整套验收。

根候选整合了样衣、标签、共享审计、公司快照和历史迁移兼容。`sample-full-01` 实跑534单测，其中1项提示文案门禁失败，IT未启动。11个 DTO 仅补字段提示、保留原约束后，`sample-full-02` 实跑534单测全部通过，143个真实IT出现3失败、34错误，0跳过。34错误均为合成品牌缺失，尚未进入业务HTTP；两项迁移断言错误地读到其他测试创建的角色；另一项为P57真实恢复失败。标签15项及共享审计50项均通过，两组包含11个重复计数，143是去重后的实际总数，不能相加冒称154。已冻结专属品牌fixture和独立空库迁移验证器，未删旧断言、未过滤其他角色来制造通过；恢复修复另作真实单项红绿。两轮原始报告及失败证据均保留，私有PG/Redis/JVM均退出，临时私密文件已清理。

第三轮 `sample-full-03` 已实际完成：534单测全绿，143 IT有3失败、2错误、0跳过，耗时319.966秒。两处新测试错用了非sys表不存在的deleted列；品牌预置检查读到35条合法业务夹具；50,000资料包测试在转换前因错误预热入口未生成缓存epoch而失败；组合恢复仍报helper不存在。原始报告与报文保留，服务全部退出、私密临时文件清理。测试修复须保留原数量、并发、权限、完整字典和恢复护栏，不以过滤夹具或删除断言制造通过。

前端生命周期最终7文件增量：来源/引用读取恢复、旧范围等待上传清理、品类读取恢复与样衣主图说明。正确的缓存入口在未改生产index时真实复现品类GET取消后无第二次GET且下拉空；修后第二次GET与可选上装均验证。中间一次失败仅为错误定位器，原证据保留。最终同份源码 `620d1680` 通过type/lint/build及31项实际Vue组件检查（8首次/返回、6生命周期、3样衣资料包、6宽表、4评审限制、4评审动作），page/console/HTTP contract错误均为0；主控亲读所有实际结果、完整7文件diff、核验文件指纹并打开关键红绿图。首次3 GET原本各1次，不能计作已复现漏洞；这些组件使用严格合成HTTP，真实生产UI单独执行，不能代替后端143门禁。

P45精度和库存/盘点/生产窄侧栏已并入根：类型检查、Lint、生产构建、24单测、19公共检查及9项实际组件检查均通过，主控逐张打开12张最终截图。六个布局窗口实测无横向页面溢出，日志时间/操作者/动作/状态/长备注均可见；三个受控503均显示重试并恢复，pageerror及非预期console均为0。这一窄栏专项使用合成HTTP，不能替代此前独立的真实后端精度/生产UI证据。

### 成品完整云端放行与产物覆盖补正

`676cdac96b95e6cb66133081cc26cb2bf22a0b88` 在独立验证引用完成 [CI 45101531600](https://github.com/xiaoliangao/lingmao/actions/runs/45101531600)：512单测、331真实IT均零失败/错误/跳过；22集成（21真实UI＋1API权限专项）及7会话检查全绿。主控下载Artifact14787851201（32,863,166字节/1419文件，SHA256 `44d0c50a0bd3769d5f6777d9035fea55a95f203fe578cc358d428b327992c34c`），亲读原始XML、全部22报告、28份成品HTTP、10条库存流水、价格物理投影和DataLog old/new；逐张打开12张成品生产截图。两页签16个401仅1刷新且全部重放。保留真实20并发创建/审核证据；一般库存50并发为10成功40不足，不能与成品本地25/25混写。PR以expected-head保护由104f632推进到该SHA，基准dev未变，未合并。

本轮云端遗漏上传 `target/a1-fg/`，因此不能声称已读到该轮新成品EXPLAIN；既有本地2000行seek实际计划另存并已终审。后续CI按生产测试实际输出补齐a1-fg/a1-tags/a2-tags/a2-tags-review/a2-sample五个精确目录，并对这些目录和原有query-capacity JSON统一脱敏；不扩大到整个target。独立实际sanitizer 9项检查通过，最终云端仍须复验。该成品提交不包含P45、标签、样衣或P53，相关未通过项保持开放。

### 样衣第四轮与真实页面终审发现

`sample-full-04` 实际执行455.714秒：534单测全部通过，143IT为142通过、1错误、0跳过。唯一错误是50000真实资料包头转款的HTTP超时；Maven同时记录 `adoptSamplePacks` UPDATE执行30017ms后查询超时，列为P58。原约束、数量及25秒HTTP/30秒SQL门槛未放松。所有私有服务退出、清理无错误。

主控亲读同轮pg_dump/pg_restore：36样衣审计+2安全审计及8域表快照SHA256 `ccf62778179488ce01d29175b20d028580d8e76bb83dec6cd6405435ce10ae8e` 原/恢复一致，两个wrapper固定search_path、恢复连接空search_path下实际检查均通过；身份伪造SQLSTATE23514和append-only拒绝仍有效。旧repeatable两次重放仅在独立空库发生；共享库两个函数配置和CHECK定义哈希前后不变，独立库已移除。

真实页面第二轮 `sample/root-real-ui-02` 使用生产JAR `4b96a03b` 与已冻结前端，实际仅执行样衣第24项，16.802秒通过：3色×5码建样、CAS编辑/字段Diff、无成本权限物理投影、研发BOM、改版、通过、真实价格档案、转款15SKU及生产草稿/现价重算。第一轮测试错误取可空SKU参考价的失败原件保留，第二轮通过真实价格API录价后校验来源ID，不改生产定价规则。主控逐张打开第二轮7张页面图；但发现最终脱敏破坏deleted_token对象，evidence.json与raw-sample-http.json无法解析，不能据此签发完整证据验收。列为P59，保留失败产物并在修复后重新取证。

### 样衣第五轮冻结与证据修复

主控逐行审查并仅叠入4文件：转款XML、版本边界IT、请求快照夹具及2项纯Java回归。候选1744文件的清单SHA `44a968739952ce1eb5fc8a39d408cd65817b441988fa635733ee062dd3b99e54`，选择28个IT类、145个独立调用及全部单测；运行前保存第四轮源、清单与runner，未覆盖旧失败产物。第五轮实际346.870秒退出0，536单测、145真实IT零失败/错误/跳过；主控独立解析全部121单测类和28 IT类原始XML，175份JSON均可解析。50k实际移交、两个版本边界拒绝通过；首个501字请求code400、第二个500字请求code0，两个请求的精确JSON与独立快照一致。私有PG/Redis退出、清理无错误。该结果仅覆盖冻结的样衣/标签/共享审计/迁移组，不能代替全部仓库IT或云端门禁。P59独立22项主控复跑全绿（0失败/错误/跳过），Docker/PG取消专项未在本地重跑；随后生产UI03+Tags01重新生成全部8份有效JSON，未修补旧UI02原件。

### 样衣与公共标签真实生产页面第三轮

`sample/root-real-ui-03-tags-01` 使用第五轮生产JAR `8bf9bad4b7ff601716143e089ba03fbb1b11c2ae6471215863824817f5aa33c9`，1081个生产源与第五轮冻结镜像逐个hash一致；前端为既有已审样衣最终7文件版本。两项真实Vue/HTTP用例31.026秒通过，共49份HTTP（样衣31、标签18），console/page/external错误均为空，全部8份JSON有效。使用合成夹具和真实数据库/API，不是mock接口。主控亲读所有报文（重复样衣详情按完整基准逐字段差异核对），确认DataLog名称/成本old-new与PUT requestId一致、只读成本字段物理不存在、3色×5码生成15个不同SKU、研发价11.0000保留而生产草稿按真实价格档案23.4567及来源ID重算。标签真实版本冲突1002保留输入，归档关联保留，同名新ID重建，只读写403且当前version不变。

主控逐张打开14张PNG，发现P61两处视觉问题并返工；本轮业务与证据检查通过，整体视觉验收保持未通过。根复核记录见上述忽略目录的 `root-review.json`，旧UI02损坏JSON及本轮截图均原样保留。


### P45 完整云端终审与样衣标签第四轮真实页面

P45 提交 `64f4fcfcfd3b43a9d4d756927ceabf6ec24703e0` 完成 [CI 45107632888](https://github.com/xiaoliangao/lingmao/actions/runs/45107632888)：512单测、334真实IT零失败/错误/跳过。主控下载Artifact14789983685（34,783,448字节/1467条目，SHA256 `50bdde96ad6337461b263f2c38b58db2c4ed0576b0952b947edc5053e5037d09`），验证524份JSON、逐张打开18张改动相关图，并以Decimal重新求和2000行实际HTTP明细。金额`1999999999999979.99`、数量`199999999999997.999`精确字符串及未知NULL保留。成品真实50并发为25成功/25不足、5个数据库后端连接，反序50组多SKU全部成功且余额为零；本轮补传的真实EXPLAIN使用`idx_fg_log_company_seek`，执行0.586ms（仅该2000行夹具，不泛化千万行）。以expected-head保护把PR由676cdac推进至64f4fcf；dev未变。原始证据`cloud-64f4fcf/root-p45-review.json`。

样衣/标签整合前端保留已发布的成品会话修复和P45精度修复，主控重新执行类型、Lint、24业务单测、19核心检查及生产构建，全部通过。生产JAR仍为full05的`8bf9bad4b7ff601716143e089ba03fbb1b11c2ae6471215863824817f5aa33c9`。`sample/root-real-ui-04-tags-02` 真实服务实测31份样衣HTTP和18份标签HTTP，两个选定UI用例通过、8份JSON有效、零非预期console/page/external错误。主控逐张打开14张图，确认P61来源固定列/横滚条及中文历史完整换行；独立10张组件图另覆盖1440/1180与滚动可达性。样衣DataLog228的姓名、成本45.6789→67.8912、version0→1准确，requestId与真实PUT一致；低权限报文物理不含estCost。转款保留研发价11.0000，生产草稿取真实当前报价23.4567/来源4，生成15个独立SKU。标签真实1002保留输入，归档身份1保留历史，新建同名为身份5，只读403后状态未变。此次仅本地选定流程通过，不代替本候选完整云端；标签筛选、QC/预约、财务及其余未销项继续开放。根终审证据`sample/root-real-ui-04-tags-02/root-review.json` SHA256 `feaad14eda169cd55cf5449102f4ace760639a57ff6edd7b728f163e1bad6491`。
