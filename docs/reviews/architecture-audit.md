# lingmao 架构设计与代码实现一致性审查

## 1. 审查结论与基准

| 项目 | 本次结果 |
|---|---|
| 审查日期 | 2026-11-17 |
| 仓库 / 默认分支 | `xiaoliangao/lingmao` / `main`；通过仓库元数据和远端 `HEAD` 核对 |
| 审查基线 | [`34e692b3144d562d885da12270a59b1ec148bcd5`](https://github.com/xiaoliangao/lingmao/commit/34e692b3144d562d885da12270a59b1ec148bcd5) |
| 提交分支 / PR 目标 | `chore/arch-audit-and-review` → `main` |
| 本次变更 | 仅新增本报告，单独一个文档 commit；未修改业务代码、UI、接口、迁移和部署配置 |
| 发现分级 | 未证实 P0；11 项 P1、7 项 P2，另有待确认事项；详见第 5–8 节 |
| 已完成的执行验证 | 前端独立类型检查、生产构建、原型产物一致性、JS/脚本语法检查；部分前端函数逻辑复现 |
| 未完成的执行验证 | 后端编译/单元测试/打包、数据库集成测试、浏览器交互与逐像素验证、部署及恢复演练，原因见第 4 节 |

**总体判断：保留现有模块化单体，优先修复跨路径数据不变量和编辑状态保护。** 当前规模和分层目标见 [docs/架构设计.md:35–50](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L35-L50)、[docs/架构设计.md:150–172](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L150-L172)；主要偏差集中于库存正反向操作、前端全量保存、算料跨域数据契约，证据分别见 P1-01～P1-09。现有 Java 分层、公共单据能力和 TypeScript 严格检查值得保留，不能仅凭若干大文件就推导出需要拆服务或重写 UI。

所有源文件行号均指上述基线，不随报告合入后的行号变化。本文将“文档明确要求而代码遗漏”“多个已支持入口之间的契约缺口”“已明确延后的功能”分开记录。发现中的复现步骤是后续验收用例；除明确标注函数级复现外，均为源码路径推导，未冒充真实服务或数据库实测。

审查最初从 `6142560` 开始；提交前发现默认分支新增 `34e692b`，已完整复核增量并将审查分支快进到该提交。增量仅修改 PRD、数据库设计、进度三份文档：营业执照识别取消、图片迁移后排、换算系数按物料设置（[docs/PRD.md:33](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L33)、[docs/PRD.md:2207](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L2207)、[docs/PRD.md:2231–2236](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L2231-L2236)）。应用源码、依赖锁文件、构建配置和原型均未变化；所有证据行号已对齐最新基线，前述需求调整不作为缺失功能上报。

分级口径：P0 为已证实的全局阻塞、不可控破坏等必须立即停止交付的问题；P1 为可达的数据覆盖、业务不变量失守、关键流程故障或重要架构/安全隐患；P2 为局部缺陷、体验或工程改进。**未证实 P0 不代表已完成后端发布验收。** 后端运行验证缺口在本报告中保留，不以静态阅读代替。

## 2. 文档、原型与范围基准

### 2.1 判定依据与冲突处理

| 层次 | 采用的依据 | 使用方式 |
|---|---|---|
| 业务需求 | [CLAUDE.md:7–15](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L7-L15)、`docs/PRD.md`、`docs/甲方确认清单.md` | 以 PRD 更新记录和已确认答复解释业务；旧清单空项不直接当成当前未决 |
| 架构 / 表结构 | [docs/架构设计.md:150–172](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L150-L172)、[docs/数据库设计.md:5–8](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%95%B0%E6%8D%AE%E5%BA%93%E8%AE%BE%E8%AE%A1.md#L5-L8) | 跨域调用、事务、表归属按架构；已经落地的字段同时核对 Flyway 迁移 |
| 模块细则 | [docs/rfc/README.md:5–11](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/README.md#L5-L11)、RFC 001–009 | 同时读取实施记录；已实现接口不能只按早期 RFC 草案签名判断 |
| 前后端契约 | [docs/api/README.md:5–10](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L5-L10)、API 01–06 | 类型、字段、状态、错误码与 `web/src/types` / `web/src/api` / 后端 DTO 对照 |
| 视觉与交互 | [CLAUDE.md:48–62](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L48-L62)、`docs/原型/index.html`、`docs/原型/src/` | 对照页面结构、弹层、悬浮联动、状态和缓存行为；采用 API 文档已批准的原型调整 |
| 实施阶段 | [docs/进度.md:159–180](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E8%BF%9B%E5%BA%A6.md#L159-L180)、[docs/api/README.md:183–192](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L183-L192) | 生产、看板等尚在前端先行阶段；不能把所有计划接口当成本次实现遗漏 |

完整阅读/核对范围包括根目录说明、`docs/` 下 23 份 Markdown、API 01–06、RFC 001–009、数据库设计、甲方确认清单，以及 HTML 原型和全部业务原型源文件。第三方 `vendor/vue.global.prod.js` 作为依赖进行语法/产物检查，未进行第三方源码逐行安全审计。

### 2.2 当前边界、数据流与交互要求

| 边界 / 流程 | 基准要求 | 实现入口与本次关注点 |
|---|---|---|
| 主数据 → 款式 / SKU | 主数据被引用后不能硬删；启用颜色 × 尺码生成 SKU | [CLAUDE.md:37](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L37)；[src/main/java/com/lingmao/scm/module/style/service/impl/StyleServiceImpl.java:377–395](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/style/service/impl/StyleServiceImpl.java#L377-L395)；关注新增尺码后的资料包可用性（P1-08） |
| 款式 → 资料包 → 订单 | 生产 BOM 生效时配齐料，订单提交检查、最终审核锁版本 | [docs/api/01-资料包.md:185–198](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L185-L198)、[docs/api/02-需求订单.md:237–241](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L237-L241)；[src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:424–460](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java#L424-L460) |
| 订单 → 算料 → 采购 | 订单状态、BOM 展开、库存/在途和软占用参与计算 | [docs/PRD.md:1247–1255](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L1247-L1255)；[src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java:63–74](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java#L63-L74)、[src/main/resources/mapper/purchase/MrpCalcMapper.xml:49–74](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/resources/mapper/purchase/MrpCalcMapper.xml#L49-L74)；关注领域契约（P1-09） |
| 采购 → 库存 → 源单累计 | 单据状态、库存、库存日志、源单回写在同一事务；净入库有上下界 | [docs/架构设计.md:500–522](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L500-L522)、[docs/api/04-库存.md:198–218](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L198-L218)；[src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:558–592](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java#L558-L592)；关注双向操作（P1-01/02） |
| 公共单据能力 | 状态动作、审批、引用、附件、变更提醒经统一入口；后端权限兜底 | [CLAUDE.md:42–44](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L42-L44)、[docs/api/README.md:98–136](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L98-L136)；[src/main/java/com/lingmao/scm/module/biz/service/DocActions.java:13–24](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/biz/service/DocActions.java#L13-L24)、[src/main/java/com/lingmao/scm/module/biz/service/impl/DocCommonServiceImpl.java:32–66](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/biz/service/impl/DocCommonServiceImpl.java#L32-L66) |
| 列表 / 整页编辑 | 列表和导出同筛选；全量保存；1002 冲突恢复；切页签保留编辑 | [docs/api/README.md:74–82](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L74-L82)、[docs/api/README.md:132](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L132)、[CLAUDE.md:53](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L53)；[web/src/composables/useTable.ts:54–95](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/composables/useTable.ts#L54-L95)；关注数据映射、异步失败和 KeepAlive（P1-03～06、P2-03） |

以下差异有明确批准依据，**不列为缺陷**：

- 订单编辑采用整页、变更单增加独立详情：[docs/api/02-需求订单.md:422–425](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L422-L425)。
- 工艺标准由资料包打开抽屉：[docs/api/01-资料包.md:38–43](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L38-L43)；BOM 不做图上编号，但仍应有行联动料卡：[docs/api/01-资料包.md:317](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L317)。
- BOM 版本对比后做：[docs/api/01-资料包.md:366–367](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L366-L367)；手机报工独立外框和跟单员范围：[docs/api/05-生产.md:36–38](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/05-%E7%94%9F%E4%BA%A7.md#L36-L38)、[docs/api/05-生产.md:107–111](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/05-%E7%94%9F%E4%BA%A7.md#L107-L111)。
- 原型批注、意见汇总不进真实系统：[CLAUDE.md:62](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L62)；TRADE、二期/待定模块不据原型直接认定遗漏：[docs/api/README.md:38](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L38)。
- 生产、生产看板、手机报工、首页后端尚未完成：[docs/进度.md:178–180](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E8%BF%9B%E5%BA%A6.md#L178-L180)。开发 mock 仅在 DEV 开关下装载：[web/src/main.ts:64–67](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/main.ts#L64-L67)。前端构建成功不等于这些计划功能已可端到端交付。

## 3. 全仓实现盘点与架构评价

### 3.1 覆盖清单

基线共 1,044 个跟踪文件。主 Java 代码 619 个文件，测试 Java 123 个文件；`web/src` 共 108 个 Vue、58 个 TypeScript 文件，另读 Vite/TypeScript/包配置和样式；24 份 SQL 迁移、14 份 Mapper XML、11 份 shell 脚本。原型包含 19 个业务 JS、1 个第三方 Vue JS 和 `check.mjs`。计数来自基线 `git ls-files`，不含安装依赖、构建产物或本报告。

| 模块 | 已审查内容与定位 | 结果 / 关联发现 |
|---|---|---|
| basic（80 Java） | 基础资料 Controller/Service/DTO/实体/Mapper、Excel；`src/main/java/com/lingmao/scm/module/basic/`；RFC 003 | 追加尺码、物料导入：P1-08、P2-01 |
| supplier（44 Java） | 准入/变更/证照/导入导出、引用校验；`src/main/java/com/lingmao/scm/module/supplier/`；RFC 004 | 核对状态、共享字典、外键/引用与旧值回显；未把停用供应商后保留工厂仓当成缺陷 |
| style（33 Java） | 款式主档、颜色/SKU、Excel；`src/main/java/com/lingmao/scm/module/style/`；RFC 005 | SKU 增量生成与资料包覆盖契约：P1-08 |
| price（20 Java） | 当前价、记价、作废、查询/导出；`src/main/java/com/lingmao/scm/module/price/`；RFC 006 | 核对新增实施记录；旧架构文字需要澄清：P2-06 |
| techpack（80 Java） | 版本、尺寸、BOM、成本、发布检查、PDF 模板；`src/main/java/com/lingmao/scm/module/techpack/`；API 01 | 成本全量保存、尺码覆盖、BOM 交互：P1-04/08、P2-05 |
| order（43 Java） | 订单/变更、审核、导入导出、数据范围；`src/main/java/com/lingmao/scm/module/order/`；API 02 | 编辑往返丢字段、非 SKU 变更详情、导出：P1-03/06/07 |
| purchase（72 Java） | 算料、软占用/在途、采购/跟进、源单 Handler；`src/main/java/com/lingmao/scm/module/purchase/`；API 03 | 锁顺序、逆向上限、读契约、包装量：P1-01/02/09、P2-02 |
| inventory（66 Java） | 出入库、盘点、库存/日志、过账、源单接口；`src/main/java/com/lingmao/scm/module/inventory/`；API 04 | 正反向事务与盘点编辑：P1-01/02/05/06 |
| system（79 Java） | 登录、权限、用户/角色/菜单/字典、文件、日志、版本、备份状态；`src/main/java/com/lingmao/scm/module/system/`；RFC 001/002 | 核对生产配置、鉴权、签名 URL、日志脱敏；登录回跳见 P1-10 |
| biz（48 Java） | 多级审批、附件、引用、单据头/日志/提醒/动作；`src/main/java/com/lingmao/scm/module/biz/`；架构 §4 | 保留现有统一服务边界；待确认源单权限范围见第 8 节 |
| common / config / security / 启动（54 Java） | 状态 CAS、数字、Excel、异常、流水号、文件签名、MyBatis、JWT、DataScope | 数量舍入 P2-02；其余关键保障见 3.2 |
| 前端全部实现 | `web/src/{api,types,views,components,composables,stores,router,utils,layout,styles}`、mock、入口/指令/配置 | 全部脚本/模板语义逐文件审阅；全局样式全文阅读，组件/页面样式做静态规则扫描；问题详见 P1-03～06/10、P2-03～05/07 |
| SQL / 测试 | `src/main/resources/db/migration/`、`src/main/resources/mapper/`、`src/test/` | 迁移和 XML 全读；123 个测试文件盘点，重点核验领域/并发/权限/本次缺陷相关用例，未声称全部测试逐行阅读或执行通过 |
| 原型 / 工程 / 部署 | `docs/原型/`、`pom.xml`、Maven Wrapper、`web/package.json`、`deploy/`、`dev/` | 原型源码/产物对照；构建、配置隔离、备份/升级脚本静态检查；P1-11、P2-06/07 |

### 3.2 已具备的保障及其适用范围

- **状态和事务有统一机制。** [src/main/java/com/lingmao/scm/common/doc/StatusCas.java:41–58](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/doc/StatusCas.java#L41-L58) 使用条件更新；[src/main/java/com/lingmao/scm/module/biz/service/DocActions.java:85–96](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/biz/service/DocActions.java#L85-L96) 收敛动作权限；[src/main/java/com/lingmao/scm/common/mybatis/Updates.java:32–63](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/mybatis/Updates.java#L32-L63) 实现空值覆盖规则。对应 [CLAUDE.md:35–44](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L35-L44)。这些机制能防同一单据重复动作，但不自动解决不同单据的跨表锁顺序（P1-01）。
- **权限没有只依赖前端。** [src/main/java/com/lingmao/scm/security/JwtAuthFilter.java:44–65](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/security/JwtAuthFilter.java#L44-L65)、[src/main/java/com/lingmao/scm/security/DataScope.java:78–115](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/security/DataScope.java#L78-L115)、[src/main/java/com/lingmao/scm/module/biz/service/impl/DocCommonServiceImpl.java:32–66](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/biz/service/impl/DocCommonServiceImpl.java#L32-L66) 提供会话/数据范围/通用单据守卫；与 [CLAUDE.md:43](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L43) 对齐。当前审批人跨 SELF 范围的例外已经在 [src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:486–503](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java#L486-L503) 和 [src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java:356–370](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java#L356-L370) 实现，不报为缺失。
- **文件和生产配置已有隔离。** [src/main/java/com/lingmao/scm/common/file/FileUrlSigner.java:55–113](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/file/FileUrlSigner.java#L55-L113)、[src/main/java/com/lingmao/scm/module/system/service/impl/FileServiceImpl.java:49–108](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/system/service/impl/FileServiceImpl.java#L49-L108)、[src/main/resources/application-prod.yaml:9–17](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/resources/application-prod.yaml#L9-L17) 分别提供签名/路径处理、文件检查和生产 Swagger/SQL 日志关闭；对应 [CLAUDE.md:38](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L38)、[docs/rfc/002-交付部署包.md:137–146](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/002-%E4%BA%A4%E4%BB%98%E9%83%A8%E7%BD%B2%E5%8C%85.md#L137-L146)。本次没有执行线上访问测试、完整 secret 扫描或依赖 CVE 审计，不能据此给出“绝对安全”结论。
- **类型与分层有基础门禁。** [web/tsconfig.app.json:13–18](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/tsconfig.app.json#L13-L18) 启用 strict/noUnused，实际类型检查通过；业务 TS 未发现普遍滥用 `any` / `@ts-ignore`。[web/src/utils/request.ts:16](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/utils/request.ts#L16) 的 Axios 类型合并例外不直接作为业务类型缺陷。[src/test/java/com/lingmao/scm/ArchitectureTest.java:83–97](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/ArchitectureTest.java#L83-L97) 和 [src/test/java/com/lingmao/scm/TableOwnerTest.java:49–59](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/TableOwnerTest.java#L49-L59) 分别约束 Java 依赖和 SQL 写表归属。源码 import 静态建图未发现域间环、跨域 Mapper/实现类直接导入或前端字面量运行时 import 环；这不涵盖动态装载、依赖注入、SQL 业务读取，也不代表 ArchUnit 已执行通过（P1-09）。

## 4. 工具链验证记录

以下命令在未改动业务源码的审查分支执行，执行时提交为 `6142560`；随后到 `34e692b` 仅有第 1 节列出的文档增量，已用 Git 对比确认应用源码、依赖和构建配置相同。前端环境为 Node 24.22.1 / npm 11.9.0 / pnpm 11.25.0；后端由 Wrapper 下载 Maven 3.9.16，项目要求 Java 21（[pom.xml:30](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/pom.xml#L30)）。临时日志和环境设置不进 PR，关键结果保留如下，便于复核。

| 检查 | 实际执行 / 结果 | 状态 |
|---|---|---|
| 前端安装 | `cd web && pnpm install --frozen-lockfile`；依赖已安装，随后因环境策略拒绝 `esbuild@0.28.2` 构建脚本，`ERR_PNPM_IGNORED_BUILDS`，退出 1 | **环境受限无法验证**（该安装命令未完整成功） |
| 前端类型 | `cd web && ./node_modules/.bin/vue-tsc -b`，退出 0；此前 `pnpm exec vue-tsc -b` 被同一安装策略中断，未到类型检查 | **通过**（直接执行已安装的同一检查器） |
| 前端生产构建 | `cd web && npm run build`，即仓库脚本 `vue-tsc -b && vite build`（[web/package.json:9](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/package.json#L9)）；退出 0，Vite 7.3.7，12.37 秒；生成 `web/dist/index.html` 和资源，共 172 个文件 | **通过** |
| 前端 lint / 测试入口 | [web/package.json:6–10](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/package.json#L6-L10) 未配置 lint/test 脚本；未以其他命令冒充这两个门禁 | **未配置，不记为通过** |
| 后端单元测试命令 | `./mvnw -B -ntp test` 首次受 Maven Central 解析限制；使用临时环境代理设置重试，已进入 619 个源文件编译，随后失败；单元测试未执行 | **环境受限无法验证** |
| 后端打包 | `./mvnw -s <临时 Maven 设置文件> -B -ntp package`，同样到编译阶段失败；未生成可用应用 JAR | **环境受限无法验证** |
| 后端集成测试 / 迁移 | 未运行 `verify`；没有 Docker/Podman、PostgreSQL、Redis。项目要求本机测试库/缓存：[CLAUDE.md:66–71](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L66-L71)、[src/test/resources/application-it.yaml:6–19](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/resources/application-it.yaml#L6-L19) | **环境受限无法验证** |
| 原型产物 | `check.mjs` 重建到临时路径；与跟踪的 `docs/原型/index.html` 逐字节相同，1,371,703 字节；SHA-256 为 `77376a4d9843b3c636c9a4bd9b0838e90c698d0756dc503d1b04a5056a675831` | **通过**（产物一致性） |
| 原型浏览器自测 | `CHROME=/usr/bin/chromium OUT=<临时文件> node docs/原型/check.mjs`；Chromium 启动时报 `socket() Operation not permitted` / ptrace 限制，脚本退出 1，未跑完页面自测 | **环境受限无法验证** |
| 语法 | 11 份 `.sh` 分别执行 `bash -n`；20 个原型 `.js` 和 `check.mjs` 分别执行 `node --check`；全部退出 0 | **通过**（仅语法） |
| 特定前端逻辑 | 提取源函数后在 Node/TS 中复现 SKU 映射、成本失败默认值、盘点 hydration、跨时区日期，详见对应发现 | **已复现问题**；不是浏览器或后端集成测试 |
| 部署 / 升级 / 恢复 / 性能 | 静态阅读脚本，未部署、未执行恢复、未连接真实业务环境、未做压测或实际 PDF 渲染 | **环境受限无法验证运行结果** |

后端两次进入编译的关键输出一致：

```text
Compiling 619 source files with javac [debug parameters release 21]
BUILD FAILURE
Fatal error compiling: error: release version 21 not supported
```

虽然 `java` 显示 21.0.13，此环境没有可用 `javac` 可执行文件且缺少该 JDK 的 `lib/ct.sym`，编译器帮助中的 Supported releases 为空。该证据支持“当前 Java 工具链不完整”，不能认定是仓库源码编译错误，也不能认定后端原始构建通过。未通过降 Java 版本、跳测试或更改 `pom.xml` 来制造绿灯。

建议在完整 JDK 21 和本机 `dev/compose.yml` 环境补跑 `./mvnw -B -ntp test`、`./mvnw -B -ntp package`、`./mvnw -B -ntp verify`，PDF 集成测试先执行 `dev/fetch-font.sh`。[src/test/java/com/lingmao/scm/IntegrationTestConfig.java:28–43](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/IntegrationTestConfig.java#L28-L43) 会清空并重建测试库，仅能指向名称以 `_test` 结尾的隔离库。验证限于代码审查所需，不使用真实业务库。

## 5. P0 阻塞缺陷

**本次没有证实 P0。** 当前最严重的已定位问题列为 P1；其触发条件和实际验证层级逐项列出。后端编译、集成测试和原型浏览器验证仍未完成，见第 4 节，不能用这项分级替代发布门禁。

## 6. P1 架构隐患与关键缺陷

### P1-01｜库存正反向操作及 MRP 重算没有统一跨表锁顺序

**依据。** [docs/架构设计.md:500–522](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L500-L522)、[docs/api/04-库存.md:202–214](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L202-L214) 要求排序防死锁，并在同一事务完成过账/回写。库存键内部排序已经实现，但源单与库存之间的顺序相反。

**证据。** [src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:569–574](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java#L569-L574) 审核先 `handler.check` 后 `ledger.post`；[src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:110–115](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L110-L115)、[src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:210–215](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L210-L215) 先锁采购单；[src/main/java/com/lingmao/scm/module/inventory/service/impl/StockLedger.java:95–100](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/impl/StockLedger.java#L95-L100) 再更新库存。反审核在 [src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:588–590](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java#L588-L590) 先更新库存，再到 [src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:176–177](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L176-L177) 锁采购单。

**最短交错场景（静态推导，未运行 PostgreSQL）。** 同一采购单、仓库、SKU 有已审核入库 A 和草稿入库 B，数量足够使单独操作合法：

| 时序 | 事务 T1：审核 B | 事务 T2：反审核 A |
|---|---|---|
| 1 | 持有采购单行锁 | — |
| 2 | 暂停于库存更新前 | 持有库存行锁 |
| 3 | 等待 T2 的库存行锁 | 等待 T1 的采购单行锁 |

形成锁环后其中一个事务会被数据库中止，用户操作失败；本项没有把事务回滚说成数据损坏。另一个同根因路径在 [src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpServiceImpl.java:760–765](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpServiceImpl.java#L760-L765)、[src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpServiceImpl.java:835–857](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpServiceImpl.java#L835-L857)：只预锁本次占用在途对应的采购单，重算移交旧采购行时可能先 `moveCalcItem` 锁明细、再 `bumpVersions` 锁单头；收货在 [src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:177–193](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L177-L193) 则为单头→明细。旧采购单不在 `uses` 预锁集合时也存在交错窗口。

**改法示例。** 单独设计源单处理接口的 `lockAndValidate(posting, direction)`，统一“源单头（按 ID）→源明细（按 ID）→库存键”的层级；MRP 确认前收集此次会移交的旧采购单一起按序锁定。具体锁模式应在事务矩阵中明确，不能把正向收货上限检查原样复用于反审核，也不能只加任意重试掩盖倒序。

**验收 / 任务 T01。** 用两事务 barrier 强制上述交错，覆盖审核/反审核、重算/收货；断言无死锁、每张单仅过账一次，库存日志与源单累计一致。现有 [src/test/java/com/lingmao/scm/module/inventory/service/impl/InventoryIT.java:168–190](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/inventory/service/impl/InventoryIT.java#L168-L190) 仅覆盖普通出库的库存键顺序；[src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java:208–219](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java#L208-L219) 是顺序重算，不能代替该测试。本项涉及核心事务，后续独立 PR。

### P1-02｜采购退货反审核可使净入库超过采购上限

**依据。** [docs/api/04-库存.md:296](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L296) 定义净入库不得超过采购量 × 1.05；[docs/架构设计.md:522–524](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L522-L524) 要求源单回写与库存同事务。接口文档列举了建草稿、改草稿、审核的检查，但没有把反审核增加净入库的路径闭合起来，本项属于双向操作的不变量缺口。

**证据。** [src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:187–193](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L187-L193) 计算反向 `delta` 后只检查 `received - returned >= 0`；[src/main/java/com/lingmao/scm/module/purchase/mapper/PurchaseOrderItemMapper.java:30–33](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/mapper/PurchaseOrderItemMapper.java#L30-L33) 直接累加。正向 `check` 的 105% 上限并未在反审核入口执行，入口见 [src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:588–590](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java#L588-L590)。

**最短场景（静态推导）。** 采购量 100：入库 100 → 退货 50 → 再入库 50，净入库仍为 100；此时反审核那张退货单，累计变成 `received=150, returned=0`，净入库 150，大于上限 105，库存也增加 50。采购单在 DONE/RUNNING 间的转换可达，且未结案，因此现有状态检查不阻止该序列。

**改法示例。** 在源单锁内按采购行合并本次数量，统一校验操作后的 `0 <= nextReceived - nextReturned <= maxReceived`，对逆向超限返回结构化业务错误；检查失败时单据状态、库存、日志、累计全部回滚。若业务希望反审核例外，必须先明确如何处理已补收数量，不能让上限仅对某个入口生效。

**验收 / 任务 T02。** 上述序列的最后一步被拒且无持久化副作用；合法的先撤销补收、再撤销退货等路径仍可完成。[src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandlerTest.java:68–114](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandlerTest.java#L68-L114) 已测正向超收和负净入库，但缺这个逆向上限序列。与 T01 共用锁/方向约定，分开测试与提交。

### P1-03｜订单编辑将合法逐 SKU 字段压缩，保存其他字段也会改写明细

**依据。** [docs/api/02-需求订单.md:224–225](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L224-L225) 规定逐 SKU 整单覆盖，同时将 UI 价格/交期/备注收敛到款或尺码；[docs/api/02-需求订单.md:273–278](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L273-L278) 又允许 Excel 一行一个 SKU，独立单价和行交期。这是入口之间的表达能力冲突，不是简单判定页面没照文档写。

**证据。** [web/src/views/order/order/form.vue:155–167](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/form.vue#L155-L167) 以 `sizeCode` 覆盖价格，交期/备注取第一行；[web/src/views/order/order/form.vue:190–196](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/form.vue#L190-L196) 将压缩值重新写到所有 SKU。后端 [src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:663–689](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java#L663-L689) 接收逐 SKU 的三个字段；[src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:1000–1008](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java#L1000-L1008) 导入时保留每行价格和交期。Excel 没有独立备注列，备注差异的合法输入来源是 API，不能把它说成 Excel 字段。

**函数级复现。** 从源文件提取分组代码及 `priceOf` / `buildItems`，对同款同尺码的红/蓝两行输入价格 10/20、交期 12-01/12-02，不修改明细直接再序列化（模拟仅改单头的保存），得到价格 20/20、交期 12-01/12-01；已有不同备注也被改成首行备注。此处已执行的是 DTO 转换，不是完整 Vue/HTTP 测试。

**改法示例。** 保留 `Map<styleSkuId, OriginalFields>` 与显式修改标记；未改动的 SKU 字段原样往返，用户主动修改款级/尺码级输入时才批量展开。混合值应显示为“多个值”等明确状态。另一方案是收紧导入/API 为同款同码统一字段，但会改变现有契约，属于 **[待确认]** 业务规则，不能在本次审查中替用户决定。

**验收 / 任务 T03。** 导入/API 建单后，只改单头或打开后原样保存，逐 SKU 价格、交期、备注保持不变；显式批量编辑仍按用户输入覆盖；清空字段和混合值都有用例。先修数据往返，不重做录单界面。

### P1-04｜资料包成本加载失败被当成空数据，普通保存会清掉已有成本

**依据。** [docs/api/01-资料包.md:148–151](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L148-L151) 将详情和核价分开加载；[docs/api/01-资料包.md:167–173](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L167-L173) 定义整版覆盖，以及 `cost=null` 时后端保留核价。

**证据。** [web/src/views/style/style/TechPackTab.vue:145–170](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/TechPackTab.vue#L145-L170) 捕获成本请求错误后设 `costData=null`，仍调用 `hydrateDraft`；[web/src/views/style/style/TechPackTab.vue:138–142](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/TechPackTab.vue#L138-L142) 填入 `OUTSOURCE / null / []`；[web/src/views/style/style/TechPackTab.vue:307–313](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/TechPackTab.vue#L307-L313) 只按成本权限决定是否发送完整成本对象。后端 [src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java:253–279](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java#L253-L279) 对非空 cost 覆盖字段并重写子表；[src/main/java/com/lingmao/scm/module/techpack/service/impl/PackContent.java:128–129](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/techpack/service/impl/PackContent.java#L128-L129) 删除旧费用，[src/main/java/com/lingmao/scm/module/techpack/service/impl/PackContent.java:202–209](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/techpack/service/impl/PackContent.java#L202-L209) 按请求重插。

**函数级复现与触发条件。** 用户有成本权限，资料包草稿已有 BUY 单价及费用；详情请求成功、成本请求失败。将失败分支的 null 注入原 `hydrateDraft`，修改其他内容后调用原 `buildSaveForm`，实际得到 `cost={costMode:'OUTSOURCE', buyPrice:null, items:[]}`。失败请求到该分支的调用链由源码确认；未调用真实保存接口破坏数据。

**改法示例。** 将未授权、未加载、加载成功、加载失败分开：`CostLoad = {state:'ready', packId, data} | {state:'loading'|'error'|'forbidden'}`。只有当前版本成本成功加载后才允许序列化完整成本；失败时用现有 null 保留语义保存其他区域，或明确阻止保存并提供重试。异步结果还要校验当前版本，避免迟到结果污染正在编辑的成本。

**验收 / 任务 T04。** 成本 GET 失败时修改尺寸/工艺/单头，持久化成本完全不变；无成本权限仍保留；正常加载后明确清空/修改仍有效；切版本和延迟响应不串数据。采用最小状态修复，不改变成本计算规则。

### P1-05｜盘点草稿切回页签会被自动刷新覆盖

**依据。** [CLAUDE.md:48–53](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L48-L53) 规定 KeepAlive 页签和未保存保护；切页签不询问不等于可以丢弃草稿。[docs/api/04-库存.md:430–432](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L430-L432) 要求盘点详情作为表单，保存或提交前保存。

**证据。** [web/src/views/inventory/count/detail.vue:73–89](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/inventory/count/detail.vue#L73-L89) 的 `syncFromDetail` 覆盖单头/明细并 `markSaved()`；[web/src/views/inventory/count/detail.vue:97–108](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/inventory/count/detail.vue#L97-L108) 每次非首次激活都刷新，未判断 dirty。故缓存组件保留了，草稿却被新请求覆盖。

**函数级复现。** 原同步函数接收服务端实盘 10，将内存未保存的 18 覆盖成 10，并将 guard 标为已保存。切到其他业务页签再切回触发该函数的路径来自静态调用链；未声称已完成浏览器点击测试。

**改法示例。** `onActivated` 对干净详情正常刷新；对 dirty 的可编辑草稿保留数据，如需同步状态/版本，单独获取并展示冲突提示，让用户明确保存、放弃或重载。不能为刷新最新版本直接覆盖正在编辑的整个对象。

**验收 / 任务 T05。** 单头、实盘数量、行备注、删除行在切页返回后都保留；干净页面能更新，明确放弃后能重载；关闭页签、刷新、退出的保护仍有效。与 P2-03 共用编辑生命周期设计。

### P1-06｜订单、采购、盘点的导出缺少列表状态条件

**依据。** [docs/api/README.md:74](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L74)、[docs/api/02-需求订单.md:281–283](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L281-L283)、[docs/api/04-库存.md:378–380](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L378-L380) 明确导出与列表使用相同筛选。原型也把同一过滤结果交给列表和导出：[docs/原型/src/5b-order.js:39–57](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5b-order.js#L39-L57)、[docs/原型/src/5c-material.js:283–295](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5c-material.js#L283-L295)、[docs/原型/src/5c-material.js:853–860](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5c-material.js#L853-L860)；生成页对应 [docs/原型/index.html:6822–6840](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L6822-L6840)、[docs/原型/index.html:7578–7590](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L7578-L7590)、[docs/原型/index.html:8148–8155](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L8148-L8155)。

**证据。** [web/src/composables/useTable.ts:54–63](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/composables/useTable.ts#L54-L63) 缓存映射前的查询对象，[web/src/composables/useTable.ts:89–95](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/composables/useTable.ts#L89-L95) 直接将它用于下载；三个页面仅在 fetch 内把 `statusTab` 转成后端 `status`：

| 页面 | 查询映射位置 | 导出入口 |
|---|---|---|
| 需求订单 | [web/src/views/order/order/index.vue:83–99](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/index.vue#L83-L99) | [web/src/views/order/order/index.vue:243](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/index.vue#L243) |
| 采购单 | [web/src/views/purchase/order/index.vue:59–73](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/purchase/order/index.vue#L59-L73) | [web/src/views/purchase/order/index.vue:153](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/purchase/order/index.vue#L153) |
| 盘点单 | [web/src/views/inventory/count/index.vue:54–66](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/inventory/count/index.vue#L54-L66) | [web/src/views/inventory/count/index.vue:142](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/inventory/count/index.vue#L142) |

**静态请求推导。** 状态选 PENDING，列表发 `status=PENDING`；页头导出却发 `statusTab=PENDING`。后端按 status 筛选，导出范围因此扩大到其他状态。这里只判定筛选不一致，没有据此认定绕过后端数据权限。

**改法示例。** 在共享表格边界增加有类型的 `toApiQuery`，列表和导出复用同一映射；成功展示后缓存已映射查询，导出使用该快照并去掉分页、合并所选 ids。不要分别在三个导出按钮复制临时修补。

**验收 / 任务 T06。** 三个页面各状态、日期/关键字/人员组合、ids 勾选导出均与可见筛选一致；修改筛选但未查询时仍导出正在展示的结果；全状态和空列表不回归。

### P1-07｜已审核/作废的非 SKU 订单变更详情触发空指针

**依据。** [docs/api/02-需求订单.md:182](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L182) 定义变更单详情，[docs/api/02-需求订单.md:425](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L425) 明确独立详情页；[docs/数据库设计.md:1474–1485](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%95%B0%E6%8D%AE%E5%BA%93%E8%AE%BE%E8%AE%A1.md#L1474-L1485) 允许交期、价格、资料包变更的 `style_sku_id` 为空。

**证据。** [src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:205–206](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java#L205-L206) 对非草稿/待审核变更使用 `Map.of()`；[src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:221–223](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java#L221-L223) 不判断空 SKU 就调用 `get`。同文件 [src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:377–409](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java#L377-L409) 正常创建 DUE/TECH_PACK/PRICE 的非 SKU 行。Java 的该不可变空 Map 不接受 null 查询键，因此这类变更在审核/作废后取详情会抛 NPE；草稿时使用的 HashMap 接受 null，导致此前能正常打开。

**静态复现场景。** 新建只改交期、价格或资料包版本的变更单 → 审核或作废 → 获取变更详情。对应行的 SKU 本就应该为空，不能通过把它改成必填规避。

**改法示例（后续小修复）。**

```java
OrderItem row = ci.getStyleSkuId() == null
        ? null
        : bySku.get(ci.getStyleSkuId());
```

**验收 / 任务 T07。** DUE/PRICE/TECH_PACK × APPROVED/VOIDED 参数化详情测试，`scheduledQty` 对非 SKU 行保持 null；数量/配色变更正常。现有 [src/test/java/com/lingmao/scm/module/order/service/impl/OrderIT.java:186–205](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/order/service/impl/OrderIT.java#L186-L205) 只在审核前调用变更详情，审核后看订单/列表；补上这个具体缺口。本次后端构建无法验证，故即使改动小也只提供方案，不混入未经完整构建的代码 commit。

### P1-08｜尺码增长后旧 BOM 覆盖不足，订单审核与算料门禁脱节

**依据。** [docs/rfc/003-基础资料.md:104–105](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/003-%E5%9F%BA%E7%A1%80%E8%B5%84%E6%96%99.md#L104-L105) 允许尺码组追加；[docs/api/01-资料包.md:191–193](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L191-L193) 要求按尺码配料和分码用量完整；[docs/api/02-需求订单.md:241](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L241) 的订单门禁却只写颜色。问题是多个已支持操作组合后的契约缺口，不将订单代码准确照 API 检查颜色说成单独违反该条。

**证据。** [src/main/java/com/lingmao/scm/module/basic/service/impl/SizeGroupServiceImpl.java:88–103](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/basic/service/impl/SizeGroupServiceImpl.java#L88-L103) 允许追加；[src/main/java/com/lingmao/scm/module/style/service/impl/StyleServiceImpl.java:382–395](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/style/service/impl/StyleServiceImpl.java#L382-L395) 按当前尺码生成新 SKU；[src/main/java/com/lingmao/scm/module/techpack/dto/PackBrief.java:13–17](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/techpack/dto/PackBrief.java#L13-L17)、[src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java:389–415](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java#L389-L415) 只暴露颜色覆盖；[src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:428–460](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java#L428-L460) 仅据此做提交/审核检查。直到 [src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java:136–148](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java#L136-L148) 才发现缺该尺码配料/用量。

**静态复现场景。** S/M 的生产 BOM 生效，包含 BY_SIZE/BY_COLOR_SIZE 配料或 BY_SIZE 用量 → 尺码组追加 L → 生成 L SKU → L 订单可以通过现有提交/审核 → 算料失败。并非所有 BOM 都受影响，统一配料且统一用量的行不因追加尺码必然失效。

**改法示例。** 由资料包域提供指定 `packId + styleSkuIds` 的完整性结果，至少包含颜色×尺码配料、分码用量及可读缺项；订单提交、每级审核、变更共用。既有订单继续使用锁定版本，新增需求在缺料时提示修订生产 BOM，不能自动把所有旧订单切到最新版本。

**验收 / 任务 T08。** 上述序列在订单提交阶段就列出具体缺项；旧 S/M 订单不受影响；新 L 版配齐并生效后可审核、算料。[src/test/java/com/lingmao/scm/module/techpack/service/impl/PublishChecksTest.java:29–43](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/techpack/service/impl/PublishChecksTest.java#L29-L43) 已有发布时缺用量测试，但不足以覆盖“发布后追加尺码”的跨域时序。先同步 API/DTO 文档，再分模块实现。

### P1-09｜算料跨域 SQL 承担业务判断，超出文档允许的读边界

**依据。** [docs/架构设计.md:153–157](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L153-L157) 允许跨域 JOIN 取显示名称/编码，但业务状态、数量判断必须调用对方 Service；[docs/架构设计.md:170](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L170) 对跨域聚合指定 board 例外。

**证据。** [src/main/resources/mapper/purchase/MrpCalcMapper.xml:49–74](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/resources/mapper/purchase/MrpCalcMapper.xml#L49-L74) 读取订单状态、订单数量和 BOM 配料/用量，[src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java:64–73](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java#L64-L73) 直接用查询结果决定订单能否算料；[src/main/resources/mapper/purchase/SupplyMapper.xml:9–15](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/resources/mapper/purchase/SupplyMapper.xml#L9-L15) 汇总 `inv_stock.qty` 参与可用量计算。这些不只是显示字段。两个 XML 文件第 4 行却将它们描述为符合架构规则 4。

**影响。** 订单状态、BOM 就绪条件和库存模型修改时，需要同时修改 purchase 的 SQL；上游 Service 新增的条件不自动进入算料。P1-08 展示了跨域就绪检查不一致的具体后果。没有证据表明这段 SQL 写错其他域的表，不能把“业务读越界”夸大成“跨域写表”。

**改法与决策示例。** 优先保留单体，以 Order Service 提供可算订单快照、TechPack Service 提供所需 SKU 的需求展开、库存域 StockService 提供批量实存快照；purchase 继续扣除软占用并计算可用库存/在途，保持 [docs/架构设计.md:589–619](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L589-L619) 的职责。现有实存接口见 [src/main/java/com/lingmao/scm/module/inventory/service/StockService.java:20–21](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/service/StockService.java#L20-L21)。保证批量查询，避免改成逐行远程/数据库调用。若团队选择保留高性能跨域读模型，则需正式修改架构，列明只读表/字段、权限和一致性语义、所有者及版本变更责任，不能继续称它符合“只取显示字段”的旧规则。

**验收 / 任务 T09。** 先形成一份边界决策，再选择一条算料链路迁移；相同 fixture 的需求/占用结果不变，上游状态/尺码规则变化有契约测试，数据范围策略见 Q1。补充跨域业务读清单或显式例外审查。[src/test/java/com/lingmao/scm/ArchitectureTest.java:88–97](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/ArchitectureTest.java#L88-L97) 检查 Java 依赖；[src/test/java/com/lingmao/scm/TableOwnerTest.java:55–59](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/TableOwnerTest.java#L55-L59)、[src/test/java/com/lingmao/scm/TableOwnerTest.java:259–264](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/TableOwnerTest.java#L259-L264) 明确只查写表，均不能证明这些业务 SELECT 符合架构。后续独立重构 PR，不在本次搬模块。

### P1-10｜登录回跳直接信任查询参数

**依据与性质。** [docs/PRD.md:2078](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L2078) 定义鉴权/HTTPS 安全目标，[docs/api/README.md:46](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L46) 定义失效会话回登录。本项是安全工程隐患；文档没有明文写“仅允许同源回跳”，不伪造这项条款。

**证据。** [web/src/views/login/index.vue:26–28](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/login/index.vue#L26-L28) 成功登录后直接将 `route.query.redirect` 类型断言成 string 再赋给 `location.href`；[web/src/router/index.ts:16](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/router/index.ts#L16)、[web/src/router/index.ts:151](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/router/index.ts#L151) 允许公开登录页。TypeScript 断言既不检查来源、协议，也不处理数组参数。

**静态复现场景。** 未登录用户打开 `/login?redirect=https%3A%2F%2Fexample.org` 并登录，赋值目的地是外站。可以确定的是重定向目标未校验；未在浏览器执行脚本协议，也没有证据证明 JWT 已泄露，不作这两项断言。

**改法示例。** 仅接收单个字符串，要求单 `/` 开头，排除 `//`、反斜线和控制字符；用 `new URL(value, location.origin)` 再检查协议/同源，失败回 `/`，最后保留内部 path/query/hash。保留现有全页重载以重建权限路由的行为。

**验收 / 任务 T10。** 合法内部原路返回不变；外站、协议相对地址、危险协议、数组/null、反斜线混淆均回安全默认页；不通过 `as string` 替代运行时校验。该修复可独立于大型架构任务落地。

### P1-11｜异地备份手册把 SSH `restrict` 误写成只允许传文件

**依据。** [docs/rfc/002-交付部署包.md:145–146](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/002-%E4%BA%A4%E4%BB%98%E9%83%A8%E7%BD%B2%E5%8C%85.md#L145-L146) 要求部署方配置异地备份；[deploy/package/部署手册.md:237–249](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/package/%E9%83%A8%E7%BD%B2%E6%89%8B%E5%86%8C.md#L237-L249) 的设计意图是限制备份密钥用途，却将密钥加入 root 并仅设置 `from=...,restrict`，还建议开启 root 密钥登录。

**证据与影响条件。** 手册 [deploy/package/部署手册.md:245–249](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/package/%E9%83%A8%E7%BD%B2%E6%89%8B%E5%86%8C.md#L245-L249) 声称该配置只能传文件。根据 [OpenSSH 官方 sshd(8) 的 restrict 与 command 选项](https://man.openbsd.org/sshd.8#restrict)，restrict 禁止转发、PTY 和用户 rc，不会强制限定远程命令；命令限制需要单独配置。若部署方照此配置且没有其他 ForceCommand 限制，从允许的备份机持有该密钥可执行非交互 root 命令。`from` 限制来源，不能把授权变成只读备份权限。未登录或改动任何服务器，也未证明现网已采用该配置。

**改法示例。** 后续部署文档 PR 改成专用低权限备份账号，对明确的两个备份目录授予只读访问，使用经过验证的强制命令/受限 rsync 包装器限制目录及读取操作，并继续禁用转发/PTY。不要提供没有实测的 root 万能命令作为替代；新账号拉取和恢复演练验证完成后再撤销旧密钥。

**验收 / 任务 T11。** 允许目录可拉取；任意远程命令、写入、目录越界、PTY/转发均被拒；能用备份完成隔离恢复。文档须分别说明来源限制、账号权限、命令限制三层含义。此任务涉及实际服务器时单独按部署流程实施，本 PR 只报告配置说明缺陷。

## 7. P2 局部缺陷与优化建议

### P2-01｜物料导入空单位被空指针打断，丢失行列错误清单

**依据。** [docs/rfc/003-基础资料.md:199–203](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/003-%E5%9F%BA%E7%A1%80%E8%B5%84%E6%96%99.md#L199-L203) 要求字典匹配失败整批报错；[src/main/java/com/lingmao/scm/common/excel/ExcelImport.java:26–32](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/excel/ExcelImport.java#L26-L32) 约定解析失败字段为 null，Service 保留错误归集后抛 1003。

**证据与场景。** [src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java:378](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java#L378) 使用 `Objects.requireNonNullElse(purchaseUnit, stockUnit)`。当库存单位为空/未知导致解析为 null，且选填采购单位留空时，两参数都为 null，先抛 NPE，无法走到 [src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java:284–292](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java#L284-L292) 的 `book.throwIfInvalid()`。事务不会正常导入，但用户收到的不是应有的 1003 行列提示。

**改法示例。** 用允许 null 的条件回退 `purchaseUnit != null ? purchaseUnit : stockUnit`，已有解析错误留给统一归集；后续使用这个值的逻辑继续判空，不能在归集前强制非空。

**验收 / 任务 T12。** 空单位、未知单位且采购单位为空时均得到对应单元格的 1003 清单，且没有任何 insert；合法库存单位且采购单位省略仍正常。扩展 [src/test/java/com/lingmao/scm/module/basic/service/impl/MaterialImportTest.java:184–218](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/test/java/com/lingmao/scm/module/basic/service/impl/MaterialImportTest.java#L184-L218) 的错误归集测试。

### P2-02｜起订量不是包装倍数时，建议采购量违反最小包装约束

**依据。** [docs/PRD.md:651–652](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L651-L652)、[docs/PRD.md:1252](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L1252) 同时要求“不小于起订量”与“最小包装量倍数”。

**证据与例子。** [src/main/java/com/lingmao/scm/common/num/Num.java:82–93](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/num/Num.java#L82-L93) 先按包装进位，再直接取 MOQ。净需求 5、换算系数 1、包装量 6、MOQ 10 时返回 10，满足 MOQ 却不是 6 的倍数；应为 12。此例为直接算式核验，未宣称 Java 测试已运行。

**改法示例。** 保留 [src/main/java/com/lingmao/scm/common/num/Num.java:75–78](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/num/Num.java#L75-L78) 既有 3 位数量预舍入意图，把正需求的算法改为 `ceil(max(换算并按现规则舍入后的需求, MOQ) / pack) * pack`；净需求 ≤ 0 仍返回 0，不引入 MOQ 强买。

**验收 / 任务 T13。** 非整倍数 MOQ、需求已超过 MOQ、小数包装/系数、null 默认值、零/负需求全部覆盖；原例 870.86 → 871 不变。只改公共数字函数并补行为测试，不调整采购业务流程。

### P2-03｜订单编辑冲突后缺少可继续操作的恢复路径

**依据。** [docs/api/README.md:132](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L132)、[docs/api/02-需求订单.md:229](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L229) 要求 1002 冲突提示后重取；同时需保留 [CLAUDE.md:53](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L53) 的未保存保护。

**证据与场景。** [web/src/views/order/order/form.vue:240–264](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/form.vue#L240-L264) 的保存只做 finally 复位，失败后 version 保留旧值；[web/src/views/order/order/OrderChangeDialog.vue:225–249](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/OrderChangeDialog.vue#L225-L249) 同样使用原 `editing.version`。两会话先后编辑同一单据，后保存者冲突后原页再试仍带旧版本，缺少明确重载/比较/继续编辑路径。全局错误提示不是版本恢复。

**改法示例。** 在整页表单/弹层共用的冲突处理入口保留草稿副本，加载最新服务器快照，给出明确的重载或重新应用修改流程；不能只替换 version 强行重试，也不能静默重置用户输入。

**验收 / 任务 T14。** 两会话冲突可恢复；用户能看到哪些输入尚未保存；放弃和继续编辑均有明确结果；不会把旧全量 DTO 覆盖到别人刚保存的新版本。与 T05 同一设计讨论，实施可分小 PR。

### P2-04｜默认业务日和日期快捷筛选使用浏览器时区

**依据。** [docs/PRD.md:2079](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L2079)、[docs/api/README.md:78](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L78) 要求一律东八区。

**证据。** [web/src/components/doc/docUtils.ts:22–37](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/components/doc/docUtils.ts#L22-L37) 用本地 `getFullYear/getMonth/getDate` 生成默认日期，订单在 [web/src/views/order/order/form.vue:65–66](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/order/order/form.vue#L65-L66) 使用；[web/src/components/DateRange.vue:14–30](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/components/DateRange.vue#L14-L30) 的今天/近七天/本月也取宿主日期。相对地，[web/src/utils/format.ts:45–68](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/utils/format.ts#L45-L68) 的显示转换已显式使用 Asia/Shanghai。因此问题限定于默认业务日和快捷筛选，不能说所有日期显示都错误。

**函数级复现。** 固定时刻 `2026-11-17T01:00:00Z`，以 America/Los_Angeles 执行从源码提取的日期函数，得到 `2026-11-16`，东八区业务日应为 `2026-11-17`。

**改法示例。** 统一 `businessToday()` 和纯日期加减辅助函数，单据默认日、快捷筛选共用东八区日期部分；对 `yyyy-MM-dd` 继续保持纯日期语义，避免再把它当 UTC 时间点转换一次。

**验收 / 任务 T15。** 东八区午夜前后，浏览器设上海/洛杉矶/UTC 均得到同一业务日与区间；手填纯日期不偏移，月份末尾和跨年加减正确。无需新增日期依赖，先复用现有格式化基础。

### P2-05｜BOM 页缺少契约明确保留的款式图和行联动料卡

**依据。** [docs/api/01-资料包.md:317](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L317) 明确左侧按所选款色显示款式图、右侧显示当前行料卡，鼠标指到哪行就切换。取消的是图上标号，不是整个预览区域。

**原型定位。** [docs/原型/src/5a-home-style.js:787–790](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5a-home-style.js#L787-L790) 保存当前悬浮行，[docs/原型/src/5a-home-style.js:868–877](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5a-home-style.js#L868-L877) 展示图/料卡，[docs/原型/src/5a-home-style.js:887](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5a-home-style.js#L887)、[docs/原型/src/5a-home-style.js:932](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/src/5a-home-style.js#L932) 绑定两种视图的 mouseenter；对应生成页 [docs/原型/index.html:6305–6308](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L6305-L6308)、[docs/原型/index.html:6386–6395](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L6386-L6395)、[docs/原型/index.html:6405](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L6405)、[docs/原型/index.html:6450](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E5%8E%9F%E5%9E%8B/index.html#L6450)。

**实现定位。** [web/src/views/style/style/TechPackTab.vue:894–1023](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/TechPackTab.vue#L894-L1023) 仅有列表/按颜色两张表；[web/src/views/style/style/TechPackTab.vue:939–942](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/TechPackTab.vue#L939-L942) 是行内缩略图，没有当前行款图+料卡区及行悬浮联动。本项依据模板结构核对，未做逐屏截图或视觉等价断言。

**改法示例。** 后续增加只读预览组件，输入现有详情、所选款色、当前行；hover 和键盘焦点联动。空图片、未配 SKU、分码用量有明确展示；BOM 参考价和单件成本按 [docs/api/01-资料包.md:304](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/01-%E8%B5%84%E6%96%99%E5%8C%85.md#L304) 保持现有可见范围，不误套独立核价页签权限。保持已有表格编辑和切换行为，不引入图上坐标或提前实现版本对比。

**验收 / 任务 T16。** 列表/颜色视图都能切行换卡，切款色换图，无图/缺配料有空态；完成 Chrome/Edge 的实际交互与截图对照。该 UI 补齐单独提交，本审查 PR 不改页面。

### P2-06｜删除规则、恢复演练提示和部分进度说明仍保留旧口径

**依据与具体冲突。**

| 旧内容 | 当前依据与实现 | 应如何处理 |
|---|---|---|
| [docs/PRD.md:1971](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L1971) 仍称所有表逻辑删除 | [CLAUDE.md:37](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L37)、[docs/架构设计.md:655](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L655) 定义新业务表物理删除；[src/main/java/com/lingmao/scm/common/entity/AuditEntity.java:14–17](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/entity/AuditEntity.java#L14-L17)、[src/main/java/com/lingmao/scm/common/entity/BaseEntity.java:8–13](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/common/entity/BaseEntity.java#L8-L13) 区分新业务表和旧 sys 表；[src/main/java/com/lingmao/scm/module/basic/service/impl/CategoryServiceImpl.java:89–95](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/basic/service/impl/CategoryServiceImpl.java#L89-L95) 实际删除未引用品类 | 改成新旧表各自规则，链接权威章节，避免后续新增 `deleted` 或漏外键 |
| [deploy/package/scripts/check-restore.sh:7–8](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/package/scripts/check-restore.sh#L7-L8)、[deploy/package/scripts/check-restore.sh:84–91](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/package/scripts/check-restore.sh#L84-L91) 认为线上业务表行数少于备份就不正常 | 上述物理删除允许合法减少业务行；[src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java:482–493](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java#L482-L493) 也允许删除符合条件的草稿 | 将行数差异作为需结合业务解释的信息，恢复完整性另行验证；脚本 [deploy/package/scripts/check-restore.sh:95–97](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/package/scripts/check-restore.sh#L95-L97) 仍输出演练成功，因此不称它必然阻止恢复 |
| [docs/进度.md:165](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E8%BF%9B%E5%BA%A6.md#L165) 已含订单变更完成，[docs/进度.md:167](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E8%BF%9B%E5%BA%A6.md#L167) 又保留未完成项 | [src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:198–227](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java#L198-L227) 等实现已存在；但仍有本报告 P1-07 的质量问题 | 区分已实现、待联调、缺陷待修，去掉重复的开发状态 |

另有需澄清而非直接判错的旧表述：[docs/架构设计.md:643](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1.md#L643) 说反审核不删价格档案，没有清楚说明现在会自动作废来源记录；[docs/rfc/006-价格档案.md:289–295](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/rfc/006-%E4%BB%B7%E6%A0%BC%E6%A1%A3%E6%A1%88.md#L289-L295) 和 [src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java:799–800](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java#L799-L800) 已规定/调用 `voidFromDoc`。应补充“保留历史但自动作废”的含义，不能把保留记录误读为价格继续生效。

**改法示例 / 任务 T17。** 一份文档提交同步 PRD、架构、进度；恢复提示如需改脚本则另一个 commit。建立简短的“替代了哪条旧约定”链接，禁止回改已执行迁移。验收用合法删除一条草稿后的隔离恢复样例，报告提示不再声称必然数据异常。

### P2-07｜前端缺少行为回归入口，构建工具版本和 CI 门禁未收敛

**依据。** [CLAUDE.md:44](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L44)、[CLAUDE.md:68–71](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/CLAUDE.md#L68-L71) 要求规则/数据库集成验证；[docs/api/README.md:196](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L196) 要求前端 build 含类型检查。现有门禁值得延续，但类型正确无法发现本报告的 DTO 信息丢失与异步状态问题。

**证据。** [web/package.json:6–10](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/package.json#L6-L10) 只有 dev/dev:mock/build/preview，没有 lint/test；同文件未声明 packageManager/engines。[.mvn/wrapper/maven-wrapper.properties:1–3](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/.mvn/wrapper/maven-wrapper.properties#L1-L3) 已固定 Maven，但 [deploy/build-package.sh:64–75](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/build-package.sh#L64-L75)、[deploy/build-package.sh:118–126](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/deploy/build-package.sh#L118-L126) 使用 PATH 中的 mvn/pnpm。基线 `git ls-files .github/workflows` 为空，没有仓库内 GitHub Actions 工作流证据。与此同时打包脚本确实运行 `mvn verify` 和前端构建，不能把项目说成“完全没有测试或发布门禁”，也不能推断 GitHub 仓库设置中一定没有外部检查。

**改法示例。** 明确完整 JDK 21、Node/pnpm 受支持版本和包管理器构建脚本策略；打包与 CI 复用 Wrapper/同一前端命令。只为真实风险建立少量前端行为测试：逐 SKU 往返、部分加载失败、KeepAlive 草稿、查询/导出一致和日期边界。后端 CI 启动隔离 PostgreSQL/Redis 跑单测、ArchUnit、迁移和 IT；依赖审计及镜像固定策略另作明确门禁，不凭版本号猜漏洞。

**验收 / 任务 T18。** 全新环境能按锁文件安装；`vue-tsc`、build、上述行为回归和 `./mvnw verify` 可重复通过，产物真实存在；CI 能稳定拦住本报告给出的错误样例。不为提高覆盖率数字写重复实现的测试；先覆盖本次可达风险。

## 8. [待确认] 事项与继续工作的默认边界

这些问题不计入上述 18 项确定性发现，也不阻塞本次全仓扫描和文档 PR。

| 编号 | 具体证据 / 待确认问题 | 建议默认处理与后续验收 |
|---|---|---|
| Q1 | [src/main/java/com/lingmao/scm/module/inventory/controller/InvDocController.java:127–142](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/inventory/controller/InvDocController.java#L127-L142) 以 `inventory:doc:add` 提供采购源单预填/生成；[src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:72–94](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java#L72-L94) 直接按 ID 读取采购单，未套采购 DataScope。[docs/api/04-库存.md:234–269](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/04-%E5%BA%93%E5%AD%98.md#L234-L269) 未明确仓库岗位是否必须受采购单本人范围限制 | **[待确认]** 仓库岗位是否本就要查看全部可收货采购来源。先制定采购源单的读取范围矩阵；按“权限 × 数据范围 × 单据状态”验证；未确认前不直接定性为越权，也不擅自增加会阻断仓库工作的权限 |
| Q2 | [docs/api/02-需求订单.md:224–225](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L224-L225) 的款/尺码级 UI 与 [docs/api/02-需求订单.md:273](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/02-%E9%9C%80%E6%B1%82%E8%AE%A2%E5%8D%95.md#L273) 的逐 SKU 导入不同；实现证据见 P1-03 | **[待确认]** 是否长期支持同款同码异色的不同价格/行交期。默认保留现有合法数据，先做无损往返；收紧所有入口属于业务契约变更，另行决定 |
| Q3 | [web/src/components/ImageUpload.vue:52–74](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/components/ImageUpload.vue#L52-L74) 上传完成才写入模型；[web/src/views/style/style/form.vue:258–277](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/style/style/form.vue#L258-L277) 保存当前模型；对照 [web/src/views/supplier/supplier/CertDialog.vue:128](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/web/src/views/supplier/supplier/CertDialog.vue#L128) 已有上传中禁存。[docs/api/README.md:81](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/api/README.md#L81) 规定文件数组覆盖，却未明确上传中保存/关闭的交互策略 | **[待确认]** 保存是等待上传、禁用还是明确允许不带未完成文件。尚未做慢网浏览器复现，不声称已发生附件丢失；后续验收慢上传、失败、关窗重开、迟到响应，不在本 PR 修改公共上传行为 |

## 9. 可执行任务与优先决策

### 9.1 Top 3：建议下一步拍板的架构改进

1. **统一库存及采购的双向事务协议（T01/T02，P1-01/02）。** 决定源单、明细、库存的全局锁层级，以及审核/反审核均必须满足的结果不变量。建议先做该项，覆盖当前可达的死锁和净入库越界，再继续扩展生产源单 Handler。交付是锁顺序矩阵、方向感知接口和可强制交错的 PostgreSQL IT，不是重写库存引擎。
2. **确定编辑数据的保留规则（T03/T04/T05/T14，P1-03/04/05、P2-03）。** 建议统一“未修改字段无损往返、未成功加载不序列化为空、dirty 草稿不被自动刷新覆盖、版本冲突先保留再解决”。先分别修三处已复现问题，再提取少量公共辅助能力；无需引入全新状态管理框架。Q2 的业务选择只影响是否后续收紧入口。
3. **确定 MRP 的跨域读边界和 BOM 就绪契约（T08/T09，P1-08/09）。** 建议保留模块化单体，优先通过批量 Service DTO 表达可算订单、SKU 完整性和库存；若保留特定跨域读模型，则正式记录边界例外。先补新尺码完整性契约，再逐条替换 SQL，保留数值基准和性能要求，避免一次跨多个模块硬改。

上述三项不应拖住 T07（变更详情空指针）、T10（登录回跳）、T11（备份权限说明）等可以独立准备的小任务。每项均需在完整工具链中验证后再合入。

### 9.2 后续 PR 拆分表

| 任务 | 建议负责范围 | 独立交付与验收依据 | 依赖 / 顺序 |
|---|---|---|---|
| T01 | 后端 inventory + purchase | 锁顺序矩阵、反向预锁、重算移交预锁；P1-01 两事务 IT | 首批，先约定接口再改调用 |
| T02 | 后端 purchase | 双向净入库上下界；P1-02 全事务回滚/合法逆向序列 | 与 T01 同步方向约定，独立 commit |
| T03 | 前端 order，接口维护者协审 | 原始逐 SKU 映射与显式批改；P1-03 往返不丢字段 | 首批；保留数据可先行，收紧规则等 Q2 |
| T04 | 前端 techpack | 成本分区加载状态和安全序列化；P1-04 失败保存不覆盖 | 首批，独立小 PR |
| T05 | 前端 inventory / 公共表单 | dirty 激活策略；P1-05 切页保留草稿 | 首批，与 T14 共用原则 |
| T06 | 前端公共表格 + 三个列表 | 查询/导出共用类型映射；P1-06 三页面参数回归 | 不改变后端筛选契约 |
| T07 | 后端 order | 非 SKU 空键保护；P1-07 六种状态/类型组合 | 可先独立修复，需后端构建/测试通过 |
| T08 | techpack + order，API/TS 同步 | SKU 完整性 DTO 和统一门禁；P1-08 跨域场景 | 先补契约；旧锁定版本保持不变 |
| T09 | 架构维护者 + purchase/upstream | Service DTO 或正式只读例外；P1-09 数值/权限/边界测试 | 边界决定后分链路，结合 Q1 |
| T10 | 前端认证 | 同源内部路径归一化；P1-10 合法/恶意参数矩阵 | 可独立修复 |
| T11 | 部署维护者 | 低权限备份账号、强制命令及手册；P1-11 只读/越界/恢复验收 | 文档与实际部署分别安排 |
| T12 | 后端 basic / Excel | nullable 回退；P2-01 1003 与无写入断言 | 独立卫生修复 commit |
| T13 | 后端 common / purchase | 先 MOQ 后包装进位；P2-02 数字边界用例 | 独立小 PR，不改数量精度规范 |
| T14 | 前端 order / 公共表单 | 1002 后保留/比较/重载流程；P2-03 双会话恢复 | 与 T05 同一设计，分开实现 |
| T15 | 前端日期公共件 | 东八区业务日；P2-04 跨时区/跨月回归 | 可独立修复 |
| T16 | 前端 techpack | 只读 BOM 预览/料卡联动；P2-05 浏览器对照 | 保持当前表格交互，单独 UI PR |
| T17 | 文档 / 部署脚本 | 删除/价格/进度口径；P2-06 恢复提示 | 文档和脚本分 commit，不改迁移 |
| T18 | 工程维护者 | 固定工具版本、CI、针对本次风险的前端行为入口 | 可与前述修复并行，成为后续 PR 门禁 |

每个后续代码 PR 至少执行项目类型检查/前端 build（如涉及前端）或后端 test/package，以及与风险有关的 verify/浏览器验收；完整联动 PR 执行两端检查。对环境受限的项明确保留状态，不将跳过测试标为通过。

## 10. 本次交付与证据限制

- 本报告独立提交；所有修复示例和任务都是后续方案，不表示已经改到代码里。当前后端无法完成构建，因此没有将“看起来简单”的修复混入文档 PR。
- 构建记录来自本次环境；`docs/进度.md` 中历史测试通过次数属于历史记录，没有当作本次验证结果。完整 JDK/数据库/浏览器可用后，按第 4 节和各任务补齐验证。
- 架构分析覆盖当前跟踪源码及文档，未扫描 Git 全历史、私有配置、线上数据库、第三方完整源码或外部仓库设置；也未宣称达到 PRD 中的性能指标。相关需求位置为 [docs/PRD.md:2074–2082](https://github.com/xiaoliangao/lingmao/blob/34e692b3144d562d885da12270a59b1ec148bcd5/docs/PRD.md#L2074-L2082)。
- 仅从指定审查分支提交 PR 到核实后的默认分支 `main`；不直接推送主分支，不替业务确认尚未明确的规则。

## 11. PR #4 整改状态回写

原审查基线、发现、证据与历史执行结果保留。以下是后续修复分支`fix/audit-remediation`的验收范围；不把历史审查改写为当时已修复。完整契约、测试方法和运行边界见[audit-remediation.md](audit-remediation.md) §2–6。

已验证代码为`61ea1c542ba8448cecefc8684f2031530d3df49d`，[云端运行](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997)通过：464单测、121 IT、19前端测试、20 API/浏览器用例及类型/Lint/build/恢复；125张原始PNG已逐张打开。[CI产物](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997/artifacts/14356814763)只含隔离合成数据。全部P0/P1的真实启用门禁尚未满足，PR保持Draft。

| 原问题编号 | 修复内容 / 定位 | 状态与范围 | 修复 Commit SHA |
|---|---|---|---|
| P1-01 | 正反过账 / MRP 锁序；`src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:565–598` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / 3939674 / 1d33085 |
| P1-02 | 退货反审净收上限；`src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:176–208` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / 3939674 |
| P1-03 | 逐 SKU 无损往返；`web/src/utils/orderSku.ts:12–26` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / da06a92 / c134b44 / 793e5ed |
| P1-04 | 成本失败不覆盖；`web/src/views/style/style/TechPackTab.vue:154–202,338–369` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / 3939674 / da06a92 / c134b44 / 793e5ed |
| P1-05 | 盘点 KeepAlive 草稿；`web/src/views/inventory/count/detail.vue:73–105` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 / c134b44 / 793e5ed / 61ea1c5 |
| P1-06 | 列表与导出同条件；`web/src/composables/useTable.ts:53–74,96–117` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / da06a92 / c134b44 |
| P1-07 | 非 SKU 终态变更可读；`src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:215–228` | 通过（限定本行验收范围）；代码 / 运行 | 3939674 / 1d33085 |
| P1-08 | 实际色码与 BOM 准入；`src/main/java/com/lingmao/scm/module/techpack/service/impl/RequirementChecks.java:23–68` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / 3939674 |
| P1-09 | 限定跨域读投影；`src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:797–804` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / 3939674 |
| P1-10 | 登录回跳校验；`web/src/utils/redirect.ts:1–16` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 / c134b44 |
| P1-11 | 备份账号只读隔离；`deploy/package/部署手册.md` | 通过（限定本行验收范围）；文档 / 合成环境运行 | a3c7196 / da06a92 |
| P2-01 | 导入空单位行列错误；`src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java:378–384` | 通过（限定本行验收范围）；代码 / 运行 | 3939674 |
| P2-02 | MOQ 与整包量同时满足；`src/main/java/com/lingmao/scm/common/num/Num.java:82–93` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 |
| P2-03 | 1002 冲突可恢复；`web/src/components/doc/docUtils.ts:61–69` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 / c134b44 / 793e5ed |
| P2-04 | 东八区纯日期；`web/src/utils/businessDate.ts:4–40` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 |
| P2-05 | BOM 款图 / 行料卡；`web/src/views/style/style/TechPackTab.vue:953–969,1019,1070` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 |
| P2-06 | 删除、恢复与进度旧口径；`docs/架构设计.md` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / da06a92 |
| P2-07 | 工具锁定与完整门禁；`.github/workflows/ci.yml` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 / bac099d / 13fa7a5 / c134b44 / 2ff9117 / 8b7846c / 327b734 / 793e5ed / 61ea1c5 |
| Q1 | 仓管来源独立作业视图；`src/main/java/com/lingmao/scm/module/inventory/controller/InvDocController.java:127–167` | 通过（限定本行验收范围）；契约已定 / 验证边界 | a3c7196 / 3939674 / 327b734 |
| Q2 | 保留合法逐 SKU 差异；`web/src/utils/orderSku.ts:12–26` | 通过（限定本行验收范围）；代码 / 运行 | a3c7196 / da06a92 / 793e5ed |
| Q3 | 上传等待与迟到隔离；`web/src/utils/pendingTasks.ts:2–30` | 通过（限定本行验收范围）；代码 / 运行 | da06a92 / c134b44 |

原报告材料缺口和后续分期继续有效；正式截止/期初、实际角色规则、目标部署机及业务人员验收均不由合成CI替代。完整首页聚合未实现，不能把S/P1-08当前入口通过扩大为首页功能全部通过。
