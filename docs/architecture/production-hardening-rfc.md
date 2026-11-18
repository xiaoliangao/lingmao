# 生产加固 RFC：并发、边界、审计与生命周期

日期：2026-11-18。范围：A1 → A3 → A2 → A4 四个独立阶段；公共契约以 [production-contract.md](production-contract.md) 为准。实施与主控独立验收分别记录，远端 PR / GitHub Actions 未执行不能写成已通过。

## 1. 业务范围与部署模型

当前系统是 Java 21 / Spring MVC / PostgreSQL 16 / Vue 3 的模块化单体，保留原有 Redis 和 Compose 启动方式。新增组件通过 `SerialNoGenerator`、共享缓存/限流接口、`OutboxService`、`OutboxHandler`、`ExportFileStore` 和生命周期参与者接入；没有引入 Kafka、ZooKeeper、Elasticsearch 或分库中间件。

实际业务包括主数据、供应商、款式、资料包、需求订单、算料采购、物料库存和生产。仓库没有完整收付款/核销、财务月结、电子合同、物流/BI 回调、全套标签库；也没有原系统全部 160 页、52 个搜索页面或私有《模块字段清单》原件。库存估值未知仍是 `UNKNOWN`，不能补成零。本文的“已修复”仅指列出的实际路径。

数据库没有通用 `tenant_id` 业务分区，也没有覆盖全域的公司独立 ACL。现有模式是**一个可信部署命名空间、共享公司数据、服务器角色数据范围**。`X-Company-Id` 冻结当前页面的业务目标，由服务端核对；它不是权限声明。部署命名空间写入数据库单例；另一命名空间连接同库时拒绝启动。私有导出目录另有存储身份校验，两台机器的不同本地目录不能冒充共享卷。共享库多租户/公司隔离模式未实现，应保持部署守卫关闭该模式，不接受“多带一个请求头就完成隔离”的配置。

## 2. 保持不变的公共契约

| 项目 | 规则 |
|---|---|
| 错误与响应 | 保留 `R {code,msg,data}` 和原 1000–1005 业务码；500 仅安全中文及追踪码，无 SQL、栈、参数和原请求体。 |
| 追踪 | `X-Request-Id` 规范名、`X-Trace-Id` 兼容名；安全 8–64 字符值原样传递，非法输入生成 UUID。仅规范 ID 不走手机号/银行卡正则，以免长数字 ID 在日志被改写。 |
| 幂等 | 同一动作保持原 UUID `Idempotency-Key`、原请求体和原主体。数据库事务认领；处理中 409；成功重放仍检查实时权限和对象可见性；换体/换主体拒绝。 |
| 主键/版本 | BIGINT 主键以十进制字符串进入 JSON，包括审计记录、旧/新关联 ID；`version`、`versionLock`、`planRevision` 保持整数。 |
| 金额 | 服务器以 BigDecimal / NUMERIC 重算。未知 DTO 字段包括客户端伪造总额直接 400；合法数量/价格才进入计算。 |
| 日期 | 业务日直传 `YYYY-MM-DD`；时间戳使用带偏移 ISO-8601。东八区日期筛选转换为半开区间，不漏当天 23:59。 |
| 查询 | 批量编码最多 200；单个编码和名称都按包含查（编码列 trigram 索引），LIKE 字面转义，排序白名单。普通列表保留 `{list,total}`；高增长流水用 `{list,nextCursor,hasMore}`。 |
| 刷新 | HttpOnly / SameSite=Strict Cookie 原子轮换，固定家族期限；同页单飞、跨页 Web Locks，原请求最多重放一次；写请求须有后端已支持的同一幂等键。 |

## 3. 数据库与异步工作

### 3.1 事务、锁和查询

发号采用 PostgreSQL 原子步进；幂等记录与业务提交在同一事务；单据以状态和版本 CAS 检查冲突。库存按完整库存键的稳定顺序锁定，数量增减是数据库条件原子更新。采购末批金额采用累计数量/累计金额差分，避免逐批四舍五入吞掉分币尾差；退货反向抵消。

金额计算保留现有业务口径：数量乘原口径单价，以 BigDecimal / NUMERIC 计算。仓库尚无已定义的含税反算、独立税额、整单运费或抹零分摊、总账收付款与核销模型及接口，这些能力未实现、未验收。生产回货既有规则限定为每 SKU 回货上限向下取整至订单量的 103%、累计回货达到总量的 97% 算完成；不能据此宣称所有业务均已实现短溢容差结案。

数据库池容量和连接、语句、锁、空闲事务等待均有明确边界。迁移使用 `SET LOCAL`，不能用会污染池连接的 `SET ...; RESET ...`。测试会同时借出全部池连接检查超时，防止只抽中一条正常连接就误报通过。文件解析在事务外完成，提交阶段再次验证当前账号和权限；文件与远程资源不能占着核心事务等待。

库存流水游标绑定筛选、排序、身份和授权范围，不做深 OFFSET 或隐式 COUNT。批量核价和关联信息预加载保持 SQL 数量不随单据行数线性增长。A1 的实际十万行夹具、133 份 EXPLAIN 和访问行数阈值提供回归证据；不能把“计划出现索引名”当作没有大扫描的证据，也不能据此承诺任意短词或高命中查询都无扫描。

### 3.2 缓存与 Outbox

缓存键含可信部署命名空间；使用短 TTL 空值、TTL 抖动、跨进程单次回源、提交后失效及回填版本栅栏。T08 要求两个实际生产 JVM、100 个 Barrier 同起请求、PostgreSQL 实际字典 SELECT 恰好一次。单 JVM 的两个对象或顺序 HTTP 请求不能替代该门禁。

Outbox 是现有缓存失效和库存日志导出任务的持久状态机。业务变更与待处理事件同事务提交；消费者通过 `FOR UPDATE SKIP LOCKED` 领取，带租约 token 更新完成状态。租约 60 秒、10 秒心跳；超时回收、指数退避、最多 5 次执行失败后死信。旧持有者不能提交新租约的结果；重推和下载仍检查当前用户、字段权限和任务所有权。

每个实例最多两个重型导出 worker，控制线程与重型工作分开。正常停机继续维持已领取任务心跳；开始摘流后不再领取新任务或开始新的清理批次。强停时保留可回收的持久租约，删除未提交半文件，由下一实例的 reaper 接管，不能把中断请求伪装成成功。

## 4. 可追踪日志和真实字段审计

### 4.1 三个日志边界

应用使用统一 JSON 行布局，`message` 按 UTF-8 最多 4096 字节，栈位置最多 2048 字节；线程、logger、异常类等元数据也分别受限，整个布局小于 16 KiB。密码、token、appSecret、电话、税号、银行账号按键与模式遮罩；转义引号、没有结束引号的截断值、opaque Bearer 和多 Cookie 头均有测试。异常仅记录类型和有界栈位置，不输出异常 message/cause message。异常 cause 和栈帧分别限深，空栈循环 cause 不能卡住日志线程。业务操作日志不保存 DTO 值、请求 payload 或 SQL 参数。

`ObservedDataSource` 测实际 JDBC 执行耗时；慢语句只记录操作类型、语句指纹、耗时和成功标志。每次借池连接覆盖 actor、真实 IP、request ID、操作和明细关联身份，匿名/后台工作也会清空上一次的上下文。Outbox 执行时恢复事件 request ID，完成后清理 MDC。

应用布局覆盖不到 PostgreSQL 和 Nginx 自身日志。部署 PostgreSQL 禁止原 SQL、参数、错误行 DETAIL 和错误语句；`invalid input syntax` 的主 ERROR 本身也可能包含业务值，因此严格隐私模式进一步压低数据库服务端消息级别。代价是 DB 详细错误诊断转由应用安全错误类型、SQL 指纹、探针和退出状态定位；需要原始数据库诊断时，应在受控隔离数据环境复现。不能在生产打开 `log_statement=all` 来追查银行字段。T08 仅在隔离空测试库临时开语句日志，参数日志仍为 0，原 PG 日志只进程内计数，结束即删除、从不上传。

Nginx 使用单独安全 access 格式，只含 method、无 query 的 URI、状态、耗时和规范 request ID，禁止默认 combined 的完整 `$request`，防止 `/files?...signature...` 落日志。可能带原请求上下文的原生 error 日志关闭，由安全 access 状态、应用错误和探针补充定位。部署日志门禁以真实 PG / Nginx 注入敏感哨兵并检查日志，不能只测试一个字符串工具类。

### 4.2 DataLog、业务时间轴与读取留痕

V36 在 28 张实际主数据/单据/明细/库存表安装数据库字段触发器，记录 `{field:{old,new}}`、actor、真实 IP、request ID、业务根单据、PostgreSQL 事务编号和时间。触发器与业务事务共同提交或回滚；失败事务不能留下“已改价”事实。`biz_doc_log` 记录业务动作时间轴并补齐同一 request ID/IP；`sys_oper_log` 记录操作人、动作和有界元数据。

BOM 和采购草稿原本删除重建明细，新的物理行 ID 不能让旧价历史失联。审计切面在事务入口只采集 DTO 明确携带的旧行 ID 与保存顺序，不采集价格/账号值。数据库仅在**同事务、同根单据、确实发生 DELETE 的旧行**匹配时追加 `REPLACE` diff，保留原始 DELETE 和 INSERT。BOM 子项通过已确认的父行替换和数据库唯一尺寸键关联。重复物料/部位、调序和重复工序名不按自然键猜配；没有可证实旧身份的新增行只保留物理事件。

删除重建的四类内部查询另有部分索引：已替换 BOM 父行按当前行 ID 定位；旧子行按旧父行定位后核对色码；重复替换按 `previous_row_id` 定位；旧 DELETE diff 按旧行 ID 定位。只有根单据/事务索引时，PL/pgSQL 的通用计划可能为每个 SKU 重扫持续增长的整版审计历史。`A4AuditScaleIT` 使用 10 个真实 BOM 父行及 500/1000 个 SKU，保留外键和全部物理/语义审计，核对旧新身份、数量、事务与成本，并采集真实触发器的内部 `auto_explain` 计划和访问行数；不以固定毫秒门槛代替这些断言。

这些索引不改变匹配条件和审计内容。普通物理 INSERT/UPDATE 不进入新增的部分索引；每条 DELETE 额外维护两个索引，一般 REPLACE 额外维护一个，BOM 父行 REPLACE 额外维护两个。容量评估和磁盘预算必须计入这部分写入及存储成本。色码匹配仍会筛选同一旧父行的候选，单个父行的极大矩阵仍需容量控制，不能将根单据扫描消除理解为任意大小保存都具有固定延迟。

查询接口支持二选一：

```text
GET /api/data-logs?table=pur_order_item&rowId=<当前明细ID>&beforeId=<上一页末ID>&limit=50
GET /api/data-logs?rootTable=tp_tech_pack&rootId=<资料包ID>&transactionId=<可选事务ID>&limit=50
```

均要求 `system:audit:query` 和 `data:all`，按部署命名空间、主键游标取最多 100 条，无 COUNT。专用 `DataLogView` 已按持久化表和价格域投影，不再被通用 system controller 的 COST 默认策略二次误删。订单价、生产价、成本分别授权；价格档案 MATERIAL/PROCESS 取实际持久记录类型；公开吊牌价仍可见。银行、联系人及待审批银行变更的嵌套 before/after 分别授权，无权限字段物理消失。自由文本和未经定义的嵌套快照不向审计 API 输出，避免其中暗含受控价格。

每次审计查询都先写入可核查的读取记录：actor、table/rowId 或 rootTable/rootId、事务/游标、条数、IP、request ID；不写查询结果、账号全号或价格。写读取日志失败时响应失败，不放出敏感内容。

`sys_data_log` / `sys_oper_log` 由数据库触发器拒绝 UPDATE、DELETE、TRUNCATE；业务时间轴拒绝 UPDATE/TRUNCATE，DELETE 返回零行，保留旧应用删草稿时原来尝试清理时间轴的滚动兼容性。没有应用级清除审计接口。数据库 owner/superuser 能更改触发器，这不是对数据库管理员的不可篡改存证；生产管理员权限、备份和异地保留仍需运维控制。

审计覆盖明确有界：`sys_user` / `sys_role` 字段不在这 28 张表内，`sys_user_role` / `sys_role_menu` 复合键成员关系也未生成 diff；用户停用、角色数据范围和授权成员变更没有完整的字段前后值证据，通用操作元数据不能替代。上述表和关系需要另行设计权限审计。旧版本写入/人工 SQL 的 actor 可为空，并保留 SYSTEM 操作标记，不能伪造一个真实用户。其他费用没有稳定旧行身份时通过根单据物理删除/插入历史查证，不猜测一一对应。

## 5. 启动、健康与退出

生产 `EnvironmentPostProcessor` 在数据库/Redis/HTTP 服务建立前验证 JWT、文件签名密钥、连接配置、非空凭据和安全开关。拒绝已知弱值、低多样性或短周期重复密钥，拒绝 prod 混用 local/it/test/mock、任意 DEBUG/TRACE logger、SQL 参数输出、自定义日志配置、禁用 Flyway 和宽松 JSON。初始密码若提供须强且不超过 BCrypt 的 72 个 UTF-8 字节；全新数据库没有管理员且没提供初始密码时拒启，已有管理员允许移除初始化密码。命名空间、私有存储身份和 Flyway 验证通过后才打开业务准入。

`/health/live` 是不访问数据库的轻量进程探针。`/health/ready` 同时要求应用已完成启动、迁移校验成功且无待迁移项、实际 `SELECT 1` 成功、未摘流。深探针只有一个实际执行额度、零排队执行器、1 秒 JDBC 网络/查询期限、1.5 秒 HTTP 等待上限；取消 Future 不会提前释放额度。线程真正退出后才释放，避免数据库黑洞时探针无界积压。

真实 SIGTERM 的顺序：

1. 最高优先级关闭事件立即标记不接流量，ready 返回 503；HTTP 连接器暂时保留，live 仍可访问。
2. 新业务请求受控 503/Retry-After，停止领取新 Outbox 任务和新 housekeeping 批次；请求计数覆盖同步事务和流式下载。
3. 自定义阶段最多 25 秒等待已有 HTTP、worker 和控制 SQL；已有任务正常完成或达到期限后发出协作停止信号。
4. Spring Web 再提供最多 35 秒退出阶段；Outbox 对 worker/scheduler 共享最多 15 秒截止，必须确认实际 `terminated`；探针销毁最多另等 3 秒。之后才关闭数据库池。
5. 若最后仍不能证明后台线程退出，记录明确失败状态，由 `HaltStrategy` 非零终止进程，避免继续销毁数据库时仍有后台写入。正常停止不走该兜底；任务由租约接管。

部署 `stop_grace_period=90s`，覆盖 25+35+15+3 秒上限并留余量。停机门禁使用真实 HTTP 行锁、真实 export 下载的套接字背压、heartbeat/ack 与清理 SQL 等待，不安装任何生产测试路由。短排空期限门禁验证实际强停和下一真实 JVM 重试接管；不能用日志出现“stopped”代替线程终止与数据库结果断言。

Spring 7 上下文暂停与最终关闭分别处理。一次性的摘流控制器声明 `isPauseable=false`，暂停不改变最终拒流状态；Outbox 随依赖暂停并在 restart 时重建执行器。`A4ContextPauseIT` 使用真实 HTTP 上下文执行两轮 pause/restart，每轮验证 ready、业务接口和实际 Outbox/Redis 任务，最后 close 才触发完整排空。此修正也避免测试上下文缓存恢复后一直返回 503，不改变测试生命周期策略。

未捕获线程异常只记录线程名/异常类型，立刻拒接新请求并启动一次受控关闭；Outbox 定时任务显式捕获致命 Error，避免 ScheduledExecutor 静默停止轮询。Java 的线程/Future 模型按实际 API 治理，未把 Node 的 `unhandledRejection` 名称当作实现证据。

### 外部 HTTP 调用范围

本次源码检查没有发现已上线的 BI、电子合同或物流 HTTP 客户端。实际 PDF 资源入口由 A2 限制为受控本地上传路径，测试中的外部 HTTP 探针请求数为 0；构建阶段下载字体或运维脚本不属于业务出站集成。因此 #42 的当前结论是“现有业务无该出站路径”，不能宣称某个尚不存在的第三方熔断已实测。

将来新增集成必须实现可替换客户端接口，设置连接、响应和总体调用预算，限制并发且在事务外执行；通过 Outbox 发送、独立幂等回执和单向状态推进接收。接入时必须增加真实慢连接/慢响应/断连门禁；本文不引入一个没有调用点的熔断工具类来充数。

## 6. 上线迁移与回滚

已发布 V1–V26 不改。V27–V36 是本轮新增迁移；尚未发布阶段的修订由最终提交统一确定校验和，正式发布后再修改必须新建迁移。升级前保存数据库与上传/私有导出目录一致的备份，执行已有恢复演练和只读备份账号门禁。

采用 Expand–Contract：先加新表、可空元数据列、索引和新写约束；新旧版本兼容窗口结束前不删除旧列、不重写历史账。V27 的 `NOT VALID` 已约束迁移之后的新写，并不表示旧数据全合法。上线前应列出 `pg_constraint.convalidated=false` 的约束，逐表按约束谓词查找异常、人工确认修正凭据或业务调整；之后在低峰逐项 `VALIDATE CONSTRAINT`。验证会扫描历史数据，需要 I/O 预算和监控，不能在发布事务中把所有大表锁着扫描。

V28、V33、V34 的在线索引部分保留 Flyway 非事务配置；`CREATE INDEX CONCURRENTLY` 失败时先检查 `pg_index.indisvalid`，处理无效索引再按迁移规程重试。不得机械 `repair` 掩盖业务 DDL 未完成。事务迁移用有界 `SET LOCAL lock_timeout`，V36 3 秒锁等待/30 秒语句预算；拿不到锁就拒启，不无限阻塞发版。新建空审计表上的索引可事务创建，不能把同一做法复制到既有千万行表。

回滚优先回退应用镜像及匹配的前端版本，保留扩展后的表、约束和已提交业务/审计数据；不运行反向 DELETE/DROP 回滚脚本。旧版请求/ID 契约、严格 DTO 和字段权限改变必须在切换前验证兼容性；若旧前端不兼容就协调同版本前后端切换，不能把后端能摘流描述成整个版本任意混跑都零停机。回滚到弱鉴权、无参数白名单的版本需要单独安全判断，不自动降级。

V36 保留旧应用删草稿时的业务时间轴：旧 DELETE 成功执行但影响零行。字段审计触发器不依赖新版 ORM 实体，旧版业务 SQL 仍留物理 diff；没有新版身份上下文时 actor 不可追溯为某个用户，需要从发布窗口和其他日志辅助调查。禁止为回滚临时关闭 append-only 触发器。

## 7. 日常运维与容量

### 7.1 任务、私有文件和磁盘

库存日志同步导出最多 10000 行，超限引导后台任务；后台上限 100 万行/250 MiB/15 分钟，每用户最多两个未终结任务，完成文件 24 小时后过期。查询按 500–1000 行 seek 取数。取消、客户端断连、权限撤回、大小/时间达到上限均停止后续取数并清理未提交文件。上传内容通过类型魔数、容量、解压/ZIP、路径与符号链接边界；孤儿清理与新业务引用争抢同一保护条件。

DEAD_LETTER 先按 request ID 定位安全错误码、尝试次数和当前权限，修复原因后使用已有 retry 接口；不要直接把 PROCESSING 改回 PENDING，也不要删除 Outbox 记录“消除红灯”。租约未过期的任务不得人为抢占。取消/重推/下载接口限定任务本人及当前导出权限；如原文件包含已撤销的价格权限，应重新生成，不继续发旧文件。

部署应用滚动日志 100MB/文件、30 天、总量 1GB，容器 stdout 10MB×3。备份、上传、导出和数据库分开统计用量。建议 70% 磁盘占用预警、85% 告警并限制新大导出；保留至少一个最大导出文件和迁移/备份工作空间。阈值是运维建议，不能冒充代码已自动触发这些告警。现有清理任务有批次和时间预算，磁盘满仍会失败并受控回报；“有 TTL”不等于永远不会 ENOSPC。

不要自动删审计表、库存流水、幂等成功记录或备份来腾空间。审计保留/归档需经业务与审计要求确认，采用校验完整性的归档及受控迁移；当前应用没有在线删除审计能力。多实例导出必须同一真实共享私有挂载；备份恢复也要恢复存储身份文件，不能只复制路径字符串。

### 7.2 百万到千万数据的演进条件

以下是容量决策触发条件，不是未经测量的上线承诺：

| 观察窗口/指标 | 先采取的措施 | 何时考虑替换接口实现 |
|---|---|---|
| 连续两个高峰，关键列表 p95 >300ms 或单页实际访问 >1000 行 | 用脱敏 SQL 指纹找计划，检查统计信息、筛选选择性、索引、seek 和批量预加载 | 完成查询治理仍受主库 I/O 限制，再评估只读副本；强一致余额/权限/任务领取继续主库 |
| 流水/审计达到百万行，索引持续增长、VACUUM 或备份超过维护窗口 | 按业务时间和访问模式测量保留策略、归档和分区成本，先在恢复库演练 | 接近千万行且历史查询与在线写互相干扰时再引入分区/归档；不以一个行数阈值自动分库 |
| 池等待 p95 >100ms、活跃连接持续 >80% 或锁等待集中在同一键 | 查长事务、重复工作、固定锁序和慢解析；限制入口与重型并发 | 单机资源持续饱和后横向扩容，所有共享状态仍通过现有接口；先验证总连接数不越数据库预算 |
| Outbox 最老待处理 >60s，或日常处理能力不足持续 15 分钟 | 区分毒消息与容量；修 DLQ 原因、查询索引和文件 I/O，按测量调 worker 上限 | 数据库队列表的锁/写放大成为瓶颈后，用同一幂等/租约语义替换 MQ 实现；本轮不部署集群 |
| 缓存回源峰值放大、Redis 内存告警或限流错误增加 | 检查 namespace、回填栅栏、负缓存和 TTL，保证会话/限流键不被静默淘汰 | 仅在内存和高可用预算明确时扩容现有 Redis；不能用进程内 Map 假装跨实例保护 |
| 大文件影响业务延迟或本地卷接近预算 | 限额、清理、私有共享卷、错峰任务 | 通过 `ExportFileStore` 替换对象存储，仍保留下载授权和安全文件名，禁止直接暴露永久公链 |

应记录请求 p50/p95/p99、实际 SQL 访问行数、池等待、锁等待、Outbox 租约/失败/队龄、导出大小/耗时、磁盘与备份恢复时间。日志字段和状态接口已经提供排障基础，本轮没有部署完整时序监控/告警平台，也没有声称完成千万行线上压测。

## 8. T01–T12 可复现门禁和 CI

统一入口是现有 `.github/workflows/ci.yml`，没有另建绑定阶段分支的工作流。CI 使用 PostgreSQL 16、Redis 7、JDK 21、固定 Node/pnpm 与 Playwright；先完成实际数据库 IT，再给临时 PostgreSQL/Redis 配置 SCRAM/密码，执行生产 JAR 门禁。双 JVM 使用随机空 `_test` 库和共享私有导出目录，读 PG 日志之前写入并等待唯一 SQL marker，确保 Docker 日志 follower 真正送达全部待计数语句。

```sh
# 现有全部单测、真实数据库 IT 与四阶段专项；需要专用测试 PostgreSQL/Redis 和 PDF 字体。
./mvnw -B -ntp -Pprod-readiness verify

# 后端定向定位：下表的测试类均可用这一形式选择，不能以定向结果冒称全量通过。
./mvnw -Pprod-readiness -Dtest=ArchitectureTest -Dit.test=A1DatabaseProductionIT,A1HttpCreationIT verify
./mvnw -Pprod-readiness -Dtest=A4LogTest,A4EnvironmentTest,A4ProbeTest,A4OutboxDrainTest,ArchitectureTest \
  -Dit.test=A4AuditHttpIT,A4AuditBusinessHttpIT,A4AuditScaleIT,A4JdbcDeadlineIT,A4AsyncFailureIT,A4ContextPauseIT,SchemaConventionsIT verify

pnpm --dir web typecheck
pnpm --dir web lint
pnpm --dir web test
node --test tests/prod-readiness/a3-browser/core.test.mjs
pnpm --dir web build
python3 tests/prod-readiness/a3-browser/run.py
python3 tests/prod-readiness/a4-lifecycle/artifact_gate.py
python3 tests/prod-readiness/a4-lifecycle/ci_helpers_test.py

# 已启动实际后端并注入真实登录测试环境后执行双页签 Cookie 门禁。
python3 tests/prod-readiness/a3-browser/real-session.py

# 临时 PG/Redis 的凭据和显式测试开关从环境注入，不写在命令行。
python3 tests/prod-readiness/a4-lifecycle/process_gate.py

# 双 JVM 的 PG_DSN / DB_URL / Java / 日志要求见 A2 README。
python3 tests/prod-readiness/a2-security/two_instances.py \
  --jar target/scm-0.0.1-SNAPSHOT.jar --pg-log /path/to/disposable-postgres.log \
  --work-dir /path/to/disposable-a2-evidence

# 原生网关及部署日志；也有独立 Docker PG16 / Nginx1.28 CI 适配。
python3 tests/prod-readiness/a2-security/gateway.py
python3 tests/prod-readiness/a4-lifecycle/deployment_logs.py \
  --postgres-bin /path/to/pg16/bin --nginx /path/to/nginx
```

运行条件及秘密注入见 [A2 门禁](../../tests/prod-readiness/a2-security/README.md)、[A3 浏览器](../../tests/prod-readiness/a3-browser/README.md)、[A4 进程](../../tests/prod-readiness/a4-lifecycle/README.md) 和 [部署日志](../../tests/prod-readiness/a4-lifecycle/deployment_logs.md)。本地单实例发版允许短暂受控 503；持续可用需要两个实际实例、共享状态/文件及真正按 ready 摘流的负载均衡，不能仅凭 Compose healthcheck 宣称零停机。

| 门禁 | 真实入口与断言 | 业务范围 |
|---|---|---|
| T01 | `A1DatabaseProductionIT`：20 个同时就绪的事务争抢发号，唯一性及结果落库校验 | 已有业务发号 |
| T02 | `A1HttpCreationIT`：20 HTTP 同键建单、409 竞争、成功回放、只落库一次；换体/主体和回滚验证 | 已有核心创建，不含未实现收付款 |
| T03 | `A1DatabaseProductionIT`、`A1HttpCreationIT`：同版本双写仅一笔成功，失败状态码与 DB 结果一致 | 单据 CAS |
| T04 | `A1DatabaseProductionIT`：50 请求争最后 10 件、反序多 SKU 真锁竞争、无负库存/死锁 | 物料库存 |
| T05 | `A1DatabaseProductionIT`、`A1HttpCreationIT`、`A1PurchasePrecisionIT`：负数/非有限数、采购分批尾差、伪造总额零写、合法重算、长 ID 字符串 | 采购/库存原口径金额；不含价税反算、整单费用分摊或总账核销 |
| T06 | `A1HttpCreationIT`、`A1DatabaseProductionIT`、`SchemaConventionsIT`：反审/历史日期守卫、软删除编码重建 | 不做库存期间冻结（11-18 用户定），未验证财务月结 |
| T07 | `A1KeywordQueryIT`、`A1DatabaseProductionIT`、`A1CostBatchIT`：23:59、批量上限、实际 EXPLAIN/扫描量、预加载 SQL 次数 | 13 类真实查询及核价 |
| T08 | `A2CacheStateIT` + `two_instances.py`：100 Barrier、两个真 JVM、日志排空 marker、实际 PG 回源=1；命名空间隔离和提交/回滚失效 | 可信部署 namespace 缓存 |
| T09 | `A2OutboxStateIT`、`A2ExportHttpIT`、`A2ImportIT`、`A2ExcelInputTest`：真实租约、DLQ/重推、TCP 断连停止下一页、公式精确行列错误 | 已有异步、五条导入与库存日志导出 |
| T10 | `A2BusinessHttpIT`、`A2FinancialHttpIT`、`A2ExportScopeHttpIT`、`A2InputHttpIT`、`A2RefreshHttpIT`：混合不可见 ID、未知字段、JSON/Excel/PDF 权限、停用/降权/刷新吊销 | 已有数据范围，未冒称共享库租户 ACL |
| T11 | `core.test.mjs`、`run.py`、`real-session.py`：8/16 并发 401 单次真实刷新、实际 Abort、IME、跨时区、跨页签与错误恢复 | 仓库实际组件和生产传输层 |
| T12 | `A4LogTest`、`A4EnvironmentTest`、`A4AuditHttpIT`、`A4AuditBusinessHttpIT` + `process_gate.py`：同 ID 的 HTTP/慢 SQL/Outbox、掩码、500、真实改价/读取留痕、生产拒启 | 真实业务 HTTP、DB 与生产 JAR |

补充门禁还覆盖：无界探针队列反例、真正未捕获线程异常与 Hikari 关闭、上下文暂停/恢复及最终关闭、真实 SIGTERM 流/事务/控制 SQL/强停恢复、生产 JAR/dist 没有 mock/测试入口、PG 与 Nginx 原生日志隐私。既有原型检查、备份只读账号、真实恢复演练、20 项实际业务 UI 和 PR 前后截图全部保留。

CI 最后先脱敏再上传 Artifact：JUnit/Failsafe、EXPLAIN、枚举结果、两份合成 BOM/采购审计 JSON、浏览器截图。脱敏失败时禁止上传。会话测试只对布尔结果做凭据断言，避免密码/Cookie 先进入 Maven 控制台；文件脱敏额外处理裸 Cookie、AssertJ 数组和 XML 转义。原始 PostgreSQL 语句日志、JVM 线程转储、环境秘密、临时 XLSX 和运行目录不进入 Git；双 JVM 的完整 PG 原始日志不上传。双 JVM wrapper 超时先对整个子进程组 TERM、限时后 KILL，再删库与恢复 PG 日志配置。报告里的实际查询数、结果 JSON 和截图仅代表对应运行，不借旧成功文件代替本次退出码。

## 9. 48 项逐号状态

下表“通过”指本地实际实现和列出的门禁证据；远端干净 Ubuntu / Redis 7 的执行状态另列于下一节，不以本地替代。A1/A3/A2 的独立主控结论见 [A1 审查](../reviews/review-a1.md)、[A3 审查](../reviews/review-a3.md)、[A2 审查](../reviews/review-a2.md)；A4 最终放行以主控独立报告为准。

| 编号 | 状态 | 实际措施与证据/限制 |
|---|---|---|
| #01 | 已修复通过 | 原子发号、20 事务同起；T01。 |
| #02 | 已修复通过，限定现有创建 | 同事务幂等、主体/内容摘要绑定、回放重新授权；T02。无支付/回调模块。 |
| #03 | 已修复通过 | 状态+版本 CAS、真实竞争；T03。 |
| #04 | 已修复通过 | 稳定多键锁序、条件扣减、50 抢 10；T04。未实现的打印计数不计覆盖。 |
| #05 | 已修复通过；存量待上线审计 | DTO/领域数值范围与有限数 NOT VALID 约束；T05。旧脏数据须单独校验。 |
| #06 | 已修复通过，限定现有金额口径与采购分批过账 | 原口径金额、分批数量/金额末批清零及退货抵消；T05。既有生产回货 103%/97% 规则保留；价税反算、整单费用分摊、总账收付未实现。 |
| #07 | 撤销 | 11-18 用户定不做库存期间冻结：原 V28 及其测试、P2007 映射已去掉，后面的迁移顺次前移一号。 |
| #08 | 原架构保留并补测通过 | 系统软删除唯一性、主数据停用+外键历史保护；T06。 |
| #09 | 已修复通过 | MVC/嵌套/audit BIGINT 字符串，前端字符串主键；T05/T12。 |
| #10 | 已修复通过，限定现有调用 | 连接/SQL/锁/空闲事务期限、解析移出事务；所有池连接验证；A1/A2 IT。 |
| #11 | 已修复通过 | 库存日志 seek，无隐式 COUNT；实际 EXPLAIN；T07。 |
| #12 | 已修复通过 | 200 词上限（11-18 用户定）、字面转义、日期半开、索引与访问行数验证；T07。短词不承诺无扫描。 |
| #13 | 已修复通过，限定已审路径 | 批量预加载，核价与 PDF 多色 SQL 次数固定；T07/A2 PDF 真实验证。 |
| #14 | 已修复通过；生产存量待演练 | Expand–Contract、并发索引、局部锁时限；空库/现有迁移验证。没有线上大库执行声明。 |
| #15 | 已修复通过 | 数据库租约与共享缓存/限流/会话；两个独立 JVM；T08/T09。 |
| #16 | 已修复通过 | namespace、空缓存、TTL 抖动、跨进程单飞、提交后失效和回填栅栏；T08。 |
| #17 | 已修复通过，限定实际任务 | Outbox 事务提交、心跳/reaper、退避/DLQ/重推；T09 和 A4 真实重启接管。无外部业务回调。 |
| #18 | 已修复通过 | seek 流、异步限额、TCP 断连、GET/HEAD 共用准入、五条导入原子写；T09。 |
| #19 | 已修复通过 | 私有文件限额/TTL、孤儿引用竞争、存储身份、RFC5987 下载头；A2 文件/导出 IT。 |
| #20 | 已修复通过，限定现有部署模型 | 命名空间拒绝、主体一致性、真实 SELF/ALL、混合 ID 全量校验；T10。共享库租户 ACL 未实现，模式拒启。 |
| #21 | 已修复通过 | DTO 未知字段/尾随 JSON 拒绝，状态/总额服务端控制，导入提交再授权；T05/T10。 |
| #22 | 已修复通过，限定已审接口 | 三种价格及银行/联系独立投影，JSON 物理剥离、Excel 整列移除、PDF 无价；T10 和 A4 独立域审计。 |
| #23 | 已修复通过 | 实时身份/权限/session version、阶梯冷却、Cookie 固定期限轮换与跨实例吊销；T10/双 JVM/真浏览器。 |
| #24 | 已修复通过 | 公式输入、ZIP/魔数、路径/链接检查；PDF 外部探针零请求；T09/A2 文件测试。 |
| #25 | 已修复通过 | 显式可信代理链，真实 IP 分层限流/审计；真实 Nginx/API 与 A4 伪造 XFF 断言。 |
| #26 | 已修复通过 | 严格同源/CORS、Secure Cookie、保留 Host 端口、安全头与结构化 413；Nginx/API 真实门禁。 |
| #27 | 已修复通过 | 一次 chunk 恢复、脏表单不强刷、no-cache HTML / immutable hash 资源；浏览器+Nginx。 |
| #28 | 已修复通过并完成真实接线 | 单页/跨页刷新互斥，两个真实页签 16 个 401→一次刷新→16 重放；T11。 |
| #29 | 已修复通过 | AbortController/序号、旧搜索/切版请求取消不覆盖；T11。 |
| #30 | 已修复通过 | isComposing / 229 / compositionend 同任务保护；T11。 |
| #31 | 已修复通过 | 跨页签会话/主体同步、请求快照、后端主体复核；浏览器+T10。 |
| #32 | 已修复通过，限定真实组件 | 路由拆包、55列×1000行虚拟化、固定列对齐、localStorage 失败兜底；浏览器。未声称原系统28表全验。 |
| #33 | 已修复通过 | 50色×20码矩阵局部更新、聚焦/失焦7位值完整；浏览器。单次样本不是生产 p95。 |
| #34 | 已修复通过，限定已接入口 | 缩略图、懒加载/破图、Blob 与监听/请求清理；浏览器。未执行长期堆泄漏基准。 |
| #35 | 原日期架构保留并补测通过 | 上海/UTC/洛杉矶同一日期字符串；T11。 |
| #36 | 已修复通过 | 20 并发动作互斥、固定幂等键、仅安全请求重放、未知结果回执；浏览器+T02。 |
| #37 | 已修复通过 | 脏表单关闭提醒、身份隔离的有界草稿、显式恢复/放弃；浏览器。不保存 token/账号全号。 |
| #38 | 已修复通过 | 局部错误边界/重试/追踪码，数组归一化且缺价不填零；浏览器+T12。 |
| #39 | 已修复通过 | canonical/legacy 请求头、规范化、MDC、慢 SQL、DataLog 和 Outbox 同 ID；T12，含长数字合法 ID。 |
| #40 | 已修复通过 | UTF-8 日志预算、密码/头/PII 遮罩、cause 限深、安全500；PG/Nginx 原生日志另测；T12。 |
| #41 | 已修复通过，限定28表与既有时间轴 | 真 HTTP 改名/银行/BOM/采购、数据库 diff/事务回滚、重复行旧ID关联、append-only 和读取留痕；T12。用户/角色及授权成员字段审计不在覆盖范围。 |
| #42 | 当前业务无该出站路径 | 源码无 BI/合同/物流 HTTP 客户端；PDF 禁网络资源已真测。未宣称不存在的集成已完成熔断。 |
| #43 | 已修复通过 | 轻量 live、迁移+实际 DB ready、零排队/执行额度、网络期限；A4 探针反例与生产断库。 |
| #44 | 已修复通过，限定实际流/事务 | 真 SIGTERM 拒新流量/任务，行锁事务、18万行下载、heartbeat/cleanup 排空，短期限真实接管；A4 process。 |
| #45 | 已修复通过 | 实际 JAR 弱/缺配置、嵌套 DEBUG、无管理员密码、namespace/storage mismatch 全部拒启；T12/process。 |
| #46 | 已修复通过 | 未捕获线程异常受控拒流/关闭池；定时任务致命异常显式处理；A4AsyncFailureIT。 |
| #47 | 已修复并接构建门禁 | 实际 JAR/dist 扫描测试/Mock入口，prod混入测试profile拒启，浏览器夹具只在忽略目录；artifact/process gate。 |
| #48 | 已接现有CI；远端执行受限 | T01–T12、真生产进程、网关/日志、原型/备份/恢复/UI统一编排；本RFC列清边界与演进阈值。远端Actions尚未运行。 |

## 10. 实测证据和仍需在目标环境完成的验证

2026-11-18，主控在最终源码 `45302d9` 上独立运行完整 `-Pprod-readiness verify`：489 个单元测试、240 个实际集成测试，失败/错误/跳过均为 0，BUILD SUCCESS，耗时 16 分 19 秒。23 张十万行夹具的 133 条核心与 5 条导出计划通过；500/1000 SKU 审计规模回归同时验证实际 SPI 索引、完整旧新身份与同父候选范围。此前的 48 个单元/架构测试、27 个 PG IT 定向验证为历史分阶段证据，最终结果以本次全量为准。

主控用同一最终生产 JAR 依次完成 19 项真实进程门禁、两 JVM 的 100 并发、20 项实际业务 UI 和 7 项真实会话。180000 行、18578037 字节导出取得真实下载背压和线程排空证据；强停后租约保留、部分文件移除，下一 JVM 成功接管。100 个缓存未命中请求仅一次实际 PG 回源，计数在真实日志屏障后读取；双标签页 16 个过期请求仅一次刷新且全部重放成功。

前端类型检查、Lint、19 个 Node 测试、16 个核心回归、生产构建及 58 项浏览器专项通过。5 项 CI 辅助回归和最终 JAR/web 产物隔离通过。PG 与 Nginx 日志门禁分别使用真实 PostgreSQL 16.11 和 Nginx 1.26.3，8 项 Nginx 日志断言及原 9 项网关回归通过。截图由主控实际打开核对；原始日志和运行物保持在忽略目录。

本地 Redis 是 8.0.2；CI/部署目标 Redis 7 尚须实际 CI 运行验证。Docker PG16 和 Nginx1.28 编排适配已接入并静态核查，本地无 Docker，不能把原生进程结果写成 Docker/干净 Ubuntu runner 已通过。远端发布被自动审批拦截，尚未创建阶段 PR、运行远端 Actions 或合入 main；本地四阶段独立提交和主控审查记录完整保留。

完整执行入口、实际证据、返工过程和边界记录在 [review-a4.md](../reviews/review-a4.md)，报告提交不修改已验收生产源码。正式上线前还需在真实数据副本审计 NOT VALID 约束、演练升级/回退及备份恢复、确认磁盘和共享卷预算，并针对实际并发量观察延时与锁等待。
