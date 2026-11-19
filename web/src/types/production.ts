// production 域（外发加工单、成品采购单、进度节点、报工、异常、回货、生产看板、手机报工）的返回和请求，
// 和后端 module/production/dto 一一对应（docs/api/05-生产.md）。排单的类型在 types/order.ts（02 定的），这里不重复。
// 件数是整数；单价、金额是 JSON 数字，显示走 utils/format.ts
import type { Attachment, DocAction, DocHeader, DocStatus, StatusTone } from '@/types/biz'
import type { InvDocKind, InvDocStatus, InvDocType, MaterialSkuBrief } from '@/types/inventory'
import type { ResponsibleParty } from '@/types/order'
import type { SupplierStatus } from '@/types/supplier'
import type { FileVO } from '@/types/system'

// ---------------------------------------------------------------- 种类、状态

/** 种类：OUTSOURCE 外发加工单（清加工、半包，我方出料）/ BUY 成品采购单（包工包料，工厂买料）。排单时按加工方式定，之后不能改 */
export type ProductionKind = 'OUTSOURCE' | 'BUY'

/**
 * 每种单的叫法。priceLabel 是单价那一格的名字（PRD §6.7.1）；path 是列表菜单的地址，详情是 path/:id。
 * 同时说两种单的地方（看板、首页、报错）叫"生产单"
 */
export const PRODUCTION_KIND: Record<ProductionKind, { label: string; short: string; priceLabel: string; path: string }> = {
  OUTSOURCE: { label: '外发加工单', short: '外发加工', priceLabel: '加工费', path: '/production' },
  BUY: { label: '成品采购单', short: '成品采购', priceLabel: '成品单价（含料）', path: '/fp-purchases' },
}

/** 生产单用 DocStatus 的全部七个；RUNNING 回过货时显示成"部分回货"（PRODUCTION_PARTIAL），库里还是 RUNNING */
export type ProductionStatus = DocStatus

export const PRODUCTION_STATUS: Record<ProductionStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  PENDING: { label: '待审核', tone: 'pending' },
  APPROVED: { label: '已下单', tone: 'active' },
  RUNNING: { label: '生产中', tone: 'progress' },
  DONE: { label: '已完成', tone: 'done' },
  CLOSED: { label: '已结案', tone: 'closed' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/** status = RUNNING 且 returnedQty > 0 时用它代替 PRODUCTION_STATUS.RUNNING */
export const PRODUCTION_PARTIAL: { label: string; tone: StatusTone } = { label: '部分回货', tone: 'progress' }

/**
 * 详情、列表行上的按钮：通用的状态动作 + edit 改草稿、remove 删草稿、report 报进度、editPlan 改节点计划、
 * reportIssue 上报异常、receive 登记回货、cut 登记裁床数（要 production:node:edit）、issue 领料、returnMaterial 退料（后两个要 inventory:doc:add，走 04 的接口）
 */
export type ProductionAction = DocAction | 'edit' | 'remove' | 'report' | 'editPlan' | 'reportIssue' | 'receive' | 'cut' | 'issue' | 'returnMaterial'

/** 操作记录里的动作叫法（DocLogList 的 actions）。statuses 传 PRODUCTION_STATUS 的 label */
export const PRODUCTION_LOG_ACTION: Record<string, string> = {
  create: '排单生成',
  update: '改草稿',
  'update-plan': '改节点计划',
  report: '报进度',
  'report-issue': '上报异常',
  'start-issue': '开始处理异常',
  'close-issue': '关闭异常',
  receive: '登记回货',
  'void-receipt': '回货作废',
  cut: '登记裁床数',
  'issue-material': '领料',
  'return-material': '退料',
  // 11-18 以前导出工艺单 PDF 留下的操作记录
  'export-pdf': '导出工艺单',
}

/** 领料状态（prd_order.issue_status），领料、退料过账时回写。资料包里没有自购的行时字段是 null（不用领料） */
export type MaterialIssueStatus = 'NONE' | 'PARTIAL' | 'DONE'

export const MATERIAL_ISSUE_STATUS: Record<MaterialIssueStatus, { label: string; tone: StatusTone }> = {
  NONE: { label: '未领料', tone: 'off' },
  PARTIAL: { label: '部分领料', tone: 'pending' },
  DONE: { label: '已领齐', tone: 'done' },
}

// ---------------------------------------------------------------- 风险、节点

/**
 * 货期预警（PRD §6.7.4），后端现算，只有已下单、生产中的单才可能为 true：
 * late 已逾期（今天 > 预计交期 ?? 交期）、near 临期（还剩 ≤ nearDays 天，默认 7）、nodeLate 有节点延期、issue 有没关闭的异常、
 * upstream 上游订单变了还没处理
 */
export interface ProductionRisk {
  late: boolean
  near: boolean
  nodeLate: boolean
  issue: boolean
  upstream: boolean
}

/** 列表"风险"筛选、地址参数 ?risk= 的取值 */
export type ProductionRiskKey = 'LATE' | 'NEAR' | 'NODE_LATE' | 'ISSUE' | 'UPSTREAM'

export const PRODUCTION_RISK_LABEL: Record<ProductionRiskKey, string> = {
  LATE: '已逾期',
  NEAR: '临期',
  NODE_LATE: '节点延期',
  ISSUE: '有异常',
  UPSTREAM: '上游已变更',
}

/**
 * 节点状态（PRD §6.7.2），后端现算：DONE 按时完成；LATE 延期（今天 > 计划完成还没完成，或实际完成 > 计划完成）；
 * WARN 临期（计划完成 − 今天 ≤ 2 天，还没完成）；RUNNING 有实际开始；TODO 其他
 */
export type NodeState = 'DONE' | 'LATE' | 'WARN' | 'RUNNING' | 'TODO'

export const NODE_STATE: Record<NodeState, { label: string; tone: StatusTone }> = {
  DONE: { label: '已完成', tone: 'done' },
  LATE: { label: '延期', tone: 'danger' },
  WARN: { label: '临期', tone: 'pending' },
  RUNNING: { label: '进行中', tone: 'progress' },
  TODO: { label: '未开始', tone: 'off' },
}

/** 列表、看板、手机卡片上的节点进度条：每个节点一格 */
export interface NodeDot {
  nodeName: string
  state: NodeState
}

/** 一个进度节点（prd_node）。实际开始、实际完成、完成数量是最近一次报的 */
export interface ProductionNode {
  id: string
  seq: number
  /** 字典 prod_node 的 value（MATERIAL、CUT、SEW……） */
  nodeCode: string
  /** 生成时存下的名字，字典以后改名不影响 */
  nodeName: string
  /** yyyy-MM-dd。审核时从交期往前倒排，跟单员能改 */
  planStart: string
  planFinish: string
  actualStart: string | null
  actualFinish: string | null
  doneQty: number | null
  remark: string | null
  state: NodeState
  /** 晚了几天：已完成的 = 实际完成 − 计划完成，没完成的 = 今天 − 计划完成；不是 LATE 是 null */
  lateDays: number | null
  lastReportByName: string | null
  /** 2026-11-15 10:30:00 */
  lastReportTime: string | null
  /** 报过几次（> 1 时显示"汇报记录 n 次"，点开调 nodeReports） */
  reportCount: number
  /** 这个节点每次报工的照片，新的在前 */
  photos: Attachment[]
}

/** 一次报工（prd_node_report），只插不改 */
export interface NodeReport {
  id: string
  action: NodeReportAction
  actualStart: string | null
  actualFinish: string | null
  doneQty: number | null
  remark: string | null
  photos: Attachment[]
  createByName: string | null
  /** 2026-11-15 10:30:00 */
  createTime: string
}

/** START 只报开始（节点进行中）/ FINISH 报完成 */
export type NodeReportAction = 'START' | 'FINISH'

/**
 * 报进度（电脑上的"报进度"、手机报工共用）。
 * START：actualStart 必填，actualFinish 发 null；FINISH：actualFinish 发 null = 今天，actualStart 发 null = 节点原来的实际开始（没有就等于完成日期）
 */
export interface NodeReportForm {
  action: NodeReportAction
  actualStart: string | null
  actualFinish: string | null
  doneQty: number | null
  remark: string | null
  /** 最多 9 张，追加到这个节点上；先 fileApi.upload 拿 FileVO */
  photos: FileVO[]
}

export interface NodeReportResult {
  /** 不拦的提示，如"前面还有节点没报完成"；没有是 null */
  warning: string | null
  /** 报完以后生产单的状态（已下单 → 生产中） */
  status: ProductionStatus
}

/** 改节点计划：全部节点一起发 */
export interface NodePlanReq {
  planRevision: number
  nodes: NodePlanItem[]
}

export interface NodePlanItem {
  nodeId: string
  planStart: string
  planFinish: string
}

// ---------------------------------------------------------------- 列表

/** 外发加工单、成品采购单列表和看板列表的一行 */
export interface ProductionRow {
  id: string
  no: string
  kind: ProductionKind
  /** 品类线（字典 product_line）：跟款式（RFC 013 §2） */
  productLine: string
  status: ProductionStatus
  /** 批量条逐条调 act 要带 */
  version: number
  factoryId: string
  factoryShortName: string
  /** 字典 process_mode */
  processMode: string
  orderId: string
  orderNo: string
  styleId: string
  styleCode: string
  styleName: string
  image: Attachment | null
  totalQty: number
  unitPrice?: number
  totalAmount?: number
  dueDate: string
  /** 异常填了新交期才有，列表上交期显示它、原交期划掉 */
  expectDate: string | null
  /** 预计交期 ?? 交期 − 今天；已完成、已结案、已作废、草稿、待审核是 null */
  daysLeft: number | null
  /** 还没审核（没节点）是 [] */
  nodes: NodeDot[]
  /** 第一个没报完成的节点；都完成了、没节点是 null */
  currentNodeName: string | null
  /** null = 不用领料（资料包里没有自购的行，成品采购单常见） */
  issueStatus: MaterialIssueStatus | null
  returnedQty: number
  defectQty: number
  /** 没关闭的异常几条、第一条的类型（字典 issue_type），看板"异常"列 */
  openIssueCount: number
  firstOpenIssueType: string | null
  merchId: string
  merchName: string | null
  risk: ProductionRisk
  actions: ProductionAction[]
}

export interface ProductionQuery {
  /** 当前待我审批；忽略 mine / owner，仍要求模块列表权限 */
  auditForMe?: boolean
  /** 两个菜单的列表必传；看板不传 = 两种都看 */
  kind?: ProductionKind
  /** 品类线：几条线的人才有这个筛选 */
  productLine?: string
  /** 单号、订单号、款号、款名、加工厂；一次查几个（README §3.2）时单号、订单号、款号精确查 */
  keyword?: string
  status?: ProductionStatus[]
  /** 和 status=RUNNING 一起用：true 部分回货、false 生产中（没回过货）；不传都要 */
  returned?: boolean
  risk?: ProductionRiskKey
  factoryId?: string | null
  merchId?: string | null
  /** 字典 brand（款式的品牌） */
  brand?: string
  /** 按"预计交期 ?? 交期"，两头都含 */
  dueDateFrom?: string
  dueDateTo?: string
  /** 只看我是制单人、生产跟单的 */
  mine?: boolean
  /** 导出选中的 */
  ids?: string[]
}

/** 状态页签上的数：每个状态几张，没有的不在里面；PARTIAL 是 RUNNING 里回过货的几张（"生产中"页签 = RUNNING − PARTIAL） */
export type ProductionCounts = Partial<Record<ProductionStatus | 'PARTIAL', number>>

// ---------------------------------------------------------------- 详情

/** 一行明细 = 一个款式 SKU（prd_order_item） */
export interface ProductionItem {
  id: string
  seq: number
  orderItemId: string
  styleSkuId: string
  /** 款号-颜色码-尺码 */
  skuCode: string
  colorId: string
  colorCode: string
  colorName: string
  colorHex: string | null
  sizeCode: string
  qty: number
  /** 已回货（含次品）、其中次品 */
  returnedQty: number
  defectQty: number
  /** 裁床数（RFC 013 §4）：null = 没登记 */
  cutQty: number | null
  /** 未回 = 件数（登记了裁床数按裁床数）− 已回货，不小于 0；登记回货时默认填它 */
  remainQty: number
  /** 最多还能回 = ⌊件数或裁床数 × 1.05⌋ − 已回货，不小于 0（Q09） */
  maxReceiveQty: number
  /** 登记裁床数的范围：最多 ⌊件数 × 1.05⌋，最少让已回的不超出新上限 */
  maxCutQty: number
  minCutQty: number
}

/** 详情顶上的红条：没关闭的异常 */
export interface OpenIssueBrief {
  id: string
  /** 字典 issue_type */
  issueType: string
  description: string
  newExpectDate: string | null
}

/** GET /api/production-orders/{id} */
export interface ProductionDetail extends DocHeader<ProductionStatus, ProductionAction> {
  /** 节点计划的独立修订号，不使用单据 version */
  planRevision: number
  kind: ProductionKind
  /** 品类线：跟款式，加工厂要做这条线 */
  productLine: string
  factoryId: string
  factoryCode: string
  /** 全称；factoryShortName 是简称 */
  factoryName: string
  factoryShortName: string
  /** 草稿、待审核时不是合作中，提交、审核会被拦，页面标出来 */
  factoryStatus: SupplierStatus
  /** 打印用：工厂的联系人、电话，没有是 null */
  factoryContact?: string | null
  factoryPhone?: string | null
  /** 字典 process_mode：外发加工单只会是清加工、半包，成品采购单只会是 FULL */
  processMode: string
  orderId: string
  orderNo: string
  styleId: string
  styleCode: string
  styleName: string
  /** 花名，没有是 null */
  aliasName: string | null
  /** 字典 brand，没有是 null */
  brand: string | null
  image: Attachment | null
  /** 订单锁定的生产 BOM：打印、看工艺单用它 */
  techPackId: string
  techPackVersion: number
  orderDate: string
  dueDate: string
  /** 异常填了新交期才有；原交期 dueDate 保留 */
  expectDate: string | null
  daysLeft: number | null
  unitPrice?: number
  /** 默认单价取自哪条价格档案；手填的是 null */
  priceSourceId: string | null
  /** 价格档案里这家工厂 × 这个款 × 这种加工方式现在的价（按下单日期）；没有是 null。和 unitPrice 不一样时页面标"改价" */
  archivePrice?: number | null
  priceChangeReason: string | null
  /** 税率 %，默认带工厂 */
  taxPct: number
  taxIncluded: boolean
  totalQty: number
  totalAmount?: number
  merchId: string
  merchName: string | null
  /** 字典 settle_method */
  settleMethod: string
  terms: string | null
  issueStatus: MaterialIssueStatus | null
  returnedQty: number
  defectQty: number
  currentNodeName: string | null
  risk: ProductionRisk
  /** 临期的天数（配置的，默认 7），页面写"7 天内交货"用 */
  nearDays: number
  /** 尺码组的尺码，按顺序，矩阵的列 */
  sizes: string[]
  items: ProductionItem[]
  /** 审核以后才有 */
  nodes: ProductionNode[]
  openIssues: OpenIssueBrief[]
  /** 页签上的数：异常（全部）、回货单（没作废的）、领料退料单（没作废的） */
  issueCount: number
  receiptCount: number
  stockDocCount: number
}

/**
 * 改草稿（PUT /api/production-orders/{id}）：单头整体覆盖，明细不能改（要改件数，删掉草稿回订单重新排单）。
 * 换了加工厂调 scheduleApi.quote(orderId, styleId, factoryId) 重新带单价、结算方式、税率
 */
export interface ProductionForm {
  version: number
  companyId: string
  expectedCompanyId?: string
  factoryId: string
  /** 只能在同一种单里换：外发加工单清加工 ↔ 半包；成品采购单固定 FULL */
  processMode: string
  unitPrice?: number | null
  /** 单价用的是档案现价时带上那条的 id（ScheduleModePrice.priceSourceId）；手填的发 null */
  priceSourceId: string | null
  /** 单价和档案现价不一样时必填 */
  priceChangeReason: string | null
  dueDate: string | null
  merchId: string | null
  settleMethod: string | null
  taxPct: number | null
  taxIncluded: boolean
  terms: string | null
  remark: string | null
}

// ---------------------------------------------------------------- 领料（"领料"页签）

/** 应领、已领：只算资料包里物料来源 = 自购的行（PRD §6.7.1、Q12）。数量是库存单位 */
export interface MaterialPlanRow extends MaterialSkuBrief {
  /** 应领 = Σ 件数 × BOM 用量 ×（1 + 损耗率） */
  needQty: number
  /** 已领 = 领料出库 − 退料入库（只算已审核的） */
  issuedQty: number
  /** 还差 = 应领 − 已领，不小于 0；0 时页面写"领齐" */
  remainQty: number
  /** 物料仓（所有启用的物料仓）现在的库存合计 */
  stockQty: number
}

/** 挂在这张生产单上的领料出库单、退料入库单 */
export interface ProductionStockDoc {
  id: string
  no: string
  /** 点单号：docRoute({ type: 'INV_DOC', kind, id, parentId: null }) */
  kind: InvDocKind
  docType: InvDocType
  status: InvDocStatus
  docDate: string
  fromWarehouseName: string | null
  toWarehouseName: string | null
  /** 几种物料 */
  itemCount: number
}

export interface ProductionMaterials {
  /** 资料包里没有自购的行时是 []（成品采购单常见） */
  rows: MaterialPlanRow[]
  /** 草稿也列，作废的不列；新的在前 */
  docs: ProductionStockDoc[]
}

/** 给 04 用：出库单页"领料出库（选生产单）"、退料表单的"源生产单"（GET /api/production-orders/issue-options） */
export interface IssueOption {
  id: string
  no: string
  kind: ProductionKind
  status: ProductionStatus
  factoryId: string
  factoryShortName: string
  styleCode: string
  styleName: string
  totalQty: number
  issueStatus: MaterialIssueStatus | null
}

export interface IssueOptionQuery {
  /** 单号、款号、加工厂 */
  keyword?: string
  factoryId?: string | null
  /** true：退料用，列已领 > 0 的；不传或 false：领料用，列还有没领齐的 */
  returnable?: boolean
}

// ---------------------------------------------------------------- 异常（prd_issue）

/** 异常自己的状态，不是 DocStatus */
export type IssueStatus = 'OPEN' | 'HANDLING' | 'CLOSED'

export const ISSUE_STATUS: Record<IssueStatus, { label: string; tone: StatusTone }> = {
  OPEN: { label: '待处理', tone: 'pending' },
  HANDLING: { label: '处理中', tone: 'progress' },
  CLOSED: { label: '已关闭', tone: 'closed' },
}

/** 异常行上的按钮：start 开始处理（待处理才有）、close 关闭（没关闭的都有）；都要 production:issue:edit */
export type IssueAction = 'start' | 'close'

export interface ProductionIssue {
  id: string
  /** YC + 日期 + 4 位 */
  no: string
  /** 字典 issue_type */
  issueType: string
  description: string
  photos: Attachment[]
  impactDelivery: boolean
  newExpectDate: string | null
  /** 责任方；null = 待定。CUSTOMER 只在 TRADE 开时出现 */
  responsibleParty: ResponsibleParty | null
  status: IssueStatus
  /** 上报人、上报时间 */
  createByName: string | null
  createTime: string
  /** 点"开始处理"的人；没点过是 null */
  handlerName: string | null
  measure: string | null
  closeByName: string | null
  closeTime: string | null
  actions: IssueAction[]
}

/** 上报异常（电脑、手机共用） */
export interface IssueForm {
  /** 字典 issue_type */
  issueType: string
  description: string
  /** 至少 1 张，最多 9 张 */
  photos: FileVO[]
  impactDelivery: boolean
  /** impactDelivery = true 时必填，填了生产单的预计交期跟着改；false 时发 null */
  newExpectDate: string | null
  responsibleParty: ResponsibleParty | null
}

export interface IssueCreated {
  id: string
  no: string
}

/** 关闭异常：处理措施必填；上报时没定的责任方这时补上 */
export interface IssueCloseForm {
  measure: string
  responsibleParty: ResponsibleParty | null
}

// ---------------------------------------------------------------- 回货（prd_receipt）

/** 回货单只用三个状态："登记"一步 = 建 + 审核，页面上看不到草稿 */
export type ReceiptStatus = Extract<DocStatus, 'DRAFT' | 'APPROVED' | 'VOIDED'>

export const RECEIPT_STATUS: Record<ReceiptStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  APPROVED: { label: '已登记', tone: 'done' },
  VOIDED: { label: '已作废', tone: 'void' },
}

export interface ReceiptItem {
  id: string
  prdOrderItemId: string
  styleSkuId: string
  skuCode: string
  colorCode: string
  colorName: string
  colorHex: string | null
  sizeCode: string
  /** 本次回货（含次品）、其中次品、良品 = 回货 − 次品 */
  qty: number
  defectQty: number
  goodQty: number
  remark: string | null
}

export interface ProductionReceipt {
  id: string
  /** HH + 日期 + 4 位 */
  no: string
  status: ReceiptStatus
  receiptDate: string
  /** 收货仓（成品仓），一期只记录；没选是 null */
  warehouseId: string | null
  warehouseName: string | null
  logisticsNo: string | null
  remark: string | null
  /** 合计 */
  qty: number
  defectQty: number
  photos: Attachment[]
  /** 登记人、登记时间 */
  createByName: string | null
  createTime: string
  /** 作废的：原因、谁、什么时候；没作废是 null */
  voidReason: string | null
  voidByName: string | null
  voidTime: string | null
  items: ReceiptItem[]
  /** void：没作废、生产单没结案没作废、有 production:receipt:void */
  actions: 'void'[]
}

/** 一个 SKU 这次回多少；qty 是 0 的行不发 */
export interface ReceiptItemForm {
  prdOrderItemId: string
  qty: number
  /** 其中次品，不能大于 qty */
  defectQty: number
  remark: string | null
}

/** 登记裁床数（PUT /api/production-orders/{id}/cut-qty）：只发改了的；cutQty null = 清掉 */
export interface CutQtyForm {
  items: { prdOrderItemId: string; cutQty: number | null }[]
}

/** 登记回货（POST /api/production-orders/{id}/receipts） */
export interface ReceiptForm {
  /** 未知结果时保留原 UUID 和完整请求体 */
  clientActionId: string
  /** 不能晚于今天 */
  receiptDate: string
  warehouseId: string | null
  logisticsNo: string | null
  remark: string | null
  photos: FileVO[]
  items: ReceiptItemForm[]
}

export interface ReceiptCreated {
  id: string
  no: string
  /** 登记完以后生产单的状态：回够了是 DONE，toast 里说"外发加工单完成" */
  status: ProductionStatus
}

/** 超回货（1004）时 data 里的一条 */
export interface ReceiptOverRow {
  /** SKU 码 */
  sku: string
  /** 加工件数、已回、这次、上限 = ⌊件数或裁床数 × 1.05⌋ */
  qty: number
  returnedQty: number
  thisQty: number
  maxQty: number
  /** 如"加工 120 件，已回 120，最多还能回 3 件" */
  message: string
}

// ---------------------------------------------------------------- 生产看板（/board）

/** 数字卡：点了筛下面的列表；地址参数 ?card= 也是它（首页点进来） */
export type BoardCardKey = 'RUNNING' | 'WAITING' | 'NEAR' | 'LATE' | 'NODE_LATE' | 'ISSUE'

export interface BoardQuery {
  kind?: ProductionKind
  factoryId?: string | null
  merchId?: string | null
  /** 字典 brand */
  brand?: string
  /** 按"预计交期 ?? 交期" */
  dueDateFrom?: string
  dueDateTo?: string
  /** 只看我是制单人、生产跟单的 */
  mine?: boolean
}

/** GET /api/production-board/cards：筛选条件和列表一样（card 不看） */
export interface BoardCards {
  /** 在制：生产中（含部分回货）的单数、件数（件数 − 已回货） */
  runningCount: number
  runningQty: number
  /** 待开工：已下单、还没报开始的单数、件数（件数 − 已回货） */
  waitingCount: number
  waitingQty: number
  nearCount: number
  lateCount: number
  nodeLateCount: number
  issueCount: number
  /** 本月回货件数（本月登记、没作废的回货单），不能点 */
  monthReturnedQty: number
  /** 临期天数，卡片写"7 天内交货" */
  nearDays: number
}

/** 按工厂看的一行：只算已下单、生产中的单（和 02 审核对话框的 OrderReviewLoad 同一个口径） */
export interface BoardFactoryRow {
  factoryId: string
  factoryShortName: string
  /** 供应商分类的名字（成衣厂、加工厂） */
  categoryNames: string[]
  /** 在制单数、在制件数（件数 − 已回货）、其中逾期的单数 */
  wipCount: number
  wipQty: number
  lateCount: number
}

// ---------------------------------------------------------------- 手机报工（/mobile）

/** "我的跟单"的一张卡片：生产跟单是我、已下单或生产中的单，按风险排 */
export interface MobileOrderRow {
  id: string
  no: string
  kind: ProductionKind
  status: ProductionStatus
  styleCode: string
  styleName: string
  image: Attachment | null
  factoryShortName: string
  totalQty: number
  dueDate: string
  expectDate: string | null
  daysLeft: number | null
  nodes: NodeDot[]
  currentNodeName: string | null
  risk: ProductionRisk
}
