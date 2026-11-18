# 部署日志真实门禁

`deployment_logs.py` 读取实际 `deploy/package/compose.yml` 中 PostgreSQL 的整个 `command` 列表，再启动全新的 PostgreSQL 16 实例并逐项 `SHOW` 核对。它实际执行唯一键冲突、非法类型转换及超过一秒的含字面量 SQL，确认数据库原始日志没有绑定参数、错误 DETAIL、主 ERROR 消息或原始 SQL 中的敏感测试值。非法类型的客户端主错误、唯一键错误的客户端 DETAIL 均须确实包含测试值，避免用没有触发泄漏风险的 SQL 冒充验证。

读取数据库日志前先关闭测试连接，再优雅停止整个测试实例并确认退出码为 0，等待全部日志生产者退出。原生模式随后关闭日志文件句柄再读；Docker 模式在容器停止后读取完整日志，最后删除容器及匿名卷。缺失断言不依赖固定 sleep，也不会在仍运行的 PostgreSQL 日志流上提前宣布通过。

PostgreSQL 的 `terse` 只隐藏 DETAIL/CONTEXT，主 ERROR 仍可能含业务值，所以生产另设 `log_min_messages=log`：服务器日志的级别顺序里 ERROR、WARNING 排在 LOG 之下，语句报错不进 PostgreSQL 进程日志；启动、关闭、检查点和 FATAL/PANIC 照常记，数据库起不来时部署方能看到原因（11-18 从 `panic` 放宽）。语句级故障通过应用安全异常类型、SQL 指纹/耗时定位。需要原始 SQL 的深诊断只能在隔离数据或受控临时维护窗口进行。

Nginx 门禁使用原有 HTTP、HTTPS 和公共 location 配置，实际覆盖签名文件 200/404、无效签名 403、符号链接 403、上游断开 503、HTTPS 下载、HTTP 跳转和不可信 request ID 规范化。测试的 `http` 层故意配置含完整 `$request` 的不安全日志，要求所有生产 server 都覆盖它，防止在同一层追加安全日志后仍写入镜像默认 combined 日志。

生产访问日志仅包含时间、方法、不带参数的 `$uri`、状态、耗时、规范 request ID。Nginx 内置请求错误日志无法替换其完整 request 字段，因此 server 层关闭该原始错误流；错误仍体现为安全访问记录中的 4xx/5xx。主进程启动/配置错误日志保留。内部错误页跳转时 `$uri` 是最终处理路径，request ID 保持同一条请求的关联。

## 原生执行

需要 Python `psycopg` 3、PostgreSQL 16 的 `postgres/initdb`、支持当前生产配置的 Nginx、OpenSSL。脚本自行分配回环端口，创建临时数据库实例、文件与证书，结束后全部清理；不连接任何已有业务数据库，不需要 Java、Redis 或 Docker。

```sh
python3 tests/prod-readiness/a4-lifecycle/deployment_logs.py \
  --postgres-bin /path/to/postgresql-16/bin \
  --nginx /path/to/nginx
```

## CI 分开执行

PostgreSQL 部分在已有 Docker 的 runner 上启动一个独立 `postgres:16` 容器，使用来自真实 compose 的命令，随机回环端口、空数据目录，结束后删除容器：

```sh
python3 tests/prod-readiness/a4-lifecycle/deployment_logs.py \
  --component postgres --docker-postgres
```

Nginx 部分可在既有 A4 gateway 测试镜像中执行，无需 Python PostgreSQL 驱动或 Docker socket：

```sh
python3 tests/prod-readiness/a4-lifecycle/deployment_logs.py --component nginx
```

`target/test-artifacts/a4-deployment-logs/` 只保存脱敏的 `postgres-results.json` 与 `nginx-results.json`，包含真实版本、配置和断言结果。原始 PG/Nginx 日志与签名密钥始终留在临时目录中，结束后删除，不上传为 Artifact、不提交 Git。
