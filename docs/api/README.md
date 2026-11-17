# 接口文档（一期剩下的模块）

## 1. 这份文档怎么用

用户 11-15 定：一期剩下的页面**前端先做**，照这里的接口写；后端随后照同一份实现，对不上的地方再一起改。所以这里定的是"前后端之间的约定"，不是后端的实现。

- 每个模块一份文档（§2 的表），讲页面、接口、状态和前端要知道的规则。
- 类型和接口函数已经写成 TypeScript：`web/src/types/<模块>.ts`、`web/src/api/<模块>.ts`。前端直接 import，不要在页面里再定义一遍。
- 需求看 PRD（每个接口后面写了节号），样子看原型 `docs/原型/`（§2 的表写了在哪个文件），写法照 `CLAUDE.md` 的"前端"一节。已经做好的款式、供应商、物料页面是样板。
- 写前端时觉得接口不够用、不好用：在那个模块文档末尾的"改动记录"里写一行，连同 `types`、`api` 文件一起改，Claude review 时一起看。不要只改页面、让接口和文档对不上。

## 2. 页面和文档

| 页面 | 地址 | 原型 | 文档 |
|---|---|---|---|
| 资料包（款式详情的页签） | `/styles/:id?tab=techpack` | `5a-home-style.js` | [01-资料包](01-资料包.md) |
| 按款看成本 | `/costing` | `6-pages-later.js` 的 `/costing` | 01 |
| 工艺标准（资料包里拉出的抽屉，不单开一页） | — | `7-pages-master.js` 的 `/settings` | 01 |
| 需求订单 | `/orders`、`/orders/:id`、`/orders/new`、`/orders/:id/edit` | `5b-order.js` | [02-需求订单](02-需求订单.md) |
| 订单变更单 | `/order-changes`、`/order-changes/:id` | `5b-order.js` 的 `/changes` | 02 |
| 算料 | `/mrp`、`/mrp/:id` | `5c-material.js` | [03-算料采购](03-算料采购.md) |
| 采购单 | `/purchases`、`/purchases/:id` | `5c-material.js` | 03 |
| 补货提醒 | `/reorder` | `5e-price.js` | 03 |
| 物料入库单（含"待到料"页签） | `/stock-in`、`/stock-in/:id` | `5c-material.js` | [04-库存](04-库存.md) |
| 物料出库单 | `/stock-out`、`/stock-out/:id` | `5c-material.js` | 04 |
| 物料盘点单 | `/stock-takes`、`/stock-takes/:id` | `5c-material.js` | 04 |
| 库存 | `/stock` | `5c-material.js` | 04 |
| 外发加工单 | `/production`、`/production/:id` | `5d-production.js` | [05-生产](05-生产.md) |
| 成品采购单 | `/fp-purchases`、`/fp-purchases/:id` | `5d-production.js` | 05 |
| 生产看板 | `/board` | `5d-production.js` | 05 |
| 手机报工 | `/mobile`、`/mobile/:id` | `5d-production.js` | 05 |
| 首页的概况、待办 | `/home` | `5a-home-style.js` 的 `/` | [06-首页](06-首页.md) |
| 上游已变更、待处理 | `/upstream` | `5a-home-style.js` | 06 |
| 设置 › 审批（管理后台） | `/system/approval-rule` | `7-pages-master.js` 的 `/settings` | 本文 §4.6 |

单据详情页的地址写在 `web/src/utils/docRoute.ts`：报错清单、"下游单据"页签、首页待办里点单号都用它。

原型里还有打样、企划、质检、成品、财务、报表、合同、客户，它们是二期或待定的模块，这一期不做（PRD §2.5）。核价这一期只做按款看成本 `/costing`，询价比价、订单毛利待定。

## 3. 通用约定

已经做好的模块都是这么写的，新模块照旧。

### 3.1 请求和返回

- 地址都以 `/api` 开头。`utils/request.ts` 自动带上登录的 token；没登录、登录过期回 401，自动跳登录页。
- 返回体 `R { code, msg, data }`，`code = 0` 才算成功。`http.get / post / put / delete` 直接拿到 `data`。
- 出错时请求函数抛 `ApiError`，消息已经弹过了，调用方只管自己的流程（关不关对话框、要不要刷新）。批量条逐条调时传 `{ silent: true }`，出错不弹，由批量条汇总。

| code | 意思 | 前端怎么处理 |
|---|---|---|
| 400 | 参数不对（必填没填、格式不对） | 自动弹消息 |
| 403 | 没有权限；多级审批里不是这一级的审批人 | 自动弹消息 |
| 404 | 不存在，或者不在你的数据范围里（不区分） | 详情页显示"找不到" |
| 1000 | 一般业务错误，`msg` 就是给用户看的 | 自动弹消息 |
| 1001 | 库存不足，`data` 是缺料清单 | 自动弹表格 |
| 1002 | 状态已经变了，或者被别人改过（`version` 对不上） | 自动弹消息；页面重新取一遍 |
| 1003 | 导入校验没通过，`data` 是错误清单 | `ImportDialog` 自己显示 |
| 1004 | 超出上限（超排、超收、超回货），`data` 是超了的行 | 自动弹表格 |
| 1005 | 有下游单据，不能反审核、取消、删除，`data` 是 `DocRef[]`；删除被引用的主数据时没有 `data` | 自动弹表格 |
| 500 | 系统出错，消息里带错误编号 | 自动弹消息 |

按错误码判断时用 `utils/errorCodes.ts` 里的名字，不写数字。1001、1004 的清单每种单据长得不一样，各模块文档里写了每列是什么。

### 3.2 列表

- **分页**：`page` 从 1 起，`size` 最多 500，返回 `{ list, total }`。列表页用 `composables/useTable.ts`。
- **关键字**：`keyword`，按各接口写的字段包含查（不分大小写）。
- **一次查几个**（PRD §5.4）：关键字里有分隔符（空白、半角全角逗号、顿号、分号；从 Excel 粘一列进来是换行）就再拆开，按列表上的单号、款号、编码这些列精确查（不分大小写），和整句包含查 OR 起来（名称里本来就有空格、逗号）。一次最多 200 个，多了回 400。哪些列算，看各接口文档的关键字说明。后端用 `common/page/Keywords.codes`；前端就一个搜索框（`FilterBar`、`DataPick`），从 Excel 粘一列进来换行换成空格，口径在 `utils/keywords.ts`
- **多选条件**：数组参数发成 `status=A&status=B`，`request.ts` 已经这样配了。
- **日期范围**：`xxxFrom`、`xxxTo`，`yyyy-MM-dd`，两头都含。组件用 `DateRange`。
- **选出来的 id**：查询类型里写成 `number | null`（`UserSelect`、`SupplierSelect` 清空后是 `null`）。值是 `null` 的参数 axios 不发，等于没筛。
- **我的 / 全部**：单据列表有 `mine=true`，只看自己负责的。数据范围是"本人"的角色本来就只看得到自己的（PRD §3.2）。
- **导出**：`GET /api/<资源>/export`，筛选参数和列表一样，再加 `ids` 只导勾选的。用 `download()`。

### 3.3 字段

- **时间**：日期 `2026-11-15`，时间 `2026-11-15 10:30:00`，都按东八区。显示用 `formatDate`、`formatDateTime`。
- **数量、单价、金额**：JSON 里是普通数字（后端 `BigDecimal`）。显示用 `formatQty`、`formatPrice`、`formatMoney`。合计、金额以后端算的为准：前端算的只用来在输入时即时显示，保存时后端重算。
- **字典**：存的是 value。显示用 `useDict(type).labelOf(value)`，下拉用 `DictSelect`。字典类型写在字段注释里。
- **文件**：上传 `POST /api/files` 得到 `FileVO`，地址带签名，原样用，不要自己拼 `/files/...`。挂在记录上的图片、附件是整组替换：`PUT …/images` 或 `…/attachments`，传 `FileVO` 数组（`attachmentBody`）。
- **保存是整体覆盖**：没填的发 `null`，不要省掉字段。单据的草稿保存整张单（单头 + 全部明细 + `version`）。
- **新建、保存的返回**：新建单据返回 `DocSaved { id, no, version }`（有的多几个字段），保存草稿返回新的 `version`。"存草稿并提交"拿它接着调 `submit`，不用再取一次详情。
- **主体**：单据上的 `companyId` 必填。新建时默认填我的默认主体：登录信息（`GET /api/auth/me`）里的 `defaultCompany`，页面用 `user.defaultCompanyId`。它是 `null`（没设默认主体、启用的主体又不止一个）时空着，让人选。

### 3.4 选数据

下拉一律能搜。已有的公共件：

| 选什么 | 用什么 |
|---|---|
| 物料 SKU、款式（弹窗，一次多条） | `DataPick kind="sku"`、`kind="style"` |
| 供应商、工厂 | `SupplierSelect`（`category` 能传数组，如 `['GARMENT', 'PROCESSOR']`） |
| 人 | `UserSelect` |
| 字典 | `DictSelect` |
| 仓库、颜色、尺码组、品类等基础资料 | `api/basic.ts` 里各自的 `options` |

### 3.5 按钮和批量

- **按钮看 `actions`**：详情接口返回 `actions`，是后端按"状态允许 ∩ 有权限 ∩ 业务条件"算好的（架构 §4.2）。前端照着显示，不在页面里再写一遍状态规则。列表行上的操作也一样，看行上的 `actions`。
- **批量**：勾选后底部出 `BatchBar`，逐条调单条接口，不另写批量接口。批量按钮的 `can` 用行上的 `actions` 判断。
- 带批量条的单据列表，行上有 `version`，批量动作直接带它，不先取一次详情；列表取到以后别人改过，后端回 1002，批量条留着勾、报原因。批量条逐条调的接口函数，最后一个参数都收 `RequestOptions`（传 `{ silent: true }`）。
- **权限**：按钮用 `v-perm` 或 `user.hasPerm(…)` 控制显不显示；有没有权限最后由后端判断。权限码在各模块文档里。

## 4. 单据公共

需求订单、变更单、算料单、采购单、物料入库单、出库单、盘点单、外发加工单、成品采购单都套这一套（架构 §4）。类型在 `types/biz.ts`，接口在 `api/biz.ts` 的 `docApi(base)`。

### 4.1 状态

通用状态是 `DocStatus`：草稿、待审核、已审核、执行中、已完成、已结案、已作废。各单据只用其中一部分，中文按单据叫，比如采购单的 RUNNING 叫"部分到货"、需求订单的 VOIDED 叫"已取消"。每个模块的 `types` 里有自己的 `XXX_STATUS`（中文 + 标签颜色）。

资料包、算料单、异常的状态和这套不一样，各用自己的类型。

### 4.2 状态动作

`POST /api/<单据>/{id}/<动作>`，请求体 `DocActionReq { version, reason? }`。前端用 `docApi(base).act(id, action, body)`。

| 动作 | 中文 | 点之前 |
|---|---|---|
| `submit` | 提交 | — |
| `withdraw` | 撤回（只有制单人） | — |
| `reject` | 驳回 | 弹框写原因，必填 |
| `audit` | 审核 | 有的单据要填东西（需求订单填承诺交期），见模块文档 |
| `unaudit` | 反审核 | 确认："回到草稿，改完要重新提交审核。" |
| `close` | 结案 | 确认后果 |
| `reopen` | 取消结案 | 确认 |
| `void` | 作废 | 确认，红色按钮 |

确认框的文字在 `DOC_ACTION_CONFIRM`。动作成功后重新取详情，刷新操作记录。

`version` 是打开详情时拿到的。别人在这期间改过、审过，后端回 1002，页面提示后重新取一遍。删除草稿是 `DELETE /api/<单据>/{id}`（`docApi(base).remove`），提交以后只能作废。

### 4.3 详情的公共部分

每种单据的详情 `XxxDetail` 都 extends `DocHeader`：单号、状态、`version`、主体公司、备注、制单人、审核人、`actions`、`approval`、`pendingNotices`。

详情页的公共页签和块：

| 什么 | 接口 | 组件 |
|---|---|---|
| 操作记录页签 | `GET …/{id}/logs` | `DocLogList` |
| 附件 | `GET …/{id}/attachments`，整组替换 `PUT …/{id}/attachments` | 照款式的 `DesignFiles` |
| 下游单据页签 | `GET …/{id}/references` → `DocRef[]`，点单号用 `docRoute` | — |
| 上游变更提醒（顶上的黄条） | `pendingNotices > 0` 时显示；`GET …/{id}/notices`，处理一条 `POST …/{id}/notices/{noticeId}/handle` | — |
| 审批块 | 详情里的 `approval` | — |

### 4.4 多级审批

需求订单、采购单、外发加工单、成品采购单金额超过门槛会多审一级（PRD §5.1，架构 §4.8）。这几种单据的详情多一个 `approval: ApprovalView`。

- 待审核时显示审批块：每一级谁审、门槛、谁什么时候审的、现在等谁。审完了只有多于一级的留着。
- 只有当前这一级的审批人 `actions` 里才有 `audit`、`reject`。别人看到的是"现在等××审"（`approval.waitingFor`），不出按钮。
- 中间一级审过，单据还是待审核，提示"这一级审过了，等××审"。
- 哪一级驳回都回到草稿；重新提交从第 1 级审起。

### 4.5 下游单据和变更提醒

- `DocRef` 是"指向一张单据"：类型、单号、状态（中文和颜色都翻好了）、负责人。1005 的清单、"下游单据"页签、首页待办、变更提醒里都是它。
- 需求订单的变更单审核以后，给每张受影响的下游单据（外发加工单、采购单、算料单）各插一条提醒。下游详情顶上出黄条"上游订单已变更"，点开看说了什么，处理完点"已处理"（可以写一句怎么处理的）。首页"上游已变更"列的是还没处理的。

### 4.6 设置 › 审批

管理后台的一页，只给超级管理员（`biz:approval-rule:list`、`:edit`）。菜单在"系统管理"下，地址 `/system/approval-rule`，页面文件 `views/system/approval-rule/index.vue`，和用户、角色这些页面放在一起。

- 接口：`approvalRuleApi`（`GET`、`POST`、`PUT`、`DELETE /api/approval-rules`）。
- 一条规则 = 单据类型 + 金额超过多少 + 审批人（角色和指定的人二选一）。
- 同一种单据有几条就加几级，门槛低的先审。改规则只影响以后提交的单，已经提交的不变。
- 样子照原型 `/settings` 里的"审批"。

## 5. 菜单和权限

页面出不出现在侧边栏，由后端的菜单表 `sys_menu` 决定：菜单的 `component` 对应 `web/src/views/` 下的一个页面文件，没有这个文件的菜单显示"还没上线"。

各模块文档里列了它的菜单（放在哪个目录下、地址、页面文件、权限码）。前端开工前，Claude 先把这些菜单和按钮权限加进迁移，提交后在开发库里就能看到菜单。之后要改菜单，跟 Claude 说。

详情页、新建页这种不在侧边栏的页面，加在 `web/src/router/index.ts` 的 `DETAIL_ROUTES` 里，样板是物料、供应商、款式的详情页。

## 6. 后端没好之前：mock

后端还没写的接口，先用前端的假数据回答：

```bash
cd web && pnpm dev:mock
```

- 只有写了 mock 的请求由假数据回答；别的照常发给后端。所以登录、菜单、基础资料、供应商、款式还是用真的，后端（IDEA 里的 8080，或者 `VITE_API_TARGET` 指的地址）要开着。
- 假数据里的单据指向款式 1（FS2611150101）、物料 1-4、供应商 1-4、仓库 1-3、主体 1，这些走真后端。开发库是空的时候点款号会是"这个款式不存在"：先往开发库灌示例主数据 `docker exec -i scm-dev-postgres psql -U scm -d scm -v ON_ERROR_STOP=1 < dev/seed-demo.sql`（跑几遍都一样，只给本机开发库用）。假数据改了 id，`dev/seed-demo.sql` 跟着改。
- 每个模块一个文件 `web/src/mock/<模块>.ts`，用法写在 `web/src/mock/index.ts` 开头。数据放在内存里，刷新就回到初始。
- 假数据照 `types` 里的类型造；状态动作要能点通（提交 → 待审核 → 审核 → 已审核……），好看每个状态下的页面。业务规则不用全写，挑页面上看得出来的写（比如超收报 1004、库存不够报 1001），清单的样子照文档。
- 导出、PDF、盘点表这些下载接口，mock 返回一个 `Blob` 就行，`download()` 照样存成文件。
- 正式打包不带 mock（`import.meta.env.DEV` 在打包时是 false）。后端写好一个模块，就删掉它的 mock 文件。

## 7. 交接和 review

- 前端每做完一个模块，`pnpm build` 要过（含类型检查）。提交前缀用「页面:」，只提交自己改的文件。
- Claude 按三样东西 review：接口文档、`CLAUDE.md` 的"前端"约定、原型。
- 不改后端代码和迁移。接口要改，照 §1 的最后一条。

## 8. 模块文档怎么写

每份照这个顺序，不相关的节可以省：

1. **页面**：地址、原型在哪、页面文件、菜单（放在哪个目录下、排第几、权限码）、按钮权限码。
2. **状态**：每个状态的中文、标签颜色、能做什么。
3. **接口**：先一张总表（方法、路径、权限、一句话），再逐个写参数、返回（类型名）、会报什么错、规则（PRD 节号）。
4. **前端要知道的规则**：什么时候灰掉、输入时怎么校验、算什么、提示什么。
5. **按默认做的**：PRD §12 里还没答的问题，这里按哪个默认做法（Q 编号）。
6. **要用户定的**：推荐的做法写在前面。
7. **改动记录**。

## 改动记录

| 日期 | 改了什么 |
|---|---|
| 11-15 | 初稿：通用约定、单据公共、菜单、mock、交接 |
| 11-17 | §3.3 加"主体"：`/api/auth/me` 返回 `defaultCompany`，单据的 `companyId` 必填、页面默认填它；§3.5 带批量条的列表行上有 `version`，批量条逐条调的接口都收 `RequestOptions` |
| 11-17 | §3.2 "一次查几个"写全：逗号、顿号、分号、换行也算分隔，单号、款号、编码都精确查，和整句包含查一起，最多 200 个；搜索框就一个，能直接粘 Excel 的一列 |
