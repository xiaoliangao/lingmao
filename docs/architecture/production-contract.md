# 生产加固公共契约

基线：`7c757c83fbd794e051347cb8edff04d346294e75`（2026-11-17）。本轮任务授权四阶段实施，覆盖仓库旧的直接提交 main / 不开子代理约定；集成分支为 dev，绝不向 main 提交。本文由主控冻结，阶段代理不得各自改变公共契约。

## 1. 依据和验收边界

- 需求依据：原 PRD 变更记录 v0.19、架构设计、数据库设计、RFC 001–010、一期 API、原型；本轮公共契约已同步到 PRD v0.20。
- 技术栈是 Java 21 / Spring MVC / PostgreSQL 16 / Vue 3；不能把 Node.js 异常、事件循环或 React ErrorBoundary 的名称当作实现证据。
- 公开仓库没有 AGENTS.md、CLAUDE.local.md、docs/private/ 原件。《领猫SCM模块字段清单》原件不可见，160 页 / 2985 列仅为 PRD 引述，不冒充已逐页验过原系统。
- 已实现域是主数据、供应商、款式、资料包、需求订单、算料采购、物料库存、生产；收付款、财务月结、外部协同、完整标签库等尚未实现。逐号报告须区分现有生产路径、新增已接入防护、未实现业务与未能实测，不以孤立工具类冒充业务闭环。
- 保留模块化单体与现有本地启动方式，不增加 Kafka / ZooKeeper / Elasticsearch 等集群。现有 Redis 不擅自删除；新增能力优先 Interface + PostgreSQL 或有边界的本地实现。

## 2. HTTP 与身份

| 契约 | 冻结规则 |
|---|---|
| 返回体 | 保留 `R {code,msg,data}`。既有业务码 1000–1005 不重编号。安全异常不返回 SQL、栈或原始请求体。 |
| 追踪 | `X-Request-Id` 为规范名，保留 `X-Trace-Id` 兼容别名；响应、MDC、审计、异步事件共享同值。仅接纳有界安全字符的输入，非法值重新生成。 |
| 幂等 | JSON 核心建单/记数写操作使用 UUID `Idempotency-Key`。已有 `clientActionId` 保留，两者同传必须一致。唯一键限定到可信部署租户、用户、动作；主体和有效请求内容一并绑定摘要，修改主体头不得生成第二次动作。处理中 HTTP 409；成功原样重放；异体重用 1002。失败事务不得留下成功记录。 |
| 幂等重试 | 一次动作持有同一个键及请求体，结果未知时保持原键；完成或明确新动作才换键。写请求不作无条件自动重试。 |
| 主体上下文 | `X-Company-Id` 表示当前页面单据主体，不能在发送时从另一个标签的可变全局值替换。后端校验与请求单据一致；此头不是授权来源。 |
| 租户 | 现有数据库无 tenant_id，不宣称支持共享库多租户。可信部署租户作为命名空间，拒绝与部署不符的外来租户；跨公司授权如未全量接入须明确阻断该部署模式，不能仅靠传头宣称隔离。 |
| 登录刷新 | 现有登录为 JWT + 服务器会话。A2 若增加刷新，使用服务器可吊销的刷新凭据、HttpOnly Cookie、原子轮换及有效期；A3 只使用明确协商的接口，不能编造已有 refresh API。认证失败只允许一次刷新/重放，不自动重试无幂等键的写请求。 |
| 代理 | 客户端 IP / 协议仅从明确可信代理链读取；默认不信任转发头。 |
| 网关错误 | 409 / 413 / 429 / 503 返回有界 R 和追踪头；客户端同时支持既有业务码与 HTTP 状态。 |

草稿显式换主体的唯一例外：现有订单、采购单、生产单的草稿 `PUT` 可带 `expectedCompanyId`。它与 `X-Company-Id` 都必须等于数据库中当前主体；`companyId` 是用户明确选定的新主体。后端在锁定原单后校验当前版本、草稿状态、目标公司存在且启用，以及既有来源一致性约束。缺少 `expectedCompanyId` 时请求体与主体头仍必须一致；新建、批量动作和其他路由不接受这一例外。页面必须冻结原主体，不能把新选择或其他页签的主体当作原主体。

### 字段权限收紧（A2）

本次生产加固明确覆盖旧 PRD Q30 的默认开放规则。`supplier:supplier:contact` 控制供应商联系人、电话、微信和联系地址；预置仅给采购、财务、管理层及管理员。`supplier:supplier:bank` 控制银行、账号、收款户名，无权限时字段从 JSON 物理移除，不返回掩码或 null 占位。价格按业务域分权，避免查看销售报价或生产成交价时一并获得全部 BOM 成本；列表、详情、嵌套快照、系统生成摘要、价格档案、Excel 和 PDF 同口径。

| 权限 | 受控内容 | 预置角色 |
|---|---|---|
| `techpack:cost:query` | 物料/SKU 参考价、MATERIAL 价格档案、款式/BOM/资料包成本、采购和物料库存的单价金额 | 开发主管、采购、财务、管理层；保留既有分配 |
| `order:price:query` | 需求订单销售单价、行/款/表头金额，订单变更前后报价及生成摘要 | 业务、财务、管理层 |
| `production:price:query` | 生产单成交/参考单价与金额、排单报价、PROCESS 加工价档案及历史 | 生产跟单、采购、财务、管理层 |

管理员依现有规则取得全部权限；上述权限不互相隐含授予，修改还须原有业务编辑权限。价格档案按服务器真实行的 kind 分权，不能凭前端提交的 kind 通过授权。

后端不能将无权限字段缺省当作清零，也不能接受无权用户主动改价。已有可见但不具备财务字段权限的编辑路径按明细身份保留受控原值，主动变更拒绝 403；需求订单原本选填的新增销售价保持 null，不发明 0。其他必需价格由服务端可信来源计算或明确拒绝缺价，不能用隐藏输入框默认值报价。权限只能从服务器当前用户获得，不能来自请求头或前端声明。

### 登录刷新与日志导出接线（A2 → A3）

- 登录保持 `R<string>` access token，另设 `scm_refresh` 与浏览器绑定凭据 `scm_browser`，均为 HttpOnly / SameSite=Strict、路径 `/api/auth` 的 Cookie；HTTPS 或可信代理确认 HTTPS 时加 Secure，响应 `Cache-Control: no-store`。登录的 access 注册与刷新会话在同一数据库事务内提交。
- `POST /api/auth/refresh` 发送 `{}` 和 `X-Requested-With: XMLHttpRequest`，使用 Cookie 原子轮换，返回 `R<string>`；跨站请求拒绝。浏览器用同会话 epoch 的 Web Locks 串行轮换，锁内重读共享 access token；同页请求用 SingleFlight 合并。仅对只读或后端明确支持幂等的原请求重放一次，保留原 body、主体和动作键；重放后的业务错误保持原码。等待刷新时发生账号切换，旧请求不得覆盖新 token。
- 服务端仅为同一 family、浏览器绑定、当前用户版本提供固定 3 秒、最多 2 次的上一凭据重放；返回同一个 successor，不再次轮换，不延长窗口。超过窗口/次数、绑定不符、账号停用或版本失效返回 401；过期旧凭据的拒绝不能删除已经有效的新 Cookie。刷新凭据只存摘要，successor 由用途隔离的 HMAC 与随机 nonce 重建，刷新会话总寿命 7 天。
- `POST /api/auth/logout` 同样发送 `{}` 和上述自定义头；access 已过期时仍允许受同站校验保护的 Cookie 登出，撤销该 family 并清 Cookie。改密、重置密码、停用、删除即时提升用户会话版本；已有 access/refresh 随即失效。旧 Redis access 注册没有用户版本，升级后统一要求重新登录，不接受无版本旧会话。
- `POST /api/export-jobs/stock-logs` 接收库存日志筛选 JSON，不接受 page/size/cursor，要求固定 `Idempotency-Key`，成功返回 HTTP 202 / `R<ExportJobView>`。`GET /api/export-jobs/{id}` 查状态，`GET /api/export-jobs/{id}/file` 下载；`POST /api/export-jobs/{id}/cancel`、`/retry` 取消或重推，均返回 `R<ExportJobView>`。
- `ExportJobView`：`id:string`、`kind:STOCK_LOGS`、`status:PENDING|PROCESSING|RETRY_WAIT|SUCCEEDED|DEAD_LETTER|CANCELLED|EXPIRED`、`rows:number`、`attempts:number`、`requestId:string`、`createdAt:ISO`、`expiresAt:ISO|null`、`errorCode:string|null`、`downloadUrl:string|null`。错误码不带原始异常或内部路径。
- 所有任务操作要求 `inventory:stock:export` 且仅本人。任务冻结列权限上限；下载、重推另查当前有效身份和权限。原文件包含后来撤销的财务字段时拒绝下载，要求重建。
- 同步日志导出最多 1 万行，在开始响应前用有界查询判断，超限受控提示改用任务；前端日志导出统一使用任务。异步上限 100 万行 / 250MB / 15 分钟，每用户最多 2 个未终结任务，文件 24 小时过期。按 500–1000 行 seek 取数，不做全表 COUNT；达到限额、取消、失败须清理半文件。
- 任务租约 60 秒、每 10 秒心跳，指数退避，最多 5 次执行失败后进入死信；事务内登记任务、事务外生成文件，完成后以租约持有者条件更新。当前没有第三方集成业务，不将此导出消费宣称为第三方回调治理。

## 3. 数据与查询

- 数据库 BIGINT ID 的规范 JSON 表示为十进制字符串。前端不得用 Number / parseInt / 一元加号解析主键，版本、数量、页码保持数字。后端支持输入十进制字符串并校验范围；滚动升级须验证旧前端兼容性，否则明确安排同版本前后端切换，不能称零停机兼容。
- `version` / `planRevision` 维持当前数字契约；冲突保留本地内容，不偷偷换版本覆盖。
- 金额与数量后端用 BigDecimal / NUMERIC 重算；前端预览没有入账权威性。有效零值（例如允许的免费单价、盘点实盘 0）按领域语义处理，不能一刀切全部 >0。
- 库存多键按现有完整库存键统一排序，先来源累计，再库存，正反向相同顺序。不能把按 sku_id 排序单独替换成破坏既有来源锁序的实现。
- 一次最多查 200 个编码，前后端同口径；有界文本、排序白名单、参数化 SQL。单个单号/编码和名称都按字面包含匹配（编码列有 pg_trgm 索引），`%`、`_`、反斜杠不充当用户通配符；支持批量的接口按最多 200 个完整编码精确匹配。采购的历史订单/款号快照仍按存储的值查询，不换成当前主档值。少于三个字符的查询用不上 trigram 索引，可能合理扫描，受连接、语句超时与分页限制。（11-18 用户定：编码按包含查、上限 200，撤回本轮的“前缀匹配、上限 100”。）
- 纯日期为 YYYY-MM-DD，不转时区；事件时间以带偏移的 ISO-8601 输出。时间戳筛选用东八区业务日半开区间，DATE 列可用含首尾比较。
- 普通列表保留 `{list,total}`。高增长流水新增 seek 契约 `{list,nextCursor,hasMore}`，游标绑定排序、筛选与授权范围；统计单独查询，不让游标页隐含 COUNT。
- sys 表现有 PostgreSQL 存活行部分唯一索引可满足软删除重建，无须机械改成 deleted_token。业务主数据外键 RESTRICT + 停用保留。
- 已执行 V1–V26 不改。新增迁移独立编号；大表约束 NOT VALID 后验证，在线索引按 Flyway 非事务迁移处理，避免在业务锁上无限等待。

## 4. 物理写边界

| 阶段 | 允许修改 |
|---|---|
| A1 | Schema / 新迁移、DAO / Repository、发号/幂等/锁/精度核心及必要已有领域调用点、tests/prod-readiness/a1-*。不改前端、CI。 |
| A3 | web 前端及前端工具库、tests/prod-readiness/a3-*。不改后端、CI。保留原型视觉。 |
| A2 | 缓存、Outbox、导入导出/存储、安全/脱敏/限流/代理中间件及对应接入点、tests/prod-readiness/a2-*。新增迁移由主控协调，不能改 A1 已提交迁移。 |
| A4 | 追踪/日志/审计/外部调用/启动与生命周期、production-hardening-rfc.md、tests/prod-readiness/a4-*、已有 ci.yml。 |
| 主控 | 公共契约、pom 的专项测试源码接入、跨阶段契约接线、文档同步、docs/reviews/review-a1～a4.md、独立复查和 PR。 |

测试源码目录按 a1-db、a2-security、a3-browser、a4-lifecycle 分开。运行产物放 target/ 或 test-artifacts/，不提交。后端专项 Java 源码通过 Maven profile 接入，不使用伪并发或仅源码字符串断言替代数据库/浏览器行为。

## 5. 放行

主控复查完整 diff、独立运行现有测试/专项并发/类型/Lint/生产构建、读取真实 API 和 EXPLAIN、打开浏览器截图。未看到真实结果不签通过。阶段 PR 明确列出其全部事故编号与证据；未完成业务和环境限制留为未闭环，不勾选清零。集成顺序 A1、A3、A2、A4；允许 stacked PR，最终目标 dev，不自动合并 main。
