# 审查修复 UI 回归套件

本目录是通用 CI 使用的真实 API 与浏览器回归套件。代码、语法检查和用例发现都不能代替运行验收；实际通过状态、提交和 Actions 证据以 `docs/reviews/` 的整改记录为准。

## 文件边界与前置

- `harness.py`：仅接受 loopback HTTP；API 固定隔离端口 `18080`。凭据只读进程环境，API 跟踪仅保留方法、路径、HTTP 状态与业务码。
- `prepare_fixtures.py`、`fixtures/domain_seed.py`：以 `dev/seed-demo.sql` 匿名主数据为基础，经真实接口建立业务单据；不控制服务，不直接改业务表，不清理已过账单据。每次完整运行需要全新 `*_test` 数据库。
- `test_audit_ui.py`：Python Playwright + unittest 的 20 个核心用例，其中用例 20 仅核对真实 API 权限与源状态。统一桌面 `1440×900`、手机 `390×844`；真实 Vue、真实 API，仅下表明确指定的响应延迟/失败、上传暂停与浏览器固定时间。
- `serve_web.py` 提供正式构建产物与真实 API 的本地代理；`prepare_before.py`、`capture_baseline.py` 用于 PR base 基准截图；`fixture-input.json` 只含匿名主数据 ID。

`SCM_UI_API_BASE`、`SCM_UI_BASE_URL`、`SCM_UI_FIXTURE_INPUT`、`SCM_UI_FIXTURE`、`SCM_UI_SCREENSHOTS` 由启动脚本传入。`SCM_UI_USERNAME` 与 `SCM_UI_PASSWORD` 只能由运行进程环境提供，不能写入 manifest、命令文本或产物。`SCM_UI_CHROMIUM` 可指定 Chromium 路径；默认由 Playwright 使用安装的配套浏览器。浏览器依赖由 CI 安装，不加入生产 npm 依赖。

准备与执行（均在隔离服务就绪、后端与前端修复完成之后）：

```bash
python prepare_fixtures.py
python -m unittest -v test_audit_ui
```

可先检查语法与发现数量，不启动浏览器：

```bash
python -m py_compile harness.py prepare_fixtures.py test_audit_ui.py fixtures/domain_seed.py fixtures/identity_seed.py
python -c 'import unittest; print(unittest.defaultTestLoader.loadTestsFromName("test_audit_ui").countTestCases())'
```

## 真实数据链

主体/用户/款式均为匿名主数据 `1`；同码异色款式 SKU `12/21`；物料 SKU `101`；物料仓 `1`、成品仓 `2`、两个工厂及工厂仓 `3/4`。准备脚本校验同尺码、不同颜色。

准备脚本创建 DEV 核价草稿、PROD+BOM 生效包，继续创建并审核需求订单 → MRP → 采购单 → 采购入库过账，再排出并审核 `OUTSOURCE`、`BUY` 各一单。另建主仓草稿盘点与工厂仓已审核盘点，供状态对照；第二份有不同成本的 PROD 草稿用于竞态，独立历史款/颜色用于快照。`fixtures/identity_seed.py` 经真实角色、菜单、用户接口建九个身份：六种审批访问身份、无成本编辑者、仅手机身份、独立仓管，密码复用运行进程环境、不落盘。仅手机身份初始只有 `production:mobile:list`，用例 19 再精确授予两个共享报工/异常按钮 ID，始终不分配桌面菜单祖先；独立仓管只有 `inventory:doc:list/add`，不含采购、库存审核或 ALL 权限。所有单据经项目接口创建；不读取生产业务数据。

## 20 个核心用例

每项运行时写入自己的 `test_XX_…/evidence.json` 与下列 PNG。单号/数量/金额均来自匿名 fixture。导出测试另存实际 XLSX，并记 SHA-256。所有浏览器 context 收集并**断言 console error、未处理 pageerror、外域请求均为零**；业务错误通过真实 R 错误码或标注的 HTTP 200 故障 envelope 展示，不把浏览器内部网络错误忽略成通过。

| 用例 / 原计划 | 核心断言 | 截图名（省略 `.png`） |
|---|---|---|
| 01 / U1 | 异色同 M 码的两行 `price/lineDueDate/remark` 不同，价格 `12.3456/23.4567`；只改单头后逐 SKU 精确回读不变。远端先保存、本地后保存得 `1002`，本地输入及远端数据均保留；三种金额容量超界得 `400` | `mixed-sku-before`、`mixed-sku-saved`、`mixed-sku-reopened-line-values`、`order-1002-keeps-local` |
| 02 / U2 | **仅成本 GET 注入失败 envelope**；保存说明发送 `cost:null`，真实成本完全保留；重试真实 GET 成功。BOM 料卡随鼠标/键盘聚焦，主图缺失有空态 | `cost-error-with-retry`、`cost-retry-success`、`bom-keyboard-card-empty-image` |
| 03 / U3 | 盘点实盘 `17.125` 切页签仍在，关页签提示。真实 `+1/-1` 流水净量为零但 revision 变化，提交 `1002`；重取提示清空有过进出的行，回读实盘 null/revision 增加。提交后 `+1` 正常收发不被审核覆盖；反审留着实盘、revision 对齐后能直接再提交 | `count-close-unsaved-warning`、`count-dirty-survives-activation`、`count-refresh-clears-warning`、`count-refresh-cleared` |
| 04 / U4 | 订单/采购/盘点各两状态，`DRAFT + 多单号` 查询与 XLSX 单号全集相等；搜索框改字未 Enter，导出仍用成功查询快照；订单勾选导出保留条件且只含选中行 | `orders-applied-filter`、`purchases-applied-filter`、`counts-applied-filter` |
| 05 / U5 | 真实登录：内部 query/hash 正常回跳；`//`、外域绝对 URL、脚本协议、反斜杠、控制字符、重复 redirect 数组安全回首页；无外域请求/脚本执行 | `redirect-internal` 等 7 张；不截登录凭据 |
| 06 / U6 | 采购来源入库同动作重放返回原 ID、不重复库存；同键异体 `1002`，流水价/额/库存单位非空且 RECORDED。回货先 `route.fetch()` 真实落账，**再扣留成功结果并回失败 envelope**；未知结果关窗重开后，UI 重试同 UUID+原 payload 仅记一次，新 UUID 的真实第二批再记一次。查看真实 MRP/采购/入库/BUY 页面并核对单号 | `receipt-outcome-unknown`、`receipt-retry-recorded-once`、`real-flow-mrp`、`real-flow-purchase`、`real-flow-inventory`、`real-flow-buy-production` |
| 07 / U6 | 两会话改节点计划：独立 `planRevision` 增加且单头 version 不变，旧计划 `1002`、输入保留；管理员账号在手机 390 px 页面无横向溢出，异常入口存在。仅手机权限由用例 19 单独覆盖 | `node-plan-conflict-keeps-local`、`node-plan-date-controls-contained`、`mobile-390-no-horizontal-overflow` |
| 08 / U7 | 真实物料 1/200 编码成功、201 编码 `400`；Excel 换行粘贴归一为空格；真实空列表和超限错误有反馈，带空格物料名恢复成功 | `multi-code-paste-results`、`real-query-empty-state`、`201-codes-error-feedback` |
| 09 / U8 | 浏览器固定 UTC `2026-12-31T16:30:00`，上海/UTC/洛杉矶的新单业务日均为 `2027-01-01`；今天/近7天/近30天/本月实际请求字符串一致。只改浏览器时间、不写单据 | 每时区 `default-date-…`、`range-month-…` |
| 10 / U9 | **暂停一个真实文件 POST，释放后仍交给后端处理**；pending 时保存必须禁用或等待，不能先 PUT 丢附件。释放后真实文件入库、整包保存回读工艺单文件数 +1（资料包「工艺单」页签，RFC 011） | `pending-upload-save-protected`、`upload-completed-and-persisted` |
| 11 / U2 | 真实成本 A 响应先扣留，切到成本不同的 B；B 加载与本地说明可编辑，A 迟到后 B 的单价、说明不变，保存与真实回读均不串包 | `current-pack-before-old-cost-arrives`、`late-cost-discarded-current-pack-saved`、`other-cost-row-complete-and-contained` |
| 12 / U1 | 三个 SKU（两个 M、一个 S）：批改控件输入但不点应用，保存完全不变；明确应用 M 单价只动两个 M；整款应用只改价格，所有行交期/备注不动，四位精度保留 | `sku-batch-input-not-applied`、`explicit-M-price-applied`、`explicit-whole-style-price-saved` |
| 13 / U9 | 真实文件已落服务器但响应延迟；仅 pending 上传也触发换版本放弃确认。明确放弃 A 后切 B，再送达成功响应；保存 B 后 A/B 的工艺单文件集合均不增加，B 未保存说明不被覆盖 | `upload-pending-is-dirty-before-discard`、`late-upload-ignored-after-version-discard` |
| 14 / U9 | 订单/采购/生产三域 × 六身份：SELF 审核权限不等于 ALL，制单人见自单，ALL只读无审核，无 list 配置规则/提交均阻止；指定人轮到本级时进入真实待我审核列表→详情→审核，完成后对象例外消失。有订单查看权但无变更查看权时，变更API直接403、页面不发该请求并显示明确权限说明，审批不受影响。订单未实现评审聚合显示不可用，权威审核 API 正常校验 | 每域 `…-designated-in-real-list`、`…-designated-can-audit`、`…-approval-left-pending-list`；`order-approver-change-permission-explained`、`order-review-unavailable-authoritative-audit-remains` |
| 15 / U2 | 仅包编辑无成本权限：成本 GET 403，详情 unitCost 不泄漏；真实伪造非 null cost 的 PUT 不改变成本；UI 无核价 tab，合法说明保存发 cost=null | `pack-editor-without-cost-authority` |
| 16 / U2 | 独立款已生效后停用一个颜色、改款名/色名，再发布新版本使旧版 VOIDED；旧详情仍 SNAPSHOT 且色码/名称一致；UI 打开作废的旧版本（导出 PDF 11-18 去掉了，RFC 011） | `voided-pack-with-frozen-context` |
| 17 / 逐页补验 | 打开其余已修改列表：款式/供应商/核价/MRP/BUY、库存明细/汇总/日志、待到料及各类型出入库；打开并取消款式/供应商/采购/出入库/物料选择器、SKU/证照/变更/排单/报工/异常表单。每页核对真实 GET；核价长款号在缩略图旁须完整单行显示，文本边界不越过单元格/裁剪祖先；款号预览等待真实成功/业务拒绝结果、附件区及供应商页脚单独拍图；MRP选订单的单号和状态必须一行完整且不溢出。库存日志实际浏览器导出并解析XLSX，含五个估值/冲销列及RECORDED/UNKNOWN；整例断言零写请求。上游提醒接口真实404展示不可用并可重试，不伪装为空待办 | `styles-list`、`supplier-new-open-cancel`、`costing-list`、`stock-material-summary-custody`、`stock-ledger-valued-or-unknown`、`inventory-list-…`、`inventory-new-…`、`order-schedule-four-place-price`、`upstream-real-unimplemented-error` 等 |
| 18 / U9 | 首页三个聚合真实返回404时分别显示不可用和重试；不显示零概况或“暂无待办/交期/提醒”。逐一重试仍观察真实404及明确失败态，不伪造成功空列表 | `home-real-unavailable-without-fabricated-zeros`、`home-independent-retries-remain-explicit` |
| 19 / S-P1-08、T09 | 独立 SELF 仅手机身份在 390 px 从 `/` 跳我的跟单，列表只含本人已下单/生产中单；本人详情可读、他人详情及报工 `404`，桌面 list/counts `403`。只读时节点禁用，桌面入口隐藏；已有桌面权限仍能原入口跳详情。精确增加 node/issue 权限后经 UI 实际报 START、上传匿名 PNG 并上报异常，真实读回节点/照片/异常且 `planRevision` 不变；待审核单审核和回货仍 `403` 且数据不变 | `mobile-desktop-authority-keeps-entry`、`mobile-only-owned-active-list`、`mobile-only-read-only-detail`、`mobile-only-foreign-detail-denied`、`mobile-only-real-start-form`、`mobile-only-start-persisted`、`mobile-only-anonymous-image-issue-form`、`mobile-only-issue-persisted` |
| 20 / A-Q1 | 专用 SELF 仓管只有库存 add/list，没有采购或审核权限。不同采购员的 DRAFT/PENDING 源预填/生成 `1000` 且不写库；APPROVED 源可预填并建草稿；无 add 的 GET/POST 和无 audit 的 `post:true` 均 `403`，单据数/库存/源版本不变。管理员真实部分入库后源为 `RUNNING`（部分到货），仓管仍可预填余量，采购详情仍 `403` | 仅 `evidence.json` 的实际 API 方法/路径/业务码、源状态、单据计数；不声称 UI 截图或全部状态穷举 |

注：用例 06 是“真实落账后成功结果不可见”的确定性 UI 故障，不声称验证了浏览器真实网络超时。用例 07 的第二会话通过真实 API 发出，不声称两个浏览器都操作了计划弹窗。没有全局 API mock。

需要新前端的稳定观察点：`cost-load-error`、`cost-buy-price`、`techpack-version-${id}`、`bom-inspector`、`bom-row`、`bom-row-card`、`order-batch-price`、`order-apply-price`、`order-size-price-${size}`、`order-apply-size-price-${size}`、`audit-for-me-toggle`。BOM 行需可键盘 focus。其他选择器依据现有 Vue 的角色、标签、路由或类名；修复后仅在真实 DOM 已变更时调整，不为让测试通过弱化业务断言。

## 仍需补验，不能标为已覆盖

- U1：null 单价、选项缺失/迟到响应、多款新增/移除、数量总容量边界、变更单六状态详情。显式整款/M 码批改已有用例12；null行交期/备注已有用例1/12，不代表null单价。
- U2：同一包按颜色切换竞态、真实主图/坏图片、LEGACY 无快照的老版本（11-17 起不再显示提示）。无成本授权、跨包竞态、停用历史色/VOIDED快照已有用例11/15/16；BOM料卡仍只验无图和一个固定料行。
- U3：盘点加行/导入后的基准、历史 PENDING 无 revision 的拒审、两个用户同时提交，以及刷新/关闭浏览器提示。
- U4：组合负责人/日期/品牌、查询失败和过期响应的快照、清空/重置、采购/盘点勾选导出。现有用例覆盖状态＋多单号＋未提交关键字以及订单勾选。
- U5：真实会话过期 `401` 后的重登路径；已有 7 类回跳不代替这个场景。
- U6：真实网络超时/断连；显式开启新动作。用例 19 覆盖手机实际 START/图片异常与计划 revision 不变，不代表手机 FINISH、重复报工或异常处理全流程。
- U7：物料空单位导入 `1003`、MOQ/包装取整、采购退货反审上限错误清单；批量查询大样本性能/EXPLAIN 必须由性能验证另记，不能把 200 码返回当 SLA。
- U9：上传失败及重试、其他未覆盖图片入口；上传期间放弃版本/迟到响应已有用例13，六身份三域权限与真实审批入口已有用例14，手机图片异常另在用例19。首页board仍按API06明确未实现；用例18只验证真实失败与独立重试，不把三列表入口说成完整首页待办。
- A-Q1：用例20仅覆盖采购入库来源的 DRAFT/PENDING/APPROVED/RUNNING（部分到货）与最小权限交叉；DONE（已到齐）、CLOSED、VOIDED、采购退货和生产领退料的完整状态矩阵未在该例穷举，不能登记为全部源类型/状态均已验收。
- CI 断言通过后仍须逐张检查 PNG 的遮挡/错位/溢出，并将实际 run/commit 与证据绑定；没有截图与实际响应证据时不能将其登记为浏览器通过。

## 产物保护

不启用 HAR、Playwright tracing、video、storage_state；不保存 API 请求体/响应体或 HTTP headers。JSON 写入拒绝凭据字段和值；console 文本替换已知秘密与 Bearer，URL 仅保留路径。密码填入/点击登录失败的 Playwright call log 也先脱敏再交给 unittest。截图遮住密码与用户名输入；截图路由仅记 pathname。合成业务 payload 只在内存或匿名 manifest 中使用。

失败时尽力保存 `failure-state.png`，JSON `passed=false`；子用例失败也计入。发现数量为 20 不代表运行通过。已过账单据不在测试末尾逆向清理，隔离数据库由主代理服务生命周期脚本统一销毁。
