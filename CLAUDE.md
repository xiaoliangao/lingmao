# SCM（服装供应链管理系统）

接手的 Claude 先读完本文，再读 `CLAUDE.local.md`（本机私有信息，gitignore，不在仓库里）。

## 目标

2026-12-01 一期上线，给甲方真实使用。需求真值是 `docs/PRD.md`，技术方案是 `docs/架构设计.md`（本文"技术约定"是它的摘要，对不上时以它为准），每个模块的具体设计在 `docs/rfc/`（写代码前写，用户审过再动手），原型在 `docs/原型/index.html`，进度在 `docs/进度.md`。

甲方表里没标黄的模块是**待定、以后再做**，不是不做（PRD §2.5）：一期把它们要用的数据记全，按架构 §14 留口子，但不提前写空代码。

上线节奏（PRD §2.4）：11-14、11-21、11-28 各发一版，12-01 一期上线；12 月做二期，每周发一版。

PRD 还是草稿，§2 分期和 §12 待决问题等甲方确认。确认以后的顺序（用户 11-05 定）：更新架构文档、写 RFC，再出计划和接口设计，然后写代码。甲方答复了就改 PRD，在"变更记录"里加一行；没答复的按默认做法做。

## 分工（2026-11-05 起）

- **Claude 写**：按 PRD 写后端、前端、测试和部署，然后提交。每个模块做完，在对话里用几行说清楚：做了什么、为什么这么设计、关键代码在哪、怎么验证的。
- **用户**：审代码、提问、试用、提意见；想自己写的地方会说，那部分就留给他。
- 不写讲义，不留作业，不出面试题。积压的 bug 001-007 由 Claude 修（见 `docs/进度.md`）。
- 设计上的取舍由 Claude 拍板，在说明里讲理由；涉及需求的不确定点按 PRD §12 的默认做，并告诉用户。

## 每次开工

1. `git status && git log --oneline | head`：用户可能在改东西，不动他没提交的改动
2. `docs/进度.md`：做到哪了，下一个是什么
3. PRD 里对应的那一节

## 技术约定

- 后端：Spring Boot 4.1 / Java 21 / Maven / MyBatis-Plus 3.5.17（分页插件在 `mybatis-plus-jsqlparser`）/ Flyway / springdoc / PostgreSQL 16 / Redis 7。Spring Boot 4 的 starter 名和 3 不同（`@WebMvcTest` 在 `org.springframework.boot.webmvc.test.autoconfigure`）；依赖升级先查文档。
- 分层：`com.lingmao.scm.module.<域>.{controller, service, service.impl, mapper, entity, dto}`，依赖方向不能反。同一个业务域里可以直接用兄弟表的 Mapper 做只读检查，跨域只调 Service。样板是 `module.system` 的字典类型。
- 返回体 `R`，错误码 0 / 400 / 401 / 403 / 404 / 500 / 1xxx（业务错误，如库存不足）。分页 data 是 `{ list, total }`。
- 时间列 `TIMESTAMPTZ` ↔ `OffsetDateTime`，JSON 东八区。数量、单价、金额用 `NUMERIC`（PRD §7.3），不用浮点。
- 逻辑删除 `deleted`；唯一约束用部分索引 `WHERE deleted = false`。
- Flyway：只加 `V<n>__*.sql`，已执行的一个字都不改。**哪个环境里有了甲方的真实数据，从那时起迁移只增不删、不改列类型**。
- 路径用复数名词；动作用 `POST /api/xxx/{id}/audit` 这种。
- 单号用 `common/seq/SerialNoGenerator`：前缀 + yyyyMMdd + 4 位流水。
- 单据：状态转换集中校验（允许的转换表），不写散落的 if；审核过账在一个事务里（改状态、改库存、写日志、回写源单）；库存用条件更新防并发（PRD §7.3）。
- 权限：菜单和按钮权限都在 `sys_menu`，后端 `@PreAuthorize` 兜底；数据范围按 PRD §3.2。
- 测试：每个模块 Service 单测覆盖主要规则 2-4 个（JUnit 5 + Mockito）+ 接口测试 1 个（`@WebMvcTest` + `@MockitoBean`）。样板：`DictTypeServiceImplTest`、`DictTypeControllerTest`。纯 Mockito 测试里用到 Lambda 条件的 `in` 时，要先 `TableInfoHelper.initTableInfo(...)`（见 `RoleServiceImplTest`）。

前端（`web/`，Vue 3 + TypeScript + Element Plus + Vite + pnpm）：

- 两块区域：工作台 `/` 所有人，业务菜单 + 页签（keep-alive，页面组件按 `Menu<id>` 命名）；管理后台 `/admin` 只给超级管理员（后端 V7 `sys_menu.admin_only` 兜底）。业务菜单建在工作台的顶层目录下，页面文件还没做时自动显示"还没上线"。
- 新页面套 `PageHeader` + `.panel` + `FilterBar` + `el-table`（不加 border）+ `StatusTag`（实线 = 定了，虚线 = 待处理或停用）。数字列 `class-name="num"`，编码列 `"code"`。
- 颜色只用 `web/src/styles/tokens.css` 的变量（植物染：靛蓝主色、茜红危险、栀黄待处理、苔绿完成、坯布灰底），不写死。
- 文案：按钮写清楚做什么；删除确认要说后果，按钮就写"删除"；空表格要说明为什么空。
- 原型 `docs/原型/index.html` 是给甲方看的样子，做真页面时照它的结构，用真组件重写。原型本身要改：改 `docs/原型/src/`，再跑 `docs/原型/build.sh`。

## 运行与测试

- **本机开发环境**（11-05 起，不再连服务器）：`docker compose -f dev/compose.yml up -d` 起 PostgreSQL 16（`127.0.0.1:5433`，开发库 `scm`、集成测试库 `scm_test`）和 Redis 7（`127.0.0.1:6380`），都没有密码、只绑本机。数据在 `dev/data/`（gitignore）。开发完清理：`docker compose -f dev/compose.yml down --rmi all && rm -rf dev/data`。连接配置在 `application-local.yaml`，模板是 `.example`。
- 用户在 IDEA 里跑应用，占 8080 端口。**绝不杀 8080**。Claude 自己起服务用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`，只杀 18080。Swagger 在 `/swagger-ui.html`。
- 跑测试：`mvn -q test -Dtest='!ScmApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false`。不排除 `ScmApplicationTests` 的话，它会连开发库 `scm`，把还没提交的新迁移执行掉；之后迁移文件一改，校验和对不上，应用就起不来。
- 新迁移先在 `scm_test` 里跑通（`DB_URL` 换成 `.../scm_test`），跑完重建它：`docker exec scm-dev-postgres psql -U scm -d scm -c 'DROP DATABASE scm_test' -c 'CREATE DATABASE scm_test'`。开发库 `scm` 里有用户自己的数据，不在上面试。
- 测写操作（POST/PUT/DELETE）只用临时数据，测完物理删除；用户的数据只发 GET。
- 前端：`cd web && pnpm install && pnpm dev`。

## 部署

**甲方那边负责部署**（11-05 定）。我们交付一套 Docker 部署包（应用、Nginx、PostgreSQL、Redis）和部署手册；服务器、域名、HTTPS、备份的异地存放由部署方负责。部署包和手册的做法见 `docs/rfc/002-交付部署包.md`。

`deploy/` 里现在的脚本是 10-12 给旧服务器写的，旧服务器 11-05 起不再给本项目用。交付包做好之前不要运行 `deploy.sh`。

备份脚本（每天 `pg_dump`，保留 7 天）随部署包交付；哪个环境里有了甲方的真实数据，哪个环境就要开备份（架构 §12.2）。

## Git

- GitHub `xiaoliangao/lingmao`，直接提交 main，提交后推送（用户 11-05 定）；不 force push。
- 提交前缀：「功能:」「页面:」「修复:」「框架:」「部署:」「文档:」。
- 提交前先看 `git status`：工作区里有用户的改动时，只 `git commit -- <自己的文件>`。
- **仓库是公开的**。提交前 grep 一遍：服务器地址和端口、密钥、真实账号的密码哈希、甲方的企业名称和联系方式、真实价格和供应商。甲方给的原件和其他私有资料放 `docs/private/`（gitignore）。
