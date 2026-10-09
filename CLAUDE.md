# SCM 项目（lingmao 学习项目的代码仓库）

本仓库是用户的学习主线项目，**执行手册在 `~/yyy/repo/projects/lingmao/CLAUDE.md`，先读它**。资料、模式目录、review 都在那个目录。

## 快速事实

- Spring Boot 4.1.1 / Java 21 / Maven / MyBatis-Plus 3.5.17（分页插件在 mybatis-plus-jsqlparser）/ Flyway / springdoc 3.1.1 / PostgreSQL 16（服务器）/ Redis 7（服务器）
- 包结构：`com.lingmao.scm.{common,config,module.<域>.{controller,service,service.impl,mapper,entity,dto}}`；样板模块 = `module.system` 的字典类型
- 配置：`application.yaml` 只有占位符；真实连接在 `application-local.yaml`（gitignore，勿提交、勿外泄）
- 跑：IDEA 运行 `ScmApplication`（用户用 8080）；执行者检查用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`，**不要杀 8080**
- 测：`mvn -q test`；Swagger：`/swagger-ui.html`
- 建表：只加 `src/main/resources/db/migration/V<n>__*.sql`，不改已执行的
- 约定细则：`~/yyy/repo/projects/lingmao/讲义/2026-10-01-00字典类型样板.md` §5
- `docs/讲义/`：每天讲义的副本，**原件在 `~/yyy/repo/projects/lingmao/讲义/`**。只改原件，改完再同步过来（`rsync -a --exclude _模板.md --exclude .DS_Store <原件目录>/ docs/讲义/`），以「文档:」前缀单独提交。本仓库是公开的：同步前查一遍有没有服务器地址、密钥、需求里的真实客户数据
