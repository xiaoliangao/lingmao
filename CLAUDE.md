# SCM 项目（lingmao 学习项目的代码仓库）

本仓库是用户的学习主线项目，**执行手册在 `~/yyy/repo/projects/lingmao/CLAUDE.md`，先读它**。资料、模式目录、review 都在那个目录。

## 快速事实

- Spring Boot 4.1.1 / Java 21 / Maven / MyBatis-Plus 3.5.17（分页插件在 mybatis-plus-jsqlparser）/ Flyway / springdoc 3.1.1 / PostgreSQL 16（服务器）/ Redis 7（服务器）
- 包结构：`com.lingmao.scm.{common,config,module.<域>.{controller,service,service.impl,mapper,entity,dto}}`；样板模块 = `module.system` 的字典类型
- 配置：`application.yaml` 只有占位符；真实连接在 `application-local.yaml`（gitignore，勿提交、勿外泄）
- 跑：IDEA 运行 `ScmApplication`（用户用 8080）；执行者检查用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`，**不要杀 8080**
- 测：`mvn -q test`；Swagger：`/swagger-ui.html`
- 建表：只加 `src/main/resources/db/migration/V<n>__*.sql`，不改已执行的
- 约定细则：`~/yyy/repo/projects/lingmao/docs/04-后端架构讲解.md` §5
