# S4 安全审计与生产凭据回归

沿用 `prod-readiness` profile、真实 PostgreSQL 和 HTTP 服务，不注册生产测试入口。

```sh
./mvnw -B -ntp -Pprod-readiness \
  -Dtest=A4LogTest,A4EnvironmentTest,A4ProbeTest,A4OutboxDrainTest,ArchitectureTest \
  -Dit.test=S4SecurityAuditHttpIT,A4AuditHttpIT,A4AuditBusinessHttpIT,A4AuditScaleIT,A4JdbcDeadlineIT,A4AsyncFailureIT,A4ContextPauseIT,SchemaConventionsIT verify
```

`S4SecurityAuditHttpIT` 验证五类真实行为：

1. 用户停启、密码重置通过实际接口写入；停用后原 Token 立即失效。数据库安全 Diff 只存白名单，密码哈希、用户名、昵称、手机号、部门和岗位不入库，密码变化只记录会话版本。审计接口需同时拥有审计和全部数据权限。
2. 角色编码、数据范围、状态的准确前后值；菜单 / 用户角色关系保留实际 INSERT / DELETE。联合键每个 ID 都是字符串，超过 JavaScript 安全整数的用户 ID 不失真，关联行不制造代理 `rowId`。
3. 在实际授权事务的 AFTER INSERT 中注入失败，核对旧授权仍在、全部字段审计回滚、失败 OperLog 保留、500 不泄露 SQL 或堆栈。
4. 以自动提交连接完整重复执行安全审计迁移两次；原实体缺失 row_id、关联缺键 / 多键 / 非对象 / 非字符串 / 非正整数 / 溢出 / root 不符均被数据库拒绝。两个约束须 validated，append-only 仍生效。
5. 实际 PostgreSQL VALIDATE 事务保持打开，检查持有 ShareUpdateExclusive 而不是 AccessExclusive；另一真实连接仍可提交角色 INSERT 及审计。迁移分两段事务，测试不能重新把整段 DDL 套进一个长事务。

安全审计迁移由 `R__security_field_audit.sql` 和它的 `executeInTransaction=false` 配置共同提供。它先提交短元数据锁，再单独验证存量；第一段原子失败回滚，第二段失败仍保留新写约束。繁忙存量库可能受锁等待 / 扫描期限阻止，不能仅凭测试库结果宣称任意规模零停顿。

生产凭据测试在原 `A4EnvironmentTest` 和 `tests/prod-readiness/a4-lifecycle/process_gate.py` 中。真实进程门禁在连接池 / HTTP 初始化前拒绝 DB / Redis 短弱口令、周期重复口令，并确认日志没有拒绝值；进程门禁仍需实际运行，不能用单元测试代替。

当前轮的匿名 JSON 写入 `test-artifacts/full-audit/s4/`，Maven XML 留在 `target/`。两者均忽略并由现有 CI 脱敏后上传 Artifact；不提交截图、日志、测试凭据或临时导出文件。通过状态以本次实际退出码及主控台账为准。
