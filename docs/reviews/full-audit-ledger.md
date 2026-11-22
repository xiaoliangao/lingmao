# 全量审查与修复总台账

本轮日期：2026-11-22。目标分支 `dev@7c757c83fbd794e051347cb8edff04d346294e75`，工作分支 `fix/full-audit-arch-prod-hardening`。本文件记录本轮观察和实际验收；历史报告中的“通过”不自动成为本轮结果。

## 1. Ground Truth 与证据边界

1. `README.md`、`CLAUDE.md`、PRD、架构、数据库设计、RFC 001–010、API 01–06、原型及源代码构成可访问基准。仓库没有 `AGENTS.md`。本轮用户的 dev/PR/目录隔离要求覆盖旧文档的直接提交 main 约定。
2. 两份原始业务表未随任务提供，公开仓库将它们排除在 `docs/private/`；文件检索两次均未命中。PRD 引述的 160 页/2985 列不能当成已核对原件。本轮不能签发原系统全页、全字段复现证明。
3. 主代理先核对 dev 的需求分期、数据模型、迁移、核心事务/查询、安全/文件和前端公共实现，建立问题和接口边界；全文件静态清单与阶段人工代码审查分开记录，不能用扫描文件数冒充逐行人工审查完成。
4. 发现 `main` 比 dev 多 105 个提交，四阶段加固分支仍在。`perf/a4-observability-lifecycle@02f69ba` 的树与 main 上已整合的 `892afa7` 相同，且均承接同一 dev 基线。本轮复用这组已有实现及整改，保留原提交，不导入 main 后续助手等不相关开发。原始四阶段 SHA：A1 `1aebc97`、A3 `2f69ccd`、A2 `dac2a7a`、A4 `b574e5f`；后续已审整改 `0e9c885`、文档同步 `02f69ba`。
5. 当前用户要求的财务收付/月结、完整样衣/质检预约和公共标签编辑，超出现有 PRD 首期实现；这属于尚未完成的业务验收要求。既不把缺失写成通过，也不凭缺失原件擅造商业规则。先补齐已明确的一期首页/评审和公共底座。

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
| M-04 | P1 | `docs/进度.md:195` 仍写迁移“在途需求订单”，与 RFC010 方案 A 冲突 | 修正当前 PLAN/切换拓扑；不回改历史记录 | 通过 | 本轮 S1 提交（见阶段记录） |
| M-05 | P1 | 历史 review-a1～a4 引用本地不可解析 SHA、旧“尚未创建 PR”及过时验证结论 | 保留历史证据出处并显著注明不可替代本轮；本表只用实际可解析提交 | 未通过 | 待主控/S4 |

## 4. 生产风险与复验台账

| ID | 级别 | dev 证据/继承实现 | 必须观察的真实结果 | 本轮状态 | 修复提交 |
|---|---|---|---|---|---|
| P-01 | P1 | `common/page/Keywords.java:25`；继承整改仍 MAX_CODES=200，与本轮≤100冲突 | 100 接受/101 拒绝；混合分隔、精确命中、真实索引 | 未通过 | 待 S1/S3 |
| P-02 | P0 | dev 仅两类 clientActionId；继承 HttpIdempotencyAspect | 20 并发同键仅 1 次落库，回放重新授权、换体/主体拒绝 | 通过（本轮 S1 真实库专项） | 1aebc97/dac2a7a |
| P-03 | P0 | SerialNoGenerator、StatusCas、StockLedger | 20 发号、双 CAS、50 抢库存、反序多 SKU 同时事务 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-04 | P0 | `Num`、采购累计差额与 Decimal、IdJsonResponseAdvice | 0.01 尾差、负数拒绝、伪造金额拒绝/重算、>2^53 ID 字符串 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-05 | P1 | dev 软删部分唯一索引、已引用主档 RESTRICT | 删后同编码重建与历史引用拒删 | 通过（本轮 S1 真实库专项） | dev/A1 |
| P-06 | P1 | dev StockLog OFFSET、无查询时限；继承 seek、超时和复合索引 | EXPLAIN 实际访问量、无隐式 COUNT、所有池连接时限 | 通过（本轮 S1 真实库专项） | 1aebc97 |
| P-07 | P1 | dev DictCache 固定 TTL、无单飞；继承命名空间/栅栏 | 100 同起仅一次回源、回滚/慢填充/跨命名空间隔离 | 未通过（待本轮执行） | dac2a7a |
| P-08 | P1 | dev 无 Outbox；继承持久租约状态机 | 超时接管、旧持有者拒 ack、退避/DLQ/手动重推 | 未通过（待本轮执行） | dac2a7a |
| P-09 | P1 | dev ExcelWriter:190 byte[]、全量行 List；继承 seek 流/导出任务 | 真实 TCP 断连停下一页；限额、清理、中文文件名 | 未通过（待本轮执行） | dac2a7a |
| P-10 | P0 | dev ExcelReader 无公式阻断、FileServiceImpl:65 只验扩展名 | =CMD 精确行列拒绝、魔数/ZIP 限制、整批零写 | 未通过（待本轮执行） | dac2a7a |
| P-11 | P0 | 继承 FieldPolicy/DTO/Batch 范围复查 | 无权限 JSON 物理无价格/银行、Batch 混入不可见 ID 拒绝 | 未通过（待本轮执行） | dac2a7a |
| P-12 | P0 | dev 每次请求现查权限；继承 session_version/轮换 | 停用/降权即时失效，固定家族寿命、同站 Cookie 校验 | 未通过（待本轮执行） | dac2a7a |
| P-13 | P1 | dev OperLogAspect 直接信 XFF；继承 TrustedProxyResolver | 伪造 XFF 不能绕限流；可信代理/非默认端口同源正确 | 未通过（待本轮执行） | dac2a7a |
| P-14 | P1 | dev request.ts 401直接登出、useTable 只丢弃晚响应 | 8/16并发单刷新、真 Abort、无键写请求不重放 | 未通过（待本轮执行） | 2f69ccd/dac2a7a |
| P-15 | P1 | 继承 IME/广播/固定主体/日期/草稿/局部边界 | 拼音 Enter、跨页签换主体、3时区、故障恢复真实浏览器 | 未通过（待本轮执行） | 2f69ccd |
| P-16 | P1 | 继承 VirtualTable/SkuMatrixCell/缩略图/存储容错 | ≥30列及多色码真实渲染，固定列截图和输入局部更新 | 未通过（待本轮执行） | 2f69ccd |
| P-17 | P0 | dev X-Trace-Id、明文参数日志；继承 SafeLog/ObservedDataSource | HTTP→SQL→Outbox 同 ID、敏感哨兵不漏、500无栈/SQL | 未通过（待本轮执行） | b574e5f |
| P-18 | P1 | 继承 28 表 sys_data_log old/new；用户/角色关系无字段审计 | 真改价 diff、回滚不留事实、只增保护；权限管理缺口单列 | 未通过（含权限管理审计缺口） | b574e5f |
| P-19 | P1 | 继承 live/ready、真实借连接探针与排空 | 真断库、SIGTERM在途事务/导出、租约恢复；不以Mock代替 | 未通过（待本轮执行） | b574e5f |
| P-20 | P0 | 继承 ProductionEnvironmentGuard、产物隔离门禁 | 实际生产 JAR 弱秘钥拒启、无测试入口/Mock；一命令启动保留 | 未通过（待本轮执行） | b574e5f |
| P-21 | P1 | 已有 `.github/workflows/ci.yml` 综合门禁 | 干净 ubuntu runner 实际结论与 Artifact，不能引用旧本地通过 | 云端无法验证（尚未提交本轮） | b574e5f，待 S4 |
| P-22 | P0 | 当前候选 `web/src/utils/request.ts`：等待旧写入回执后没有二次共享 Promise 检查；核对回执跨越会话切换后仍会改旧收据 | 真 Chromium 复现同体双 POST/双键，以及换账号后旧查询/误删；修复后必须只 1 POST/1 键，保留原身份回执 | 未通过（定向修复已报，待主控复验） | 待 S3 |
| P-23 | P1 | 当前候选 `web/src/utils/orderSku.ts:30`：`Math.round(price * 10000)` 仍先经过二进制浮点乘法，金额合计又转 Number 累加；`TechPackTab.vue:680,691,747,774` 存在 BOM/工艺/差额浮点预览 | 统一十进制定点边界、整数分逐行汇总及显示；覆盖 `1.00185` 舍入反例、大额合计及 `0.018 × 7.5 = 0.14` BOM 成本预览 | 未通过（待 S3 复验） | 待 S3 |
| P-24 | P0 | `V36__append_only_field_audit.sql:53–65` 只保护审计表；`inv_stock_log` 缺 DB 不可变守卫，`InventoryIT:325`、`A1PurchasePrecisionIT:143` 可直接改原事件 | 追加独立 repeatable 迁移，拒 UPDATE/DELETE/TRUNCATE；真实反审只追加；历史夹具改 INSERT，清理限定迁移所有者/测试事务，不能在生产留下绕过开关 | 通过（3 项守卫/冲销/迁移专项；最终全 profile 待跑） | 本轮 S1 提交（见阶段记录） |
| P-25 | P1 | 当前 `TechPackTab.vue:888,1081` 的尺寸指示和按颜色 BOM 随尺码动态扩列，≥30列时仍普通表；55列容量 fixture 不能代表页面已接入 | 宽表分支复用虚拟表，保留编辑/排序/删除/悬浮；真实 TechPackTab 大尺码夹具截图和交互复验 | 未通过（已打回 S3） | 待 S3 |

| P-26 | P0 | 当前 `request.ts` 对全部 `/api/auth/` 免会话检查，`stores/user.ts:27` 在 await 后直接写 info/subject | 主控提出慢 `/auth/me` 跨账号风险，真实 request+Pinia 已复现：晚到 200 改回旧用户/权限/subject，晚到业务/HTTP 401 清除新 token；收窄免检、先验 epoch 再触发失败副作用，补 3 项定向回归 | 未通过（已打回 S3） | 待 S3 |

## 5. 本轮执行记录

- 环境准备：前端锁定依赖已安装；PostgreSQL16.16、Redis8.0.2 和 Chromium headless-shell 已实际启动验证。当前 exec 的代理端口动态变化，运行 wrapper 会即时生成 Maven settings；prod-readiness 依赖已解析。首次编译发现仅 Java21 运行时；现已补齐完整 JDK21 并验证 `javac --release 21`。无 Docker；本地 Redis8 不能冒充 CI 的 Redis7 验收，真实引擎门禁不降级到 SQLite 或 Mock。
- 前端先观察到 P-22 三项故障均失败，再报告修复后的 3/3 通过；匿名前后证据在忽略目录 `test-artifacts/full-audit/s3/`，尚待主控复验，不能替代全套浏览器验收。
- 主控读取了本轮实际并发 JSON：20 个唯一连续单号；20 同键请求仅 1 个 200、19 个处理中 409，回放/落库断言通过；50 抢 10 件库存得到 10 个成功、40 个库存不足。已亲见 API 的 `id="9007199254740993"`、数字 `version=0`，以及 seek 计划使用 `idx_inv_log_seek`、实际读取 101 行。主控还读取了 150 份实际查询计划汇总及 Unicode 精确查询原始计划：22 张每表 10 万行级夹具下，测试覆盖的查询未扫描大表，12 份 Unicode 混合分隔计划精确命中 2 行并使用编码索引；这不覆盖所有任意短关键字，也不是生产 SLA。完整测试收尾和新库存不可变守卫仍在验收中。
- 主控已逐张打开当前前端宽表中/末段、矩阵输入、局部错误边界及 4 张权限表单截图；这些是生产组件 + 标注故障注入证据，不能当成真实后端全链路页面验收。真实基线已使用独立 dev 构建与新建匿名测试库成功运行；主控打开订单列表发现截图停留加载态，已退回补拍稳定结果。重新捕获的 7 页均确认真实 API 成功、可见加载/骨架和 pending API 为零，console/pageerror/request failure 为零；主控已逐张复看全部 7 张稳定基线图。基线逐 SKU 保存值相同，不能称作本轮复现的损坏。稳定截图脚本纳入 S4 CI整改。
- S1 主控终审：亲自运行 `-Pprod-readiness -Dtest=KeywordsTest,S1KeywordContractTest -Dit.test=S1InventoryLedgerIT,InventoryIT,A1DatabaseProductionIT,A1HttpCreationIT,A1PurchasePrecisionIT verify`，exit 0，7 单测 + 38 IT 零失败/错误/跳过，耗时 74 秒。亲见流水原事件 `qty=2, amount=6` 与只追加的冲销 `qty=-2, amount=-6, reversal_of_log_id=原事件`，净额/净量为零。证据 `test-artifacts/full-audit/s1/root-guard-proof/results.json`；新守卫下的大规模清理路径仍留最终完整 profile。首轮 7 单测 + 36 IT（150 份 EXPLAIN）证据单独归档为 `baseline-proof`，不得混称包含新守卫。
- 基准表：未取得。原系统访问只允许只读；未使用账号、凭据或真实商业数据。
- 本轮尚未报告任何全量测试通过，也未创建本轮 PR。所有新增进展须记录具体命令、退出码、结果数量、证据位置和实际 SHA。

## 6. 放行判定

存在未通过 P0/P1 或业务基准缺口时，PR 保持 Draft，并在正文保留全部未通过项。代码可以提交供审阅，不等于允许生产上线。运行截图必须由主控逐张打开；历史截图或历史报告不能代替当前页面。不得声称“100% 完成”“隐患清零”或远端 CI 全绿，除非相应范围有本轮真实证据。
