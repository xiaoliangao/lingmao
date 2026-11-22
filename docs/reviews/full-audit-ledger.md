# 全量审查与修复总台账

本轮日期：2026-11-22。目标分支 `dev@7c757c83fbd794e051347cb8edff04d346294e75`，工作分支 `fix/full-audit-arch-prod-hardening`。本文件记录本轮观察和实际验收；历史报告中的“通过”不自动成为本轮结果。

## 1. Ground Truth 与证据边界

1. `README.md`、`CLAUDE.md`、PRD、架构、数据库设计、RFC 001–010、API 01–06、原型及源代码构成可访问基准。仓库没有 `AGENTS.md`。本轮用户的 dev/PR/目录隔离要求覆盖旧文档的直接提交 main 约定。
2. 两份原始业务表未随任务提供，公开仓库将它们排除在 `docs/private/`；文件检索两次均未命中。PRD 引述的 160 页/2985 列不能当成已核对原件。本轮不能签发原系统全页、全字段复现证明。
3. 主代理先核对 dev 的需求分期、数据模型、迁移、核心事务/查询、安全/文件和前端公共实现，建立问题和接口边界；全文件静态清单与阶段人工代码审查分开记录，不能用扫描文件数冒充逐行人工审查完成。
4. 发现 `main` 比 dev 多 105 个提交，四阶段加固分支仍在。`perf/a4-observability-lifecycle@02f69ba` 的树与 main 上已整合的 `892afa7` 相同，且均承接同一 dev 基线。本轮复用这组已有实现及整改，保留原提交，不导入 main 后续助手等不相关开发。原始四阶段 SHA：A1 `1aebc97`、A3 `2f69ccd`、A2 `dac2a7a`、A4 `b574e5f`；后续已审整改 `0e9c885`、文档同步 `02f69ba`。
5. 当前用户要求的财务收付/月结、完整样衣/质检预约和公共标签编辑，超出现有 PRD 首期实现；这属于尚未完成的业务验收要求。既不把缺失写成通过，也不凭缺失原件擅造商业规则。先补齐已明确的一期首页/评审和公共底座。

### 1.1 完整业务链覆盖与明确断点

下表是源码与迁移的覆盖判断，不代替第 3–5 节的真实运行验收。Java 路径均相对 `src/main/java/com/lingmao/scm/module/`；版本化 SQL 均在 `src/main/resources/db/migration/`。

| 链路 | 已有真实入口/模型 | 必须保留的断点 |
|---|---|---|
| 主数据、供应商、价格 | `basic/controller/*`、`supplier/controller/SupplierController.java:56`、`price/controller/PriceController.java:35`；V10/V12/V13/V15 | 原始字段清单未取得；`bas_customer` 有表但没有完整客户业务 Controller，TRADE 是受控预留。 |
| SPU / 色码 SKU | `style/controller/StyleController.java:181,225,232`、`style/service/impl/SkuGenerator.java`；V14 的款式、款色和唯一色码 SKU | 当前树仍需真实矩阵往返与全链路验收；不能用有表证明全部原件字段一致。 |
| 样衣 | V14 的 `sample_no`、DEV 资料包 | 无样衣申请、打版、寄样、审批、转大货的完整单据域。 |
| 标准资料包 / BOM | `techpack/controller/TechPackController.java:72–116`、V20 的版本/尺寸/BOM/工艺/成本子表 | DEV/PROD 生效和修订存在；仅可对实测路径验收。 |
| 订单生产资料包 | `order/service/impl/OrderServiceImpl.java:874,904`、`production/service/impl/ScheduleServiceImpl.java:224,252`；V22/V25 的 `tech_pack_id` | 当前实现是冻结标准生产 BOM 版本引用；没有独立 ORDER 级可编辑 BOM 快照表。`mrp_calc.input_snapshot` 不能替代它。 |
| 订单、拆分排产 | `OrderController`、`OrderChangeController`、`ScheduleServiceImpl`；V22/V25 主子表 | 可按款/工厂/件数拆分排产并回写已排量；没有把一张需求订单拆成多张新需求单的独立动作和父子谱系。 |
| FOB / CMT | `production/controller/ProductionOrderController.java:77`；V25；`ProductionKind` 的 BUY / OUTSOURCE | 共用路由、两种视图和真实排单/审核/节点已存在；成品库存与财务结算尚未接通。 |
| 算料、物料采购、分批收退 | `MrpCalcController`、`PurchaseOrderController`、`InvDocController`；V21/V23；`PurchasePostingPrecision` | 已有双单位和累计尾差平账，不能据此声称含税反算、税额分摊和财务核销完成。 |
| 物料库存、齐料 | `StockController`、`CountController`、`InvDocController`、`SupplyController`；V21/V26、库存不可变 repeatable | 余额与流水分离；`inv_stock` 只有实物 qty / movement_revision。可配、占用、在途由 MRP/采购派生，没有完整四维余额和统一预留域。 |
| 质检、预约、成品仓、报次 | `ProductionReceiptServiceImpl.java:150,256`；V25:141,170 的回货/次品数量/照片 | 无完整 QC 或预约单；回货仓只作记录，不过成品库存。97%/103% 规则仅适用既有生产回货，不等于所有短溢装结案。 |
| 账单、对账、收付款、核销、月结 | 只有 `settle_method`、账期、税率/含税标记、银行字段和定点工具 | 无完整财务域、会计日期、关账期间硬锁、独立财审/结算状态或财务红冲凭证。MONTHLY 字典与库存估值状态不能代替月结。 |
| 公共标签 | `legacyAttrs` 只读附加属性；RFC010:81 | 无公共标签表、关联表、自动入库/编辑/筛选闭环；风格字典 `style_tag` 与吊牌价 `tag_price` 均不是标签库。 |
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

数据库迁移只追加，不改已经在 main 发布的 V1–V36。若新增迁移须先由主控核对 main 的现有版本号与未来合并风险。运行日志、EXPLAIN、临时文件和截图只放忽略目录/CI Artifact。

## 3. 功能与模型台账

行号以 dev 基线为准，后续新增位置另注。状态只用“通过 / 未通过 / 云端无法验证”；“未通过（待本轮执行）”不等于断言实现必然有错。

| ID | 级别 | 证据位置与问题 | 修复/验收方案 | 本轮状态 | 修复提交 |
|---|---|---|---|---|---|
| F-01 | P1 | `web/src/mock/home.ts:36`、`web/src/mock/board.ts:5`；`docs/进度.md:73,188`：首页/评审只有前端契约，正式接口缺失 | 实现真实查询、权限交集及错误/空态；删除相应 mock；真实主线 UI | 未通过 | 待 S2/S3 |
| F-02 | P1 | `docs/rfc/010-审查整改契约.md:81`：公共标签仅附加属性只读，缺标签编辑、关联与筛选闭环 | 必须取得字段/范围依据再定义唯一性与关联；现阶段不得宣称已实现 | 未通过（基准缺失） | — |
| F-03 | P0 | `docs/PRD.md:72–79,1923,2112`：样衣、质检、成品仓、财务存在分期差异；无收付款/月结实现 | 本轮明确未闭环，不能用库存期间或金额工具替代财务主线验收 | 未通过（范围与业务依据缺口） | — |
| F-04 | P1 | `module/production`、API05：FOB/CMT 共路由、排单、回货已有实现 | 真实数据库走订单→采购/外发→分批收退→回货与逆向，不以源码存在判通过 | 未通过（待本轮执行） | 继承 A1/A2 |
| F-05 | P1 | `TechPackTab.vue`、原型 `3-ui.js/5a-home-style.js`：BOM 浮窗/图像/权限矩阵 | 真 API 页面逐张看截图；保留视觉、加载/空/失败态 | 未通过（待本轮执行） | 继承 A3 |
| M-01 | P0 | `security/DataScope.java:43–142`、`common/entity/DocEntity.java:43`：原模型为共享公司数据，不是共享库多租户 ACL | 保留拒绝未支持部署模式的硬守卫；明确全域 tenant/company ACL 尚未完成 | 未通过（完整租户隔离要求） | 继承 A2 的部署守卫 |
| M-02 | P1 | V14/V20/V22/V25：已有 SPU/SKU、Header-Items、冻结 BOM 版本 | 验证真实色码/旧订单锁版及旧字段往返；不重复建宽表或假可编辑订单 BOM | 未通过（待本轮执行） | dev 已有，继承 A1 |
| M-03 | P1 | V21/V26：库存余额与流水已分离；财审/结算域不存在 | 验证 append-only、反向记录及状态 CAS；未实现状态维度保持缺口 | 未通过（待本轮执行） | 继承 A1/A4 |
| M-04 | P1 | `docs/进度.md:195` 仍写迁移“在途需求订单”，与 RFC010 方案 A 冲突 | 修正当前 PLAN/切换拓扑；不回改历史记录 | 通过 | 132ffaf |
| M-05 | P1 | 历史 review-a1～a4 引用本地不可解析 SHA、旧“尚未创建 PR”及过时验证结论 | 保留历史证据出处并显著注明不可替代本轮；本表只用实际可解析提交 | 通过（历史文档已标注不可替代本轮） | 待 S4 |

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
| P-08 | P1 | dev 无 Outbox；继承持久租约状态机 | 超时接管、旧持有者拒 ack、退避/DLQ/手动重推 | 通过（真实DB租约、DLQ及重启接管） | dac2a7a |
| P-09 | P1 | dev ExcelWriter:190 byte[]、全量行 List；继承 seek 流/导出任务 | 真实 TCP 断连停下一页；限额、清理、中文文件名 | 未通过（库存流水已实测；全部旧导出要求见P37） | dac2a7a |
| P-10 | P0 | dev ExcelReader 无公式阻断、FileServiceImpl:65 只验扩展名 | =CMD 精确行列拒绝、魔数/ZIP 限制、整批零写 | 通过（真实导入/文件边界专项） | dac2a7a |
| P-11 | P0 | 继承 FieldPolicy/DTO/Batch 范围复查 | 无权限 JSON 物理无价格/银行、Batch 混入不可见 ID 拒绝 | 通过（当前角色/数据范围；共享库多租户缺口仍见M01） | dac2a7a / 8d2e04f |
| P-12 | P0 | dev 每次请求现查权限；继承 session_version/轮换 | 停用/降权即时失效，固定家族寿命、同站 Cookie 校验 | 通过（真实HTTP与生产双实例会话） | dac2a7a / 8d2e04f |
| P-13 | P1 | dev OperLogAspect 直接信 XFF；继承 TrustedProxyResolver | 伪造 XFF 不能绕限流；可信代理/非默认端口同源正确 | 通过（真实HTTP、Nginx及代理边界故障） | dac2a7a |
| P-14 | P1 | dev request.ts 401直接登出、useTable 只丢弃晚响应 | 8/16并发单刷新、真 Abort、无键写请求不重放 | 通过（真实后端16请求单刷新；当前前端105项门禁） | 2f69ccd / dac2a7a / 1d78ebf |
| P-15 | P1 | 继承 IME/广播/固定主体/日期/草稿/局部边界 | 拼音 Enter、跨页签换主体、3时区、故障恢复真实浏览器 | 通过（浏览器IME/广播/三时区与故障回归） | 2f69ccd / 1d78ebf |
| P-16 | P1 | 继承 VirtualTable/SkuMatrixCell/缩略图/存储容错 | ≥30列及多色码真实渲染，固定列截图和输入局部更新 | 通过（实际TechPack宽表和矩阵；主控已逐图检查） | 2f69ccd / 1d78ebf |
| P-17 | P0 | dev X-Trace-Id、明文参数日志；继承 SafeLog/ObservedDataSource | HTTP→SQL→Outbox 同 ID、敏感哨兵不漏、500无栈/SQL | 通过（应用HTTP/SQL/Outbox追踪与脱敏；CI原生日志另见P36） | b574e5f |
| P-18 | P1 | 继承 28 表 sys_data_log old/new；用户/角色关系无字段审计 | 真改价 diff、回滚不留事实、只增保护；权限管理缺口单列 | 通过（业务Diff及新增权限审计5项真实HTTP/DB） | b574e5f；新增待 S4 |
| P-19 | P1 | 继承 live/ready、真实借连接探针与排空 | 真断库、SIGTERM在途事务/导出、租约恢复；不以Mock代替 | 通过（本轮S2云端真实进程、DB、导出排空和恢复） | b574e5f |
| P-20 | P0 | 继承 ProductionEnvironmentGuard、产物隔离门禁 | 实际生产 JAR 弱秘钥拒启、无测试入口/Mock；一命令启动保留 | 通过（生产JAR产物/启动守卫；增强凭据另见P27） | b574e5f |
| P-21 | P1 | 已有 `.github/workflows/ci.yml` 综合门禁 | 干净 ubuntu runner 实际结论与 Artifact，不能引用旧本地通过 | 未通过（S1/S2云端已绿；最终S3/S4门禁待完成） | 90ba037 / 8d2e04f；最终待 S4 |
| P-22 | P0 | 当前候选 `web/src/utils/request.ts`：等待旧写入回执后没有二次共享 Promise 检查；核对回执跨越会话切换后仍会改旧收据 | 真 Chromium 复现同体双 POST/双键，以及换账号后旧查询/误删；修复后必须只 1 POST/1 键，保留原身份回执 | 通过（修前真实失败、修后1POST/1键及旧身份回执保留） | 1d78ebf |
| P-23 | P1 | 当前候选 `web/src/utils/orderSku.ts:30`：`Math.round(price * 10000)` 仍先经过二进制浮点乘法，金额合计又转 Number 累加；`TechPackTab.vue:680,691,747,774` 存在 BOM/工艺/差额浮点预览 | 统一十进制定点边界、整数分逐行汇总及显示；覆盖 `1.00185` 舍入反例、大额合计及 `0.018 × 7.5 = 0.14` BOM 成本预览 | 通过（十进制定点反例、逐行汇总与真实BOM预览） | 1d78ebf |
| P-24 | P0 | `V36__append_only_field_audit.sql:53–65` 只保护审计表；`inv_stock_log` 缺 DB 不可变守卫，`InventoryIT:325`、`A1PurchasePrecisionIT:143` 可直接改原事件 | 追加独立 repeatable 迁移，拒 UPDATE/DELETE/TRUNCATE；真实反审只追加；历史夹具改 INSERT，清理限定迁移所有者/测试事务，不能在生产留下绕过开关 | 通过（3项本地专项；首轮CI的流水、大夹具清理均通过，整体另有搜索测试错误） | 132ffaf |
| P-25 | P1 | 当前 `TechPackTab.vue:888,1081` 的尺寸指示和按颜色 BOM 随尺码动态扩列，≥30列时仍普通表；55列容量 fixture 不能代表页面已接入 | 宽表分支复用虚拟表，保留编辑/排序/删除/悬浮；真实 TechPackTab 大尺码夹具截图和交互复验 | 通过（实际TechPackTab宽表编辑/排序/浮窗与截图） | 1d78ebf |
| P-26 | P0 | 当前 `request.ts` 对全部 `/api/auth/` 免会话检查，`stores/user.ts:27` 在 await 后直接写 info/subject | 主控提出慢 `/auth/me` 跨账号风险，真实 request+Pinia 已复现：晚到 200 改回旧用户/权限/subject，晚到业务/HTTP 401 清除新 token；收窄免检、先验 epoch 再触发失败副作用，补 3 项定向回归 | 通过（真实request与Pinia慢200/401跨身份回归） | 1d78ebf |
| P-27 | P1 | `ProductionEnvironmentGuard.java:25` 对数据库/Redis 密码仅 require 非空；`A4EnvironmentTest.java:38–39` 用弱口令作为有效配置 | 增强生产凭据校验并同步匿名测试；保留真实 JAR 启动 Fail-Fast 证据，错误不得回显凭据值 | 通过（本地主控全量单测及4项实际生产JAR拒启） | 待 S4 |
| P-28 | P1 | 待复用的 `27991d7` HomeService/UpstreamRows 先截断后排序、全量读通知，不能原样导入 | SQL 先授权/排序后 LIMIT；上游默认50/max100游标页、独立total、每单最多100提醒并返回noticesTotal，前端显式翻页及处理余量 | 通过（真实首页与分组游标HTTP；容量风险仍见P33） | 8d2e04f / 1d78ebf |
| P-29 | P1 | `ApprovalServiceImpl.java:146` 的无候选 `waitingForMe` 读全部待审 ID；Order/Purchase/Production 列表据此拼大 IN，首级判断仅 `perm IS NOT NULL` | 三域列表/COUNT/合计使用可信会话构造的 CURRENT 审批 EXISTS，权限码精确匹配；动作补齐只查当前页 ID；1025 待审和实际 EXPLAIN，旧请求注入审批范围无效 | 通过（1025待审/精确权限/真实SQL；夹具顺序修正另记） | 8d2e04f；夹具清理待 S4 |
| P-30 | P0 | 认证 HTTP 响应的 Set-Cookie 不受前端 epoch 控制：旧退出/刷新/改密晚到可覆盖新登录 Cookie；单飞刷新锁未覆盖认证切换 | 同源 Web Locks 串行认证响应与会话发布；无 Web Locks 时协商 access-only，服务器不读写刷新 Cookie，旧身份任务发出前检查 epoch；真实浏览器 Cookie/HTTP 七项回归及真实后端模式测试 | 云端无法验证（真实Cookie与access-only定向通过；实际非安全HTTP源待CI） | 8d2e04f / 1d78ebf |
| P-31 | P0 风险核查 | 本轮认证锁候选 `web/src/api/auth.ts:16` 持锁后执行改密，需排除 401 刷新二次申请同名锁 | 主控亲读真实浏览器结果：业务/HTTP 401 分别 21/11ms 结束，均 0 刷新、0 残锁；现有非幂等写保护已阻断嵌套刷新，无需增加代码分支 | 通过（排除假设风险，定向回归入库） | 1d78ebf |
| P-32 | P1 | 本轮 `PurchaseOrderServiceImpl.java:1057` 接受空白 tab，但 SQL 返回空列表；`ProductionQuery.cutRiskFirst` 可 GET 绑定，列表与 counts/export 使用不同筛选 | 页签统一归一化；真正拒绝或隔离内部查询参数，不能只依赖 JSON 注解；实测 HTTP 参数与列表/计数一致 | 通过（内部GET参数不参与绑定，注入不改变结果；列表/计数一致） | 8d2e04f |
| P-33 | P1 | `mapper/biz/DocNoticeMapper.xml:27–57` 在全量可见未处理通知 GROUP BY/MAX 后 seek，每页仍聚合完整集合 | 已测六种分布、19查询点、171份真实EXPLAIN；主控完整profile重测：计数后移在10万单组从81.599ms退化到201.343ms，LATERAL在10万空单头从50.234ms退化到192.205ms。保留原SQL，完整聚合的O(pending)容量边界与演进阈值写入RFC | 未通过（实际规模边界已确认） | 待 S2/S4 |
| P-34 | P1 | `PurchaseOrderServiceImpl.java:1064` 当前页来源仍读全部关联算料使用；`SupplyServiceImpl.java:53` 原补货列表仍全量 SKU/SupplyBook，单SKU草稿也无界 | 补货正式改PageResult（默认50/max100），SQL完整计算后分页；来源/草稿SQL预览20条加真实总数，前后端同步并验证真实分页与业务数值 | 通过（真实235SKU/125来源及9项分页浏览器） | 8d2e04f / 1d78ebf |
| P-35 | P0 | `SupplyController.java:28`、`MrpServiceImpl.kitting`：登录即可批量读取任意orderIds的齐料与calcIds，且逐张算料selectByCalc形成N+1 | 混入不存在/不可见ID整批404；订单/生产列表与看板分别授权；看板仅APPROVED/RUNNING普通数据范围，无审批例外；一次批量预加载，真实HTTP混批和SQL次数验收 | 通过（独立看板授权、混批拒绝/1次SQL及6项浏览器失败恢复） | 8d2e04f / 1d78ebf |
| P-36 | P1 | `tests/prod-readiness/a4-lifecycle/ci_two_instances.py:141–184` 对整个 PostgreSQL 服务启用 SQL 日志并通过 docker stdout 采集；删除本地副本后 Actions 停容器仍输出原始 SQL | 私有文件采集、测试库级配置、finally 恢复与清理；匿名 SQL 哨兵在私有日志可见且容器日志不可见，保留实际回源次数核对 | 未通过（从全绿 CI 日志中发现，S4 修复中；未发现真实商业数据泄露证据） | 待 S4 |
| P-37 | P1 | `MaterialImport.java:183–190`、`StyleExcel.java:157–164`：旧多表导出只限制主档 50000 条，色码子行先完整加载再检查 Excel 限额；`ExcelWriter.java:102` 最终仍构造 byte[] | 对主子表实际导出行数实施查询前/查询中边界，并按真实内存预算验证；库存流水的 seek/异步/断连证明不可外推到所有旧导出 | 未通过（子表预算与50000/50001边界已过；全链路流式、峰值/并发/断连仍无证明） | 待 S4 |

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
- 尚未创建本轮PR，不宣称全量完成。所有新增进展须记录具体命令、退出码、结果数量、证据位置和实际SHA。

## 6. 放行判定

存在未通过 P0/P1 或业务基准缺口时，PR 保持 Draft，并在正文保留全部未通过项。代码可以提交供审阅，不等于允许生产上线。运行截图必须由主控逐张打开；历史截图或历史报告不能代替当前页面。不得声称“100% 完成”“隐患清零”或远端 CI 全绿，除非相应范围有本轮真实证据。
