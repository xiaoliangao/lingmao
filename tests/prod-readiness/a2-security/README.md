# A2 生产边界回归

这些测试使用实际 PostgreSQL、Redis、HTTP 和 XLSX。`A2*IT` 随 `prod-readiness` profile 加入 Maven；输出日志和生成文件不提交。

```sh
mvn -Pprod-readiness -Dtest='ArchitectureTest,A2*Test' -Dit.test='A2*IT,SchemaConventionsIT' verify
```

`application-it.yaml` 指向专用 `scm_test` 数据库和 Redis 14 号库。`IntegrationTestConfig` 会重新迁移测试数据库，因此只允许测试库名。队列自动轮询在该 profile 关闭，Outbox/导出专项自行启动真实 dispatcher，避免不同测试 context 抢任务。

## 覆盖边界

| 测试 | 实际验证 |
|---|---|
| `A2CacheStateIT` | 100 个任务全部就绪后同时开始，两个实例对象共用实际 Redis；单次实际 PG 回源、命名空间隔离、负缓存、OOM 降级、实际字典提交/回滚、慢回填失效栅栏 |
| `A2RateStateIT`、`A2InputHttpIT` | 共享限流、阶梯锁定、Redis OOM 受控 503、可信代理/CORS/租户头、未知 DTO 字段、定长与 chunked JSON 上限、实际上传/导入/下载路径 |
| `A2RefreshHttpIT` | 两个 HTTP 客户端并发旧 Cookie，固定 3 秒/最多两次重放；当前用户版本、改密、停用、重置密码竞争和过期 access 下 Cookie 登出 |
| `A2OutboxStateIT`、`A2ExportHttpIT` | 同事务业务与消息、并发租约、僵尸回收、旧租约栅栏、退避/DLQ/重推、取消、权限撤销、过期与重试竞争、实际 TCP 断连后停止下一页查询 |
| `A2ExportStorageIT` | 私有文件大小限制和共享挂载身份，拒绝不同本地目录冒充同一存储 |
| `A2ImportIT` | 五条实际导入业务的流在事务外打开/读取/关闭；两 HTTP 客户端重复文件只落一批；精确行列错误、公式文本拒绝、最后一行数据库失败整批回滚；解析期间数据范围降权、旧请求权限和无登录拒绝 |
| `A2BusinessHttpIT` | 实际 sales 报价、merchandiser 排单/PROCESS 档案、warehouse 专用来源投影及收发货；主体头/草稿换主体/混公司 MRP；供应商联系与银行字段物理移除 |
| `A2FinancialHttpIT` | 数量编辑保留旧价及计价单位；订单变更保留隐含价格/费用说明；零数量 SKU 恢复；主档成本保留；各业务域 Excel 整列移除 |
| `A2ExportScopeHttpIT` | 订单、采购、生产、库存单、盘点五条真实导出：混入不可见 ID 或目标公司不符时在输出文件头前拒绝，合法选择仍可下载 |
| `A2BomHttpIT` | 重复业务键明细按真实 ID 调序，BOM/SKU 价独立保留；歧义/外版/重复身份拒绝；模式规范化；加工费修订时带过去、无核价权限不能改 |
| `A2ExcelInputTest`、`A2UploadContentTest`、`A2UploadLifecycleIT` | 实际恶意 ZIP/公式、导出导入往返、文件魔数/容量/引用与孤儿清理竞争 |
| `gateway.py` | 实际 Nginx HTTP/TLS、资源缓存、安全头、结构化 413 和代理链 |

部署仍是可信命名空间下的单部署共享公司模式。公司头用于核对业务目标，角色数据范围另行授权；这些测试不宣称公司数据已具备独立多租户存储隔离。

## 两个独立 JVM

`two_instances.py` 以 `prod` profile 再启动两个完整应用进程，证明共享状态不依赖同一 JVM。它对一个有真实选项的新字典发起 100 个 Barrier 同起请求，并读取 PostgreSQL 实际语句日志，要求回源 SELECT 恰好一次。随后验证跨进程 Cookie 轮换、重放上限、改密与登出吊销；登出使用签名正确但已经过期的 access token。令牌、Cookie 和密码均不打印。

前置条件：Java 21、Python `psycopg` 3、已打包应用 JAR、**全新且名称以 `_test` 结尾的专用数据库**、Redis、PostgreSQL `log_statement=all` 的可读日志。仅允许在临时测试实例或该空测试数据库启用语句日志，同时必须设置 `log_parameter_max_length=0` 和 `log_parameter_max_length_on_error=0`，不记录绑定参数。该脚本不会清空已有数据库，发现已有 `sys_user` 即停止。两个 JVM 共用脚本生成的密钥和同一私有导出目录。

从测试环境注入以下变量：`PG_DSN`、`DB_URL`（JDBC）、`DB_USER`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`。可用 `REDIS_DB` 选独立测试库。不要把凭据写进命令行或日志。

```sh
python3 tests/prod-readiness/a2-security/two_instances.py \
  --jar target/scm-0.0.1-SNAPSHOT.jar \
  --java /path/to/java-21/bin/java \
  --pg-log /path/to/test-postgres.log \
  --work-dir /path/to/test-artifacts/a2-instances
```

脚本在标准输出和 `--work-dir/summary.json` 写入脱敏结果，包含 `profile`、`instanceCount`、`requestCount`、`actualPgLoads` 和逐项 `sessionChecks`。CI 可上传该 JSON 作为验收证据，成功时要求 `status=PASSED`、实例数为 2、请求数为 100、实际 PG 回源数为 1，所有会话标志为 true。

应用日志和私有文件留在指定目录供失败定位，CI 按其正常产物策略清理；不要提交 Git。**原始 PostgreSQL 语句日志仅用于测试进程内计数，不能上传为 CI Artifact**；测试结束后随临时实例删除。
