# A4 生产进程与生命周期门禁

Java 审计、脱敏和异步异常测试通过 Maven `prod-readiness` profile 运行。`process_gate.py` 另行启动实际生产 JAR，向真实 PostgreSQL、Redis 和 HTTP 接口注入故障；不注册生产测试路由。

`A4ContextPauseIT` 使用真实 Spring 7 `context.pause()/restart()` 两轮切换，检查重启后健康探针及 `/api/version` HTTP 正常，并让真实 Outbox/Redis 任务在切换前后完成。一次性的 `GracefulLifecycle` 不参与 pause，最终 `close()` 才永久摘流；Outbox 保持可暂停，并在 restart 时重建 executor，避免依赖暂停后仍消费任务。该测试不改变 Spring 测试缓存或生命周期处理器，也不增加 profile 特例。

```sh
./mvnw -B -ntp -Pprod-readiness verify
python3 tests/prod-readiness/a4-lifecycle/process_gate.py
```

## 运行条件

只在可丢弃的测试服务上运行。脚本要求显式环境变量 `SCM_PROD_READINESS=1`，仅接纳本机回环 PostgreSQL / Redis，且 Redis 必须已经启用密码。调用进程需提供以下变量；秘密由临时环境注入，不写入命令行或仓库。

| 变量 | 用途 |
| --- | --- |
| `SCM_TEST_PG_HOST`、`SCM_TEST_PG_PORT` | 默认 `127.0.0.1:5433` |
| `SCM_TEST_PG_USER`、`SCM_TEST_PG_PASSWORD` | 默认测试用户 `scm`；需要创建和删除临时数据库的测试权限 |
| `SCM_TEST_REDIS_HOST`、`SCM_TEST_REDIS_PORT` | 默认 `127.0.0.1:6380` |
| `SCM_TEST_REDIS_PASSWORD` | 已配置到该测试 Redis 的临时密码 |
| `JAVA_HOME` | Java 21；也可从 PATH 查找 Java |
| `SCM_TEST_JAR` | 默认 `target/scm-0.0.1-SNAPSHOT.jar` |

脚本新建随机 `_test` 数据库，生成临时密钥，使用私有文件目录和 Redis 11 号测试库。所有 JVM 顺序启动，每个进程限制 512MiB 堆、4 个可见处理器。数据库、私有文件和进程在退出时清理；不连接既有业务库。

## 真实断言

| 场景 | 证据 |
| --- | --- |
| 生产安全配置 | 实际 JAR 对缺失/弱 JWT、文件密钥、初始密码、数据库/Redis 密码、混入测试 profile、全局及嵌套 logger 调试级别拒绝启动 |
| 启动身份 | 新库没有管理员密码时拒启；已经绑定的部署命名空间、私有导出存储标识不匹配时拒启 |
| 健康探针 | PostgreSQL 禁止新连接并终止现有应用会话后，ready 有界返回 503，live 仍为 200；恢复数据库后 ready 恢复 |
| HTTP 与事务排空 | 外部连接持有真实公司行锁，HTTP 修改在 PostgreSQL 等待；同时真实 Outbox 确认在 SQL 内等待。SIGTERM 先拒新流量/任务，再完成旧事务及其字段审计 |
| 流式文件与清理线程 | 实际库存日志导出 API/worker 生成 180000 行 XLSX。客户端停读，以 HotSpot 的 SIGQUIT 诊断快照确认实际 `ExportJobController.download` 线程阻塞于套接字输出；同时实际 session-cleanup DELETE 等待数据库锁。SIGTERM 后先释放 SQL 锁，再恢复下载，校验完整长度与 SHA-256 |
| 心跳控制线程 | 以数据库实际轮询语句定位调度时间，使真实心跳续租与任务完成确认同时等待 PostgreSQL 锁。释放前不得关闭连接池；释放后两个 executor 必须报告实际终止 |
| 超时分支与重启 | 生产排空期限设置为 1 秒，实际导出 worker 的完成 SQL 持续被锁阻塞。进程有界退出，未提交文件被清理，PROCESSING 租约保留。下一个真实 JVM 的 reaper 回收租约，worker 第 2 次执行成功 |
| 关闭顺序 | 摘流 → 排空结果 → workers/control executors 均已终止 → Hikari 开始并完成关闭。检查实际应用数据库会话全部消失 |

超时恢复测试只调整临时数据库的租约过期时间和退避到期时间，以压缩等待；状态转换、任务重领和文件生成仍由生产 reaper/worker 完成。控制锁、观察触发器和合成数据仅存在于脚本新建的测试库中。

## 证据与限制

`target/test-artifacts/a4/process-results.json` 保存逐项结果、受控耗时、行数、字节数和合成文件摘要。仅全部门禁成功后才设置顶层 `passed=true`。同目录只保存结构化应用日志，并替换运行时秘密；完整 JVM 线程转储、初始化日志系统之前的原始输出、PostgreSQL 语句日志、私有导出文件均不作为 Artifact 保存。

运行此脚本前必须先构建包含 A4 生命周期修复的 JAR。静态语法通过不代表进程门禁通过；以本次生成的 JSON 和实际退出码为准。所有 `target/` 产物保持在 Git 之外。
