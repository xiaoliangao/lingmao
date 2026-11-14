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
- **子代理同时最多开两个**（用户 11-05 定）。多出来的步骤排队，前一个做完再开下一个。

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

- 两块区域，共用 `layout/AppLayout.vue`（左边侧边栏，没有顶栏）：`/` 所有人，首页 + 业务菜单 + 顶部页签（keep-alive，页面组件按 `Menu<id>` 命名，首页组件 `HomePage`）；管理后台 `/admin` 只给超级管理员、没有页签（后端 V7 `sys_menu.admin_only` 兜底）。`/` 这一页叫「首页」，不叫「工作台」；菜单管理里这块区域叫「业务菜单」。业务菜单建在它的顶层目录下，页面文件还没做时自动显示"还没上线"。
- 页面结构照原型：每页顶上是 `PageHeader`（`title` / `sub` / `back`，插槽 `#title` / `#actions`）。列表页是 `PageHeader` → `FilterBar` → `el-table`（铺满，不套 `.panel`，不加 border）→ `el-pagination.pagination`；其他页的内容放 `<div class="page">`。状态用 `StatusTag`（`active | progress | pending | done | off | closed | void | danger`）。数字列 `class-name="num"`，编码列 `"code"`（等宽字体）。对话框表单标签放上面（`label-position="top"`），两列用 `.form-grid`。
- 筛选：`FilterBar` 用 `v-model:keyword` + `@search`，分段控件放 `#left`，其他条件写成 `<FilterItem label :text @clear>` 收进"筛选"弹层，选了的显示成能去掉的小标签；日期用 `DateRange`，标签文字用 `formatDateRange`。
- 选数据（用户 11-14 要的）：下拉一律能搜（`el-select filterable`）；要批量处理的列表加勾选列和底部批量条，只做现在能做的，没成功的逐条报原因、留着勾；新建单据加明细，从物料库 / 款式库的弹窗里挑（搜索 + 分类 + 一次勾多条，已在单上的灰掉）。样子照原型的 `Pick`、`BatchBar`、`DataPick`（`docs/原型/src/3-ui.js`、`3e-pick.js`）。
- 没保存的修改：对话框表单写 `useUnsavedChanges(form, dialogVisible)`，对话框加 `:before-close="guard.beforeClose"`；整页表单写 `useUnsavedChanges(form)`，加载完、保存完调 `markSaved()`。切页签不问，关页签、去管理后台、退出、刷新时问。
- 公共件，用法写在各文件开头的注释里：
  - 列表页用 `composables/useTable.ts`；导入用 `components/ImportDialog.vue`；多图用 `ImageUpload`。
  - 字典下拉用 `DictSelect`，表格里显示字典文字用 `useDict(type).labelOf`；选人用 `UserSelect`。
  - 数量、金额、单价、日期用 `utils/format.ts`；判断错误码用 `utils/errorCodes.ts` 里的名字，不写数字。
  - 下载、上传用 `utils/request.ts` 的 `download`、`upload`。
  - 系统名只在 `src/config.ts` 改。
- 颜色只用 `web/src/styles/tokens.css` 的变量（白底、暖灰底、铜褐主色；红 = 危险，琥珀 = 待处理，绿 = 完成），不写死；Element Plus 的样子统一在 `styles/element.css`。字色在白底和浅底上都不低于 4.5:1（用户 11-14 嫌看不清后加深的），新加颜色先算对比度。原型的颜色在 `docs/原型/src/style.css` 开头，两边一起改。
- 文案：按钮写清楚做什么；删除确认要说后果，按钮就写"删除"；空表格用一句话说明为什么空。页面上不放大段说明文字。
- 原型 `docs/原型/index.html` 是给甲方看的样子，做真页面时照它的结构，用真组件重写。原型本身要改：改 `docs/原型/src/`，再跑 `node docs/原型/check.mjs`（打包成 `index.html`，跑内置自测，逐页打开查报错；`--shots 目录` 截图）。业务规则在 `src/2-store.js`，页面按模块分文件；各文件共用一个全局作用域，顶层函数重名会悄悄互相覆盖，`check.mjs` 会查出来。侧边栏"业务流程"页（`src/8-flow.js`）是给甲方看的流程说明，原型的流程改了要跟着改。每个模块右侧的"模块说明"栏（`src/8b-modinfo.js`）写着这个模块有哪些信息、上下游是谁；页面加了页签、关联变了，跟着改（流程位置和谁能看是自动取的，不用改）。

## 运行与测试

- **本机开发环境**（11-05 起，不再连服务器）：`docker compose -f dev/compose.yml up -d` 起 PostgreSQL 16（`127.0.0.1:5433`，开发库 `scm`、集成测试库 `scm_test`）和 Redis 7（`127.0.0.1:6380`），都没有密码、只绑本机。数据在 `dev/data/`（gitignore）。开发完清理：`docker compose -f dev/compose.yml down --rmi all && rm -rf dev/data`。连接配置在 `application-local.yaml`，模板是 `.example`。
- 用户在 IDEA 里跑应用，占 8080 端口。**绝不杀 8080**。Claude 自己起服务用 `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`，只杀 18080。Swagger 在 `/swagger-ui.html`。
- 跑测试：`mvn -q test -Dtest='!ScmApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false`。不排除 `ScmApplicationTests` 的话，它会连开发库 `scm`，把还没提交的新迁移执行掉；之后迁移文件一改，校验和对不上，应用就起不来。
- 新迁移先在 `scm_test` 里跑通（`DB_URL` 换成 `.../scm_test`），跑完重建它：`docker exec scm-dev-postgres psql -U scm -d scm -c 'DROP DATABASE scm_test' -c 'CREATE DATABASE scm_test'`。开发库 `scm` 里有用户自己的数据，不在上面试。
- 测写操作（POST/PUT/DELETE）只用临时数据，测完物理删除；用户的数据只发 GET。
- 前端：`cd web && pnpm install && pnpm dev`。

## 部署

**甲方那边负责部署**（11-05 定）。我们交付一套 Docker 部署包（应用、Nginx、PostgreSQL、Redis）和部署手册；服务器、域名、HTTPS、备份的异地存放由部署方负责。设计见 `docs/rfc/002-交付部署包.md`。

- 打包：`deploy/build-package.sh [版本] [--full] [--arch amd64|arm64]`，包在 `dist/`（gitignore）。要先启动 Docker、起本机开发库（导出 `openapi.json` 要起一次应用），工作区要干净（打的是 HEAD）。第一次安装给 `--full` 的完整包，之后每周给升级包。
- 原样进包的文件在 `deploy/package/`：compose、Nginx 配置、安装 / 升级 / 备份 / 恢复脚本、`部署手册.md`。两个镜像是 `deploy/app.Dockerfile`、`deploy/web.Dockerfile`。
- 部署手册给甲方 IT 看，在公开仓库里：不写任何地址、密钥和甲方的名字。改了部署包的行为，手册一起改。
- 应用这边配合部署的：`GET /api/version`（不用登录，升级脚本靠它判断新版本起来没有）、`GET /api/admin/backup-status`（只给超级管理员，读 `BACKUP_DIR` 下最新的每日备份）；生产环境的日志另写一份到 `/data/logs/scm.log`（安装目录的 `logs/`，按天滚动、留 30 天）。

部署包装好就每天自动备份（backup 容器，`pg_dump`，默认留 7 天，见架构 §12.2）；备份的异地存放由部署方做（手册第 5 节）。

## Git

- GitHub `xiaoliangao/lingmao`，直接提交 main，提交后推送（用户 11-05 定）；不 force push。
- 提交前缀：「功能:」「页面:」「修复:」「框架:」「部署:」「文档:」。
- 提交前先看 `git status`：工作区里有用户的改动时，只 `git commit -- <自己的文件>`。
- **仓库是公开的**。提交前 grep 一遍：服务器地址和端口、密钥、真实账号的密码哈希、甲方的企业名称和联系方式、真实价格和供应商。甲方给的原件和其他私有资料放 `docs/private/`（gitignore）。
