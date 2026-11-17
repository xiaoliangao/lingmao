# PR #2 / PR #3 整改与销项验收

## 1. 基准、交付范围与当前状态

基线是 `main@1e126db5fe2701251bb3cf3f71d3c94288b6b37f`，仓库没有 `dev`。整改分支为 `fix/audit-remediation`，提交到 [PR #4](https://github.com/xiaoliangao/lingmao/pull/4)，不直接修改主分支。原始问题来自 [PR #2](https://github.com/xiaoliangao/lingmao/pull/2) 的 [architecture-audit.md](architecture-audit.md) 和 [PR #3](https://github.com/xiaoliangao/lingmao/pull/3) 的 [scm-full-audit.md](scm-full-audit.md)；两个原 PR 的评审评论均为空。下文用 `A/`、`S/` 区分两报告的同名编号，原文和历史定位不改写。

**工程验证通过，PR 保持 Draft。** 已验证代码为 `61ea1c542ba8448cecefc8684f2031530d3df49d`，对应[完整绿色运行](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997)。464 个 Java 单元测试、121 个隔离库集成测试、19 个前端测试及 20 个 API/浏览器用例全部通过；125 张原始 PNG 已逐张打开。原 44 项中，38 项按表内限定范围通过、4 项仅后续阶段文档契约通过；S/P0-01 与 S/P1-09 的真实切换和启用门禁仍为“云端无法验证”。完整首页聚合未实现，S/P1-08 只登记契约及当前审批/SELF入口的范围。最终状态和证据集中列在第 3–6 节。

按当前用户授权与 `CLAUDE.md` 的默认决策规则执行，实施方案是 [RFC010](../rfc/010-审查整改契约.md)。没有取得原名《领猫SCM模块字段清单.xlsx》；沿原两份审查报告已声明的附件与只读取证边界，不把替代材料说成原件，也不声称本轮再次验证参考系统。所有运行测试使用隔离的合成数据，没有操作生产系统。

本轮保留模块化单体、已存在的单据头行、SPU/SKU、精确数值、状态动作与首期功能；这些不是本 PR 从零新增。完整样衣、成品库存、财审结算、助手、外部对接和 BI 不扩写。完整首页/评审聚合仍是原计划后续；已存在的失败入口显示不可用/重试，不能伪装为零待办。依据：`docs/PRD.md` §2、`docs/进度.md`、RFC010 §4.2、§6–7。

## 2. 设计取舍与变更顺序

| 决策 | 本次落实 | 主要契约 / 实现 |
|---|---|---|
| 旧未结业务采用 A 分流 | 旧系统收尾；新系统只接新需求。旧采购在途不是期初实存，旧占用不能同时进入新单公共可用量。真实切换清单和签认另验 | `docs/PRD.md` §10；RFC010 §1；本轮没有余额迁移引擎 |
| 库存先来源、后余额 | 正反过账同事务、同锁顺序；反审也校验净收货上限；MRP 先锁采购头再改挂明细 | RFC010 §2.1；`StockSourceHandler.preparePosting`、`InvDocServiceImpl`、`MrpServiceImpl` |
| 盘点采用短窗口重数 | 账面数量和流水水位同时拍取；净量不变的收发也使旧计数失效；刷新清实盘，提交后固定差额 | RFC010 §2.2；V26；`CountServiceImpl`、`StockLedger` |
| 新动作有独立身份 | 来源建单与回货用 UUID；同体重试回原结果，异体冲突；客户端未知结果保留原体与 ID | RFC010 §3；`biz_action_request`；`ActionRequestServiceImpl`、`clientAction.ts` |
| 保存和历史事实分开 | SKU 原值无损；成本未授权/未加载不覆盖；发布包冻结色码上下文；库存流水保存过账价和冲销来源 | RFC010 §2.3、§4–5；`PackOwnerSnapshot`、`orderSku.ts`、`TechPackTab.vue` |
| MRP 保留限定读投影 | 只允许指定两 Mapper 读上游；可算状态和 SKU 完整性仍经 Service。快照不承诺完整历史占用重放 | RFC010 §4.3；`MrpEngine`、`MrpCalcMapper.xml`、`SupplyMapper.xml` |
| 工厂仓为净托管账面量 | 不把回货等同真实耗料，工厂仓不参与公共可用供应 | RFC010 §2.3；`docs/api/04-库存.md`；库存页与 `SupplyMapper.xml` |
| 审批集与普通归属分开 | `auditForMe` 仅当前级/当前人；指定审批人需模块 list 入口；audit 不授 data:all | RFC010 §6；`ApprovalAccess` 和三单据列表 |

### 2.1 提交序列

| 阶段 | 修复 Commit SHA | 内容 |
|---|---|---|
| 契约 / DB | `a3c7196fd65d5dfca04b51569b6ec6de3d2da098` | PRD、架构、DB、API01–06、RFC、计划、部署手册；兼容新增 V26 |
| 数据层 / 接口 | `39396743acd8749424dbb54673802bc8267a7de6` | 来源事务、盘点、幂等、快照、权限、数值与服务回归 |
| 终态 / 锁序补验 | `1d3308516eb64e4bb61e063e548005a25ba823bf` | 双向锁探针、六种非 SKU 终态变更、BUY 最小链路 |
| 前端 / CI | `da06a92a006304f3e54f8943fcc6958d6ede770f` | 编辑状态、查询、日期、类型、上传、原型、通用 Actions 与真实 UI/API 门禁 |
| 测试隔离 | `bac099dda134375d13a14e9f2d128d25659bc709` | 清理 Style IT 本例品牌，不放宽 Schema 断言；异步测试修正 |
| 浏览器启动 / 补覆盖 | `13fa7a537970cef54b7724adb5a5e8a6c9980209` | Playwright 管理 Chromium、独立门禁继续执行、上游真实失败态、逐页开关弹窗 |
| 共享在途修复 | `d32bd15e95eed5d6bf29947c0c1f46f786e7b59a` | 其他单的在途占用扣除已分到的净到货，避免重复占用；真实 PG 红绿回归 |
| 截图复核后的修正 | `c134b44df6643ba6e5fae3e12f9dc897eedd60c7` | 真 UI 完成状态取证；回货、节点日期、费用控件局部版式与几何断言 |
| 契约补正 | `2b45916fac92cd151d9189cb08c89be4b9753178` | 清除残留旧在途导入/盘点导入冲突，手机入口权限与API正文一致 |
| 流水导出 / 测试隔离 | `2ff9117062646a7c51a5c84bdd958a695dd14748` | 五列估值/冲销事实导出；颜色与设计师fixture唯一认领，约束原样保留 |
| HTTP动作身份 | `8b7846c9eaf2c6de9ac89db4684f21112b9a5914` | 缺randomUUID时使用getRandomValues生成v4，新增两条红绿回归 |
| 失败态 / 角色交叉 | `327b734ca621edfa22ac4d130f315c70f4e30f35` | 首页独立失败/重试，仅手机入口保护，C18–20及日志真实导出验证 |
| 实图复核补正 | `793e5ed53b30671b42f41bfb96c8dcdc947eaaf2` | SKU标签字段、订单辅助请求权限与MRP状态截断；等待刷新响应和弹窗收起后取证，保留严格内容边界验证 |
| 实图长款号补正 | `61ea1c542ba8448cecefc8684f2031530d3df49d` | 核价列表缩略图旁长款号完整显示；增加文本裁剪边界断言，确认框入场完成后取证 |

各阶段保留独立提交。V1–V25 不变，V26 只增加列/表/约束；发布时须停止旧写实例，避免旧程序不维护流水水位。验证脚本提交到 `dev/ci/`；运行日志、测试产物、截图和临时导出受 `.gitignore` 排除，仅作为 CI artifact 提交验收。

### 2.2 Schema → Java / API → TypeScript 对照

| 存储 | 服务端 / API | 前端 | 兼容与验证 |
|---|---|---|---|
| `inv_stock.movement_revision`、`inv_count_item.book_revision` | `Stock.movementRevision`、盘点明细 `bookRevision` | `web/src/types/inventory.ts` | 旧计数水位 NULL 不默认放行；ABA / refresh / legacy pending 真库验证 |
| `prd_order.plan_revision` | `ProductionDetailVO.planRevision`、`NodePlanReq.planRevision`、`PUT /api/production-orders/{id}/nodes` → `{planRevision}` | `web/src/types/production.ts`、`web/src/api/production.ts` | 独立于单头 version；同用户两设备旧计划返回 1002 |
| `biz_action_request.client_action_id` | `InvDocFromSourceReq.clientActionId`、`ReceiptReq.clientActionId` | `web/src/types/inventory.ts`、`web/src/types/production.ts` | UUID 必填；同用户/动作唯一；同体重试、异体冲突、事务回滚 |
| `inv_stock_log.unit_price/amount/stock_unit/currency/valuation_status/reversal_of_log_id` | `StockLogRowVO` 对应 camelCase 字段 | `web/src/types/inventory.ts`、库存日志页 | NUMERIC / BigDecimal；UNKNOWN/null 保留；反向引用原事件 |
| `tp_tech_pack.owner_snapshot` | `PackOwnerSnapshot`；详情 `contextSource/contextNotice`、冻结 colors/sizes | `web/src/types/techpack.ts`、资料包 PDF 颜色选择 | 新发布 SNAPSHOT；旧版 LEGACY；不倒造未知历史集合 |

准确表定义为 `src/main/resources/db/migration/V26__audit_remediation.sql:7–81`；接口正文为 `docs/api/01-资料包.md`、`04-库存.md`、`05-生产.md`，DB 正文为 `docs/数据库设计.md`。金额、税率、损耗和换算的持久化精度沿既有 NUMERIC；新增值不引入浮点列。订单行金额预览及容量判断以 BigInt 分计算，后端 BigDecimal 仍是入账权威（`web/src/utils/orderSku.ts`、`common/num/NumericLimits.java`）。

## 3. 原问题销项表

“通过”仅表示该行写明的代码/文档范围和列出的测试通过；不覆盖未列出的所有排列组合，也不表示生产启用。后续范围 P2 明示“文档契约”，不冒充已实现功能。状态以最后一次云端结果和人工截图核查为准；不得用本地绿或测试定义代替运行证据。

### 3.1 PR #2：架构与实现（21项）

| 原编号 / 问题 | 当前状态与范围 | 依据与修复定位 | 验证与边界 | 修复提交 |
|---|---|---|---|---|
| A/P1-01 正反过账 / MRP 锁序 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §2.1；src/main/java/com/lingmao/scm/module/inventory/service/impl/InvDocServiceImpl.java:565–598；src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpServiceImpl.java:754–793 | src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java：postingWaitsForSourceBeforeTakingStockLock、recalcWaitsForPurchaseHeadBeforeRepointingItsItems | a3c7196 / 3939674 / 1d33085 |
| A/P1-02 退货反审净收上限 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §2.1；src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:176–208 | src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java：reverseReturnAfterReplacementCannotBreakReceiptCap_andRollsBackAll；合法逆序单测 | a3c7196 / 3939674 |
| A/P1-03 逐 SKU 无损往返 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；docs/api/02-需求订单.md §2.3；web/src/utils/orderSku.ts:12–26；web/src/views/order/order/form.vue:178–234,386–434 | web/tests/audit-domain.test.mjs：web/tests/audit-domain.test.mjs:17–43；UI C01/C12，真实 PUT/GET 保价格、交期、备注；编辑逐SKU标签使用款式选项code，不混用订单行skuCode，C01真实DOM断言 | a3c7196 / da06a92 / c134b44 / 793e5ed |
| A/P1-04 成本失败不覆盖 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.1；web/src/views/style/style/TechPackTab.vue:154–202,338–369；src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java:259–290 | UI C02/C11/C15；src/test/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImplIT.java：costAuthorizationPreservesHeadAndChildren | a3c7196 / 3939674 / da06a92 / c134b44 / 793e5ed |
| A/P1-05 盘点 KeepAlive 草稿 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/views/inventory/count/detail.vue:73–105；web/src/composables/useUnsavedChanges.ts:28–38,68–110 | UI C03：实盘脏值返页保留、关闭取消；未逐字段穷举全部备注/删行组合 | da06a92 / c134b44 / 793e5ed / 61ea1c5 |
| A/P1-06 列表与导出同条件 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；docs/api/README.md 查询/导出；web/src/composables/useTable.ts:53–74,96–117；web/src/utils/query.ts:2–15 | web/tests/audit-domain.test.mjs:61–75；UI C04：三域 DRAFT/多单号列表与真实 XLSX、未应用输入、订单选中导出 | a3c7196 / da06a92 / c134b44 |
| A/P1-07 非 SKU 终态变更可读 | 通过（限定本行验收范围）；代码 / 运行 | docs/api/02-需求订单.md 变更详情；原 A/P1-07 六组合；src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:215–228 | src/test/java/com/lingmao/scm/module/order/service/impl/OrderIT.java：finalizedNonSkuChangeDetailsRemainReadable，DUE/PRICE/TECH_PACK × APPROVED/VOIDED | 3939674 / 1d33085 |
| A/P1-08 实际色码与 BOM 准入 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.2；src/main/java/com/lingmao/scm/module/techpack/service/impl/RequirementChecks.java:23–68；src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java:89–96 | src/test/java/com/lingmao/scm/module/techpack/service/impl/RequirementChecksTest.java；PurchaseIT.mrpRejectsDisabledMaterialSkuBeforeWritingCalc；正常链 C06 | a3c7196 / 3939674 |
| A/P1-09 限定跨域读投影 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.3；src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:797–804；src/main/resources/mapper/purchase/MrpCalcMapper.xml:49–74；src/main/resources/mapper/purchase/SupplyMapper.xml:7–16 | ArchitectureTest / TableOwnerTest；PurchaseIT.mrpConvertAuditReceiveRecalc、准入拒绝；保留命名例外，不声称 SQL 与上游完全无耦合 | a3c7196 / 3939674 |
| A/P1-10 登录回跳校验 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/utils/redirect.ts:1–16；web/src/views/login/index.vue:26–29 | web/tests/audit-domain.test.mjs:95–101；UI C05 七类实际登录，内部 query/hash 保留，外部请求为 0 | da06a92 / c134b44 |
| A/P1-11 备份账号只读隔离 | 通过（限定本行验收范围）；文档 / 合成环境运行 | docs/rfc/010-审查整改契约.md §7；部署手册异地备份；deploy/package/部署手册.md；dev/ci/check-backup-readonly.sh | Actions 临时低权限 SFTP：下载成功，写/删/改名/命令/PTY/转发/穿越/符号链接逃逸拒绝；真实主机配置另验 | a3c7196 / da06a92 |
| A/P2-01 导入空单位行列错误 | 通过（限定本行验收范围）；代码 / 运行 | docs/api/README.md 错误码 1003；src/main/java/com/lingmao/scm/module/basic/service/impl/MaterialImport.java:378–384 | src/test/java/com/lingmao/scm/module/basic/service/impl/MaterialImportTest.java：importFile_missingBothUnits_reportsRowAndWritesNothing | 3939674 |
| A/P2-02 MOQ 与整包量同时满足 | 通过（限定本行验收范围）；代码 / 运行 | docs/PRD.md §6.6.1；原 A/P2-02；src/main/java/com/lingmao/scm/common/num/Num.java:82–93；docs/原型/src/2-store.js | src/test/java/com/lingmao/scm/common/num/NumTest.java:35（suggestPurchase_packMoqAndRatio）；实际净需1/MOQ10/整包6得12，另有小数包装/零需求/换算误差；原型自检 | da06a92 |
| A/P2-03 1002 冲突可恢复 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/components/doc/docUtils.ts:61–69；web/src/views/order/order/form.vue:289 | UI C01/C07 保本地不覆盖远端；明确放弃重载入口存在，未穷举全部弹窗冲突分支 | da06a92 / c134b44 / 793e5ed |
| A/P2-04 东八区纯日期 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/utils/businessDate.ts:4–40；web/src/components/DateRange.vue:15–20 | web/tests/audit-domain.test.mjs:76–94；UI C09 上海/UTC/洛杉矶固定跨年时刻及四种真实查询快捷区间 | da06a92 |
| A/P2-05 BOM 款图 / 行料卡 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；原 A/P2-05；web/src/views/style/style/TechPackTab.vue:953–969,1019,1070 | UI C02 hover/focus、无图占位；复用当前主图，不宣称历史图片快照 | da06a92 |
| A/P2-06 删除、恢复与进度旧口径 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §7–8；原 A/P2-06；docs/架构设计.md；docs/进度.md；deploy/package/scripts/check-restore.sh:69–94 | 源码/正文对照；dev/ci/check-restore.sh 真 PG 恢复；合法当前行数下降不误报损坏 | a3c7196 / da06a92 |
| A/P2-07 工具锁定与完整门禁 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §8；.github/workflows/ci.yml；web/package.json；web/eslint.config.mjs；pom.xml；web/src/views/techpack/costing/index.vue:139,194–199 | 安装、V26、单测/IT、严格 TS、Lint、19 Node、构建、健康、原型、UI/API；Schema 原断言未放宽；C17核价长款号文字边界与完整显示 | da06a92 / bac099d / 13fa7a5 / c134b44 / 2ff9117 / 8b7846c / 327b734 / 793e5ed / 61ea1c5 |
| A/Q1 仓管来源独立作业视图 | 通过（限定本行验收范围）；契约已定 / 验证边界 | docs/rfc/010-审查整改契约.md §4.3；docs/api/04-库存.md 来源权限；src/main/java/com/lingmao/scm/module/inventory/controller/InvDocController.java:127–167；src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseStockHandler.java:72–94 | C20 最小真实API交叉：SELF仓管非采购负责人，DRAFT/PENDING拒绝、APPROVED/RUNNING（部分到货）来源可读、无add与自动过账403；未穷举所有来源状态 | a3c7196 / 3939674 / 327b734 |
| A/Q2 保留合法逐 SKU 差异 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/utils/orderSku.ts:12–26；web/src/views/order/order/form.vue:178–234 | 同 A/P1-03；不收紧既有合法数据入口 | a3c7196 / da06a92 / 793e5ed |
| A/Q3 上传等待与迟到隔离 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/utils/pendingTasks.ts:2–30；web/src/composables/usePendingUploads.ts:18–42；web/src/components/ImageUpload.vue:58–103 | web/tests/audit-domain.test.mjs:161–186；UI C10/C13 真实上传暂停、等待保存、放弃后迟到不串版本；其他接入表单只开关验证，不冒称全部上传入口穷举 | da06a92 / c134b44 |

### 3.2 PR #3：SCM 设计与业务（23项）

| 原编号 / 问题 | 当前状态与范围 | 依据与修复定位 | 验证与边界 | 修复提交 |
|---|---|---|---|---|
| S/P0-01 旧单分流与期初交割 | 云端无法验证（真实启用门禁）；设计已定 / 真实启用门禁 | docs/rfc/010-审查整改契约.md §1；docs/PRD.md §10；docs/PRD.md:2163；docs/rfc/010-审查整改契约.md:9–15 | 方案 A 与合成反例已写明；真实截止、期初分界、交割防重及负责人签认未执行 | a3c7196 / 2b45916 |
| S/P1-01 成本加载 / 字段权限 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.1；src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java:259–290；web/src/views/style/style/TechPackTab.vue:154–202,338–369 | TechPackServiceImplIT 成本头/子表；UI C02/C11/C15，无权伪造忽略、null保留、合法清空单测 | a3c7196 / 3939674 / da06a92 / c134b44 / 793e5ed |
| S/P1-02 订单矩阵字段无损 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/utils/orderSku.ts:12–26；web/src/views/order/order/form.vue:178–234,386–434 | 同 A/P1-03；web/tests/audit-domain.test.mjs:17–43 + UI C01/C12；编辑逐SKU标签使用款式选项code，不混用订单行skuCode，C01真实DOM断言 | a3c7196 / da06a92 / c134b44 / 793e5ed |
| S/P1-03 盘点测量截止 / ABA | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §2.2；src/main/resources/db/migration/V26__audit_remediation.sql:7–16；src/main/java/com/lingmao/scm/module/inventory/service/impl/CountServiceImpl.java:384–407,493–515,582–622 | InventoryIT.countMeasurementRejectsAbaAndRefreshClearsCount、legacyPendingCountCannotPostWithoutMeasurementWatermark、countFlow；UI C03 | a3c7196 / 3939674 / da06a92 |
| S/P1-04 已执行后的减量处置 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；docs/PRD.md §需求变更；src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:927–940,999–1006；src/main/java/com/lingmao/scm/module/order/service/impl/OrderChangeServiceImpl.java:560–570 | OrderServiceImplTest.applyChange_checksScheduledAndStale、OrderChangeServiceImplTest.check_listsEveryProblem；ProductionIT 真实执行后反审保护。保留已排下界，不伪造余量调整单 | a3c7196 / 3939674 |
| S/P1-05 新动作 / 第二批幂等 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §3；src/main/resources/db/migration/V26__audit_remediation.sql:27–43；src/main/java/com/lingmao/scm/module/biz/service/impl/ActionRequestServiceImpl.java:34–86；web/src/utils/clientAction.ts:1–19 | ActionRequestServiceImplTest；PurchaseIT 并发来源重试/回滚；ProductionIT 回货重试；C06 浏览器重试原 UUID/原体只记一次；真实 API 用新 UUID 登记第二批。HTTP 错误包故障不等同浏览器网络超时；web/tests/audit-domain.test.mjs:102–160 验证安全随机UUID回退，未冒称非loopback HTTP浏览器实测 | a3c7196 / 3939674 / da06a92 / c134b44 / 8b7846c / 793e5ed |
| S/P1-06 不可变流水价值 / 未知成本 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §1、§2.3；src/main/resources/db/migration/V26__audit_remediation.sql:48–70；src/main/java/com/lingmao/scm/module/inventory/service/impl/StockLedger.java:110–167；web/src/views/inventory/stock/index.vue:422–436；src/main/java/com/lingmao/scm/module/inventory/service/impl/StockExcel.java:98–102,153–158 | InventoryIT.stockValuationRemainsImmutableAcrossRepriceAndReversal；UI C06 日志字段、C17日志页。新事件可解释；旧 UNKNOWN 不补造，实际期初估值另签认；src/test/java/com/lingmao/scm/module/inventory/service/impl/StockExcelTest.java 真实xlsx精度/UNKNOWN空值/冲销引用；C17真实浏览器下载 /api/stock-logs/export，解析XLSX五列与两种估值状态 | a3c7196 / 3939674 / da06a92 / 2ff9117 / 327b734 |
| S/P1-07 工厂净托管口径 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §2.3；docs/api/04-库存.md 库存口径；src/main/resources/mapper/purchase/SupplyMapper.xml:7–16；web/src/views/inventory/stock/index.vue:63,253,365 | ProductionIT.scheduleAuditReportIssueReceiveVoid、buyWithOnlyFactoryMaterials_scheduleAuditReceiveAndVoid_withoutIssuingMaterials；UI C17库存文案。BUY 全工厂料测试不推广为 BUY 一律免领料 | a3c7196 / 3939674 / 1d33085 / da06a92 |
| S/P1-08 SELF 与当前审批入口 | 通过（当前审批/SELF与首页失败态；完整聚合未实现）；API06契约 / 已实现审批入口；完整首页聚合仍原分期 | docs/rfc/010-审查整改契约.md §6；docs/api/06-首页.md；src/main/java/com/lingmao/scm/module/biz/service/impl/ApprovalAccess.java:12–27；src/main/java/com/lingmao/scm/module/order/service/impl/OrderServiceImpl.java:178–190,1073；src/main/java/com/lingmao/scm/module/purchase/service/impl/PurchaseOrderServiceImpl.java:190–193,982；src/main/java/com/lingmao/scm/module/production/service/impl/ProductionOrderServiceImpl.java:132–149,317；web/src/views/order/order/OrderAuditDialog.vue:43–56；web/src/views/home/index.vue；web/src/views/production/mobile/detail.vue；web/src/views/order/order/detail.vue:60,110–114,299,346–356 | ApprovalRule/ApprovalService/OrderIT；UI C14 三域六身份、规则/提交list前提、当前人列表→详情→审核。完整首页聚合仍后续，现有入口失败不伪装为0；UI C18 首页真实404/独立重试；C19仅手机身份SELF/接口拒绝/真实报工异常；C14无变更查看权时页面零变更GET及明确权限说明，直接API403，订单审批仍通过 | a3c7196 / 3939674 / da06a92 / 13fa7a5 / c134b44 / 2b45916 / 327b734 / 793e5ed |
| S/P1-09 实现 / 验证 / 发布退出门槛 | 云端无法验证（真实启用门禁）；工程门禁 / 真实启用门禁 | docs/rfc/010-审查整改契约.md §8；原 S/§8.3 L1–L8；.github/workflows/ci.yml；dev/ci/run-smoke.sh；docs/进度.md；本文§4–6 | 每轮当前 SHA 独立门禁、JUnit/API/截图/真实恢复；目标机与正式角色/切换业务验收不可由 CI 替代 | a3c7196 / 1d33085 / da06a92 / bac099d / 13fa7a5 / c134b44 / 2ff9117 / 327b734 / 793e5ed / 61ea1c5 |
| S/P2-01 历史包色码 / PDF | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.2；src/main/java/com/lingmao/scm/module/techpack/service/impl/PackOwners.java:86–120；src/main/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImpl.java:317–344；web/src/views/style/style/TechPackTab.vue:1324–1329 | TechPackServiceImplIT.publishedContextFreezesColorsSizesAndOwner_legacyIsExplicit、exportPdf；UI C16真实禁用/改名/VOIDED后API与下载PDF旧名保持 | a3c7196 / 3939674 / da06a92 / c134b44 |
| S/P2-02 导出与角标条件 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；web/src/composables/useTable.ts:53–74,96–117；web/src/views/order/order/index.vue:82–86 | 同 A/P1-06；角标去 status，浏览器不宣称穷举所有角标/筛选组合 | a3c7196 / da06a92 / c134b44 |
| S/P2-03 MRP 解释边界 / 共享在途 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §4.3；原 S/L3；src/main/java/com/lingmao/scm/module/purchase/service/impl/SupplyBook.java:88–107,164–174；src/main/java/com/lingmao/scm/module/purchase/service/impl/MrpEngine.java:121–129 | src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java:240（sharedTransit_partialNetReceiptsAndRecalc_allocateOnceByUseId）：300供应、A/B150、收200退100、B重算150、替换后占用仍300；同文件:111 mrpConvertAuditReceiveRecalc覆盖正净需转采购 | a3c7196 / 3939674 / d32bd15 |
| S/P2-04 二期主体物权 | 通过（文档契约；实现按原分期）；文档契约 / 后续分期 | docs/rfc/010-审查整改契约.md §7；docs/rfc/008-成品.md；docs/rfc/008-成品.md；docs/数据库设计.md；docs/rfc/010-审查整改契约.md:97 | 文档明确访问隔离不等于物权，启用前选公共/专属仓/余额键；未实施二期主体账 | a3c7196 |
| S/P2-05 节点计划独立版本 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §3；src/main/resources/db/migration/V26__audit_remediation.sql:19–23；src/main/java/com/lingmao/scm/module/production/service/impl/ProductionNodeServiceImpl.java:58–99；web/src/views/production/order/ProdNodeDialogs.vue | ProductionIT.nodePlanRevisionBlocksSecondDevice_butReportsDoNotChangePlanRevision；UI C07两会话1002、日期控件边界与390手机页 | a3c7196 / 3939674 / da06a92 / c134b44 / 793e5ed |
| S/P2-06 二期成品尾数 | 通过（文档契约；实现按原分期）；文档契约 / 后续分期 | docs/rfc/010-审查整改契约.md §7；docs/rfc/008-成品.md；docs/rfc/008-成品.md；docs/rfc/010-审查整改契约.md:98 | 一回货一有效入库必须一次处理全部良品；分次累计另批。未提前建设成品库存 | a3c7196 |
| S/P2-07 助手草稿不产生业务效果 | 通过（文档契约；实现按原分期）；文档契约 / 后续分期 | docs/rfc/010-审查整改契约.md §7；docs/rfc/009-智能助手.md；docs/rfc/009-智能助手.md；docs/rfc/010-审查整改契约.md:99 | 先独立提案，确认前不写现价；固定 post=false、受限身份；未新增助手执行工具 | a3c7196 |
| S/P2-08 外部事件独立身份 | 通过（文档契约；实现按原分期）；文档契约 / 后续分期 | docs/rfc/010-审查整改契约.md §7；架构对接边界；docs/架构设计.md；docs/数据库设计.md；docs/rfc/010-审查整改契约.md:100 | event 动作/版本/topic 与外部单号/回执分别标识；未实现对外发送 | a3c7196 |
| S/P2-09 正文 / 原型旧口径 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §1–5；原 S/P2-09；docs/PRD.md；docs/数据库设计.md；docs/进度.md；docs/原型/src/7-pages-master.js、5c-material.js、2-store.js | 原型打包与内置自检；尺码创建即锁、MOQ先下限再向包装取整、基础量/毛需求区分；相关 PurchaseIT/OrderServiceImplTest | a3c7196 / da06a92 / 13fa7a5 / 2b45916 |
| S/P2-10 四位单价输入 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；docs/api/02-需求订单.md 数值容量；web/src/utils/orderSku.ts:29–49；web/src/views/order/order/form.vue:386–434；web/src/views/style/style/TechPackTab.vue:1194–1207 | web/tests/audit-domain.test.mjs:45–49；UI C01/C12订单四位、C11资料包成本；排单/变更等控件静态精度和打开截图，不冒称每个控件都提交过 | a3c7196 / 3939674 / da06a92 / c134b44 / 793e5ed |
| S/P2-11 派生金额 / 累计件数容量 | 通过（限定本行验收范围）；代码 / 运行 | docs/rfc/010-审查整改契约.md §5；docs/api/02-需求订单.md；src/main/java/com/lingmao/scm/common/num/NumericLimits.java:9–39；src/main/java/com/lingmao/scm/module/order/service/impl/OrderExcel.java:101–109,165–179；src/main/java/com/lingmao/scm/module/production/service/impl/ScheduleServiceImpl.java:174–193 | NumericLimitsTest；OrderIT.orderCapacityRejectsJsonAndWholeExcelBatchBeforeWrites；Schedule/Receipt 溢出单测；web/tests/audit-domain.test.mjs:45–59；C01真实超界400 | a3c7196 / 3939674 / da06a92 |
| S/P2-12 恢复证据同快照 | 通过（限定本行验收范围）；文档 / 合成环境运行 | docs/rfc/010-审查整改契约.md §7；部署手册恢复；deploy/package/scripts/check-restore.sh:69–94；deploy/package/部署手册.md；dev/ci/check-restore.sh | 真 PG 恢复2行、精确总量3.375及约束；当前合法删除至1行不误报；损坏dump拒绝。目标业务余额/上传目录恢复另验 | a3c7196 / da06a92 |
| S/P2-13 实际查询计划 / 批量边界 | 通过（限定本行验收范围）；代码 / 运行 | 原 S/P2-13；docs/api/README.md 多关键词；src/test/java/com/lingmao/scm/module/order/service/impl/OrderIT.java：listCapacityExplainRecordsRealSql；src/main/resources/mapper/order/OrderMapper.xml:10–77 | 1万头+1万行、8组实际SQL EXPLAIN ANALYZE/BUFFERS；C08真实1/200/201多码、空态/400/恢复；不把单次执行时间当生产SLA | 3939674 / da06a92 / c134b44 |

## 4. 工程与运行证据

### 4.1 已执行的迭代

| 云端运行 / Head | 结果与实际修正 |
|---|---|
| [44129436026](https://github.com/xiaoliangao/lingmao/actions/runs/44129436026) / `da06a92` | 后端品牌预置断言 0/7 失败；定点复现为 Style IT 六个品牌及一个导入品牌泄漏。`bac099d` 按本例 ID 清理并清缓存；Schema 断言不改 |
| [44131838785](https://github.com/xiaoliangao/lingmao/actions/runs/44131838785) / `bac099d` | 后端 461 单测 +120 IT、JAR，前端严格类型/Lint/17 测试/build 通过；原型 raw Chrome 未及时得到 CDP page target，未记录 stderr，不能推断具体沙箱原因 |
| [44133530598](https://github.com/xiaoliangao/lingmao/actions/runs/44133530598) / `13fa7a5` | 上述门禁、原型、只读 SFTP、恢复、基线截图通过；17 UI 未通过。初始化脚本误在 about:blank 写 localStorage、隐藏 checkbox 与真实查询时序等问题，按失败证据修正，零错误断言不放宽 |

Run 4（[`c134b44`](https://github.com/xiaoliangao/lingmao/commit/c134b44df6643ba6e5fae3e12f9dc897eedd60c7)，[44136783341](https://github.com/xiaoliangao/lingmao/actions/runs/44136783341)）首轮在 InventoryIT 的随机三位颜色码碰撞，重跑又暴露 SchemaConventionsIT 的两位设计师编号碰撞；均为测试准备数据违反已有唯一约束。其余前端、原型、只读备份和恢复门禁通过，UI 因后端失败未执行。按失败证据修复候选值认领，不重跑到偶然成功后隐去失败，也不取消唯一约束或放宽断言。

Run 5首轮（[`327b734`](https://github.com/xiaoliangao/lingmao/commit/327b734ca621edfa22ac4d130f315c70f4e30f35)，[44141233229](https://github.com/xiaoliangao/lingmao/actions/runs/44141233229)）在Maven wrapper下载固定3.9.16发行包时失败，未开始后端编译/测试；前端19测试/类型/Lint/build、原型及两项运维门禁通过。没有把该下载错误报告成代码断言失败，同候选第二次运行已完成；不切换未锁版本或跳过测试。第二次后端464单测+121 IT、V1–V26/JAR及前端19测试/类型/Lint/build等门禁通过，20项回归16项通过。C01定位到SKU标签错误字段、C06读取被reload取消的旧GET、C11行外框精确视口比例、C17上传双button角色。108张原始PNG已逐张打开，另发现订单无变更查看权仍请求变更列表及MRP单号状态截断；`793e5ed`定点修正并复验。该失败run不是最终验收。

Run 6（`793e5ed`，[44145046486](https://github.com/xiaoliangao/lingmao/actions/runs/44145046486)）所有工程门禁及20用例通过，125张原始PNG已逐张打开。实图仍发现核价列表长款号末尾被缩略图挤掉；关闭未保存确认框的PNG也没有记录到实际模态帧，没有将该张作为视觉验收证据。`61ea1c5`局部修正列宽并补文本边界断言、确认框入场观察；不把测试绿色自动当作视觉通过。

Run 7（`61ea1c5`，[44148637997](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997)）再次通过全部门禁。长款号完整显示，盘点确认框与保留/清空后的状态已重新打开原图核对；本轮125张PNG的人工检查与实际JSON返回共同作为下表验收依据。

通用工作流为 `.github/workflows/ci.yml`：`ubuntu-latest`、Java 21、Node 24.22.1、pnpm 11.25.0、Python 3.13、PostgreSQL 16、Redis 7、真实 Chromium。每个失败均使 job 失败；独立门禁继续执行以保留有效失败证据，没有 `continue-on-error`，不绑定临时分支。

本地最新后端（流水导出与唯一 fixture 修正后）已跑 `mvn verify` 等效完整命令并倒序执行集成类：464 单测 +121 IT，0 failure/error/skipped，V1–V26 空库迁移与 JAR 生成通过。共享在途先红后绿：采购 300、A/B 各 150、收 200/退 100 后重算 B，修前可用在途 50，修后 150，建议采购 0；新草稿不释放旧占用，确认替换后有效占用仍 300。方法为 `PurchaseIT.sharedTransit_partialNetReceiptsAndRecalc_allocateOnceByUseId`，实现 `SupplyBook.heldTransit`。

### 4.2 运行证据如何读取

- JUnit XML：`target/surefire-reports/`、`target/failsafe-reports/`，不是手写统计。
- 查询计划：`target/audit-query-capacity.json`，真实 MappedStatement，1 万头 +1 万行，列表/计数各四组。单次合成 PG 执行不等于生产并发或页面 SLA。
- 真实服务健康：`test-artifacts/after/health.json`；准备与用例 API 状态：`fixture-evidence.json` 和各 `evidence.json`。请求/响应值断言在用例内执行，不把完整业务体或凭据写进 artifact。
- 浏览器 C01–C19 + 纯 API C20：`dev/ci/ui/test_audit_ui.py`；逐项覆盖与未覆盖边界见 `dev/ci/ui/COVERAGE.md`。故障注入只包括成本读失败、已成功写入后的结果替换/延迟、暂停上传、冻结浏览器时钟；其余走真实服务。
- 运行环境只允许 loopback、独立 `_test` 数据库；没有 HAR、登录 trace、storage_state 或生产截图。敏感字段拒绝持久化，报告上传前再脱敏；配置见 `dev/ci/run-smoke.sh`、`dev/ci/ui/harness.py`、`dev/ci/sanitize-artifacts.py`、`dev/ci/sanitize-logs.py`。

### 4.3 已查出的截图问题与处置

Run 3 原始 PNG 已下载并逐张打开。保存后订单和 reload 后回货详情两图主体尚白，不能验收；多关键词与部分详情图仍显示 loading，已要求等待真实响应及可见内容。可见控件断言保留，截图不能代替接口正确性。

实际版式修正仅限已改动的相关组件：`ProdReceiveDialog.vue` 长色码表单按原型正文 68vh 内滚动，保留标题与 footer；`ProdNodeDialogs.vue` 日期控件缩入其单元格；`TechPackTab.vue` 其他费用数字控件缩入其单元格。依据 `docs/原型/src/style.css:835–849`、`CLAUDE.md` 的表格/弹窗规范。Run5已验证回货footer和节点日期宽度；成本金额内容已完整，但整行外框比例断言为0.993351而非1，未据此放宽全局比例。后续改为逐个输入/按钮对视口及裁剪祖先的内容边界检查，保留1 CSS px舍入容差、单元格宽度及实际金额断言。MRP选订单的单号/状态扩大局部列宽后仍须一行完整；`793e5ed`另补确认框退出后的盘点/节点截图、真实款号预览结束和长表单页脚取证。本轮绿色运行后重新逐张核对；后续核价长款号修正和最终证据见下节。

### 4.4 最终代码候选的验收证据

代码 `61ea1c542ba8448cecefc8684f2031530d3df49d`；[Actions run](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997)；[完整证据包](https://github.com/xiaoliangao/lingmao/actions/runs/44148637997/artifacts/14356814763)。ZIP SHA-256：`f0e7b8457ccba851e0131106444d4cb01b02956ead0ab49440827fc7f93bfdf1`，保留期14天。证据包是隔离合成业务，不含生产账号、认证头或真实租户商业数据。

| 门禁 | 实际结果 / 产物 |
|---|---|
| 干净环境依赖与迁移 | ubuntu-latest固定工具版本安装通过；V1–V26空库迁移、旧NULL水位兼容与Schema约束测试通过。未用此结果替代目标机原库升级 |
| Java测试与生产包 | `mvn verify`工作流：464 unit +121 IT，failure/error/skipped均0，生产JAR生成；JUnit XML可复核 |
| 严格TS、Lint、前端测试与build | `web/package.json` 的typecheck（vue-tsc --noEmit）、lint、test、build全部通过；19/19 Node测试，正式dist生成 |
| 服务启动与健康 | 正式JAR + web dist代理启动，`test-artifacts/after/health.json`实际code=0 |
| API与浏览器 | C01–C19真实Vue/HTTP + C20纯API，共20/20；各evidence.json的consoleErrors、pageErrors、externalRequests均为空。预期业务拒绝和明示故障注入不当作成功数据 |
| 原型 | 原有构建与check.mjs通过；尺码页原图已打开 |
| 备份/恢复 | 真实低权限只读SFTP隔离；实际PG16恢复、同快照3.375与约束、合法删除对照、坏dump拒绝通过，非目标生产主机验收 |
| 人工实图检查 | 125张原始PNG逐张查看，包含修复前基线、桌面/390px手机、列表/主子表/确认框/料卡及空态/异常态；pending或注入失败图片明确只作为中间态/错误态 |
| 代码与产物卫生 | 本轮60个改动TS/Vue文件无裸any、as any、ts-ignore或ts-nocheck；V1–V25未改；没有PNG/日志/临时XLSX/PDF进入提交 |

关键前后对照在同一产物中：`before/order-matrix.png` → C01的`mixed-sku-reopened-line-values.png`；`before/techpack-bom.png` → C02的`bom-keyboard-card-empty-image.png`；`before/techpack-cost.png` → C11的`other-cost-row-complete-and-contained.png`；`before/count-detail.png` → C03的`count-refresh-cleared.png`。修复前路径相对`test-artifacts/`；修复后位于`after/test_XX_…/`，Cxx对应同编号用例，完整目录名见产物目录。

“看过截图”不覆盖生产数据、所有尺寸或全部业务排列；剩余未穷举组合在`dev/ci/ui/COVERAGE.md`逐项保留。完整首页/评审和上游聚合真实返回404，当前只通过明确失败与重试的验证，不能据此宣布完整首期启用。

## 5. 生命周期验收与首期路由

### 5.1 生命周期矩阵

原验收依据为 `docs/reviews/scm-full-audit.md` §8.3；以下登记可复核的具体链路，未覆盖的排列组合不写成已经验证。测试方法的完整路径见 §5.2，浏览器用例均在 `dev/ci/ui/test_audit_ui.py`。

| 生命周期 | 已有及新增执行断言 | 当前验收边界 |
|---|---|---|
| L1 资料包历史 | `TechPackServiceImplIT.devToProdFlow`、`publishedContextFreezesColorsSizesAndOwner_legacyIsExplicit`、`exportPdf`；C02/C11/C15/C16 核价权限、迟到响应、VOIDED旧版色码与真实PDF | 本轮云端/截图通过；未知旧版上下文不能补造，不承诺历史图片字节冻结 |
| L2 多级审批 | `OrderIT.createListAndTwoLevelAudit`；`ApprovalServiceImplIT.concurrentPass_onlyOneWins`、`cancelThenPass_1002_resubmitNewRound`、`waitingForMe_followsCurrentLevel`；C14三域角色与当前审批集 | 本轮云端通过；正式人员与规则不是CI匿名角色 |
| L3 共享供应 | `PurchaseIT.sharedTransit_partialNetReceiptsAndRecalc_allocateOnceByUseId`：采购300、A/B各150、先分到A、收200退100、重算B在途150且不重复采购；新草稿保留旧占用，确认后替换；`mrpConvertAuditReceiveRecalc` 另验正净需转采购 | 本地与本轮云端真实PG通过；相同物料/仓库、换算1的共享案例，不扩称跨仓跨单位全矩阵 |
| L4 分批采购 | `PurchaseIT.mrpConvertAuditReceiveRecalc`、`reverseReturnAfterReplacementCannotBreakReceiptCap_andRollsBackAll`、`fromSourceRetryIsIdempotentIncludingConcurrentCallsAndRollback`；C06来源建单真实重放 | 净收/退货反审上限与全事务回滚；本轮云端通过 |
| L5 库存与盘点 | `InventoryIT.postUnauditAndVoid`、`concurrentPostings`、`countFlow`、ABA、旧PENDING水位与不可变估值；C03实盘保留/刷新；C17收发列表/来源表单 | 新事件价额与冲销可解释，UNKNOWN不补零；真实盘点测量和期初另签认 |
| L6 两类生产 | `ProductionIT.scheduleAuditReportIssueReceiveVoid`、`buyWithOnlyFactoryMaterials_scheduleAuditReceiveAndVoid_withoutIssuingMaterials`、`receiptRetryPreservesOriginalResult_andRealSecondBatchRequiresNewId`、节点版本；C06/C07/C19 | BUY案例BOM全FACTORY，不宣称所有BUY免领料；未知结果UI是实落账后替换失败envelope，不冒充真实网络断连 |
| L7 执行后变更 | `OrderIT.changeWritesBackThenCancels`、`finalizedNonSkuChangeDetailsRemainReadable`；`OrderServiceImplTest.applyChange_checksScheduledAndStale`；`OrderChangeServiceImplTest.check_listsEveryProblem` | 保留已排数量下界与执行保护；没有无依据的新余量调整单 |
| L8 入口/筛选/移动 | C04成功查询快照导出；C08多关键词；C14审批列表→详情→动作；C18首页失败/重试；C19仅手机身份报工与异常；C20仓管来源权限 | **不能登记完整首页聚合闭环通过**：API06约定仍后续，当前只验证不可用状态。实际角色配置及同口径完整首页另验 |

### 5.2 测试源码索引

下列路径是前表简写类的唯一对应；JUnit方法与输出可直接互查。源码在最终后端修订后核对行号，不使用历史报告的旧行号定位本次代码。

| 类 / 用例 | 当前文件 |
|---|---|
| 采购与MRP | `src/test/java/com/lingmao/scm/module/purchase/service/impl/PurchaseIT.java` |
| 库存盘点与流水 | `src/test/java/com/lingmao/scm/module/inventory/service/impl/InventoryIT.java`、`StockExcelTest.java`（同目录） |
| 生产 | `src/test/java/com/lingmao/scm/module/production/service/impl/ProductionIT.java` |
| 需求及变更 | `src/test/java/com/lingmao/scm/module/order/service/impl/OrderIT.java`、`OrderServiceImplTest.java`、`OrderChangeServiceImplTest.java`（同目录） |
| 审批引擎 | `src/test/java/com/lingmao/scm/module/biz/service/impl/ApprovalServiceImplIT.java`、`ApprovalRuleServiceImplTest.java`、`ApprovalServiceImplTest.java`（同目录） |
| 资料包 | `src/test/java/com/lingmao/scm/module/techpack/service/impl/TechPackServiceImplIT.java`、`RequirementChecksTest.java`（同目录） |
| 前端纯函数 | `web/tests/audit-domain.test.mjs`：SKU1–4、金额容量5–6、查询7–8、日期9–11、回跳12–13、动作身份14–17、上传18–19 |
| 浏览器与实际HTTP | `dev/ci/ui/test_audit_ui.py`；逐项已覆盖及未覆盖组合见 `dev/ci/ui/COVERAGE.md` |

### 5.3 修复涉及的页面与证据入口

均为**隔离合成环境**，不是线上参考系统再次实测。本轮125张原始PNG已逐张打开；文件名用于在上述artifact定位，图像须结合实际响应断言阅读。

| 模块 / 实际路由 | 真接口与页面检查 | 图片入口 |
|---|---|---|
| 需求 `/orders`、`/orders/new`、`/orders/{id}` | 色码矩阵、逐行值、显式批改、1002保留、变更/排单表单、状态与导出 | C01/C04/C12/C14/C17 |
| 款式/资料包 `/styles`、`/styles/new`、`/styles/{id}`、`/costing` | 卡片/表格、款图/料卡、成本失败/权限/竞态、附件等待、历史PDF | C02/C10/C11/C13/C15/C16/C17 |
| 物料/供应商 `/materials`、`/materials/{id}`、`/suppliers`、`/suppliers/{id}` | 1/200/201编码、查询空态/异常、SKU/资质表单开关、文件入口 | C08/C17 |
| 采购算料 `/mrp`、`/mrp/{id}`、`/purchases`、`/purchases/new`、`/purchases/{id}` | 真实MRP→采购来源、状态筛选/导出、物料选择器及头行 | C04/C06/C17；共享在途详见PG IT |
| 收发盘存 `/stock`、`/stock-in`、`/stock-out`、`/stock-takes`、`/stock-takes/{id}` | 明细/汇总/日志、净托管提示、UNKNOWN、收发类型、来源表单、盘点草稿与ABA | C03/C04/C06/C17；C20仅JSON |
| 生产 `/production`、`/production/{id}`、`/fp-purchases`、`/fp-purchases/{id}` | 两kind列表/详情、节点计划、未知结果回货重试、主子表弹窗、当前审批集 | C06/C07/C14/C17 |
| 手机 `/mobile`、`/mobile/{id}` | 390px布局、仅手机SELF访问、无权操作不展示、真实报工与异常 | C07/C19 |
| 首页/登录 `/home`、`/upstream`、`/login` | 真实404明确失败并可重试；合法内部回跳与恶意/模糊参数拒绝 | C05/C17/C18 |

原型独立自检仍运行 `docs/原型/check.mjs`，`index.html` 由原有构建生成。没有新增未排期模块或访问外链BI；公共标签沿 `docs/api/README.md` / RFC010 §5 的既定范围，只读旧标签，不把旧系统标记据为新标签关系写接口。

## 6. 不能由本次沙盒替代的启用门禁

| 门禁 | 代码与文档已做 | 仍需维护者在真实交付环境核对 |
|---|---|---|
| S/P0-01 新旧切换 | RFC010 §1 已选 A；PRD 删除普通导入旧在途整单方案 | 截止时点、旧单私有清单、按仓/SKU 的公共可用期初、旧占用/托管/在途分界、交割责任、重复登记防控、差异及回退签认 |
| S/P1-09 正式发布 / 启用 | 代码、真库、真接口、UI、恢复门禁可重复运行；失败不冒称通过 | 真实用户与审批规则、业务人员操作验收、目标机 Docker/Compose 最低版本与完整部署包安装/升级；停止旧写实例后迁移 V26 |
| A/P1-11 实际异地备份账号 | 文档纠正 restrict；临时低权限只读 SFTP 允许下载并拒绝写、命令、PTY、转发、目录/符号链接逃逸 | 目标机器用户/目录所有权/sshd 配置、实际异地拉取、上传目录备份及同快照业务对账 |
| S/P1-06 期初估值 | 新流水保存不可变价额；未知保持 UNKNOWN | 可信期初价、估值起算日、币税口径。不能从旧 NULL 数据补造历史 |
| 证据与范围 | 原报告附件来源及原型已保留可追溯边界 | 原名字段清单原件、未取得的详情/Network 定义；公共标签多对多编辑、打印次数及未来模块不得据此自动上线 |

本 PR 不部署、不合并、不执行生产写操作。P0/P1 中未完成的真实门禁列为“云端无法验证”，保持 Draft；不会把“文档方案已确定”替换成“生产切换已完成”。
