// order 域（需求订单、订单变更单）的返回，和后端 module/order/dto 对应（docs/api/02-需求订单.md）。
// 另外放了两块界面在订单里、后端不在 order 域的：排单（production 域）、审核对话框的评审信息（board 域）。
// 字段名照数据库设计 §10 的列名转 camelCase；可能为 null 的地方都写了。件数是整数，单价、金额是 JSON 数字
import { DOC_STATUS, type Attachment, type DocAction, type DocActionReq, type DocHeader, type DocRef, type DocStatus, type StatusTone } from '@/types/biz'
import type { ColorOption, EnableStatus } from '@/types/basic'
import type { StyleStatus, TechPackStatus } from '@/types/style'
import type { FileVO } from '@/types/system'

// ---------------------------------------------------------------- 需求订单：状态、叫法

/** 需求订单用全套通用状态。RUNNING 叫"生产中"（有了已审核的生产单），VOIDED 叫"已取消"（审核前作废、审核后经变更单取消） */
export type OrderStatus = DocStatus

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: StatusTone }> = {
  ...DOC_STATUS,
  RUNNING: { label: '生产中', tone: 'progress' },
  VOIDED: { label: '已取消', tone: 'void' },
}

/**
 * 需求订单的按钮（详情和列表行的 actions）：通用的状态动作，再加
 * edit 改草稿、remove 删草稿、schedule 排单（还有没排完的数量、有 production:order:add）、
 * change 提变更 / 取消订单（已审核、生产中，没有没审完的变更单，有 order:change:add）、mrp 算料（接口在 03-算料采购）
 */
export type OrderAction = DocAction | 'edit' | 'remove' | 'schedule' | 'change' | 'mrp'

/** 订单来源（ord_order.source）：手工 / Excel 导入 / 翻单（二期） */
export type OrderSource = 'MANUAL' | 'IMPORT' | 'REPEAT'

export const ORDER_SOURCE: Record<OrderSource, string> = {
  MANUAL: '手工',
  IMPORT: 'Excel 导入',
  REPEAT: '翻单',
}

/** 操作记录里订单自己的动作叫法（通用的提交、审核……DocLogList 自带） */
export const ORDER_LOG_ACTIONS: Record<string, string> = {
  import: 'Excel 导入',
  schedule: '排单',
  production: '生产进度',
  change: '变更',
  cancel: '经变更单取消',
}

// ---------------------------------------------------------------- 需求订单：列表

/** 列表里"款式"一列的一个款：缩略图 + 款号 */
export interface OrderStyleRef {
  styleId: string
  styleCode: string
  image: Attachment | null
}

/** 列表的一行。齐料一列另调 03 的 kittingApi.byOrders（api/purchase.ts） */
export interface OrderRow {
  id: string
  no: string
  status: OrderStatus
  /** 字典 order_type */
  orderType: string
  /** 字典 brand */
  brand: string | null
  contractNo: string | null
  /** yyyy-MM-dd */
  orderDate: string
  requiredDate: string
  /** 承诺交期，审核后才有；多级审批中间级填了的，待审核时也有（是草填的） */
  commitDate: string | null
  /** 字典 currency；金额不换算汇率，外币显示币种 */
  currency: string
  totalQty: number
  totalAmount?: number
  /** 已排产、已回货件数（明细加总），排产、回货两列的进度条用 */
  scheduledQty: number
  returnedQty: number
  styles: OrderStyleRef[]
  salesId: string
  salesName: string | null
  merchId: string
  merchName: string | null
  companyName: string
  source: OrderSource
  createByName: string | null
  createTime: string
  /** 批量提交、审核直接带它，不用先取一次详情 */
  version: number
  /** 批量条的"提交""审核"按它判断 */
  actions: OrderAction[]
}

export interface OrderQuery {
  /** 当前待我审批；忽略 mine / owner，仍要求模块列表权限 */
  auditForMe?: boolean
  /** 单号、合同号、款号；一次查几个（README §3.2）时单号、款号精确查 */
  keyword?: string
  status?: OrderStatus[]
  /** 字典 order_type */
  orderType?: string
  /** 字典 brand */
  brand?: string
  salesId?: string | null
  merchId?: string | null
  /** 含这个款的订单 */
  styleId?: string | null
  /** 下单日期，yyyy-MM-dd，两头都含 */
  orderDateFrom?: string
  orderDateTo?: string
  /** 待排单：已审核、一件都还没排的（首页"待排单"点进来带上，口径和原型一样） */
  unscheduled?: boolean
  /** 只看我是制单人、业务员、跟单员的 */
  mine?: boolean
  /** 导出选中的 */
  ids?: string[]
}

/** 款式详情"需求订单"页签的一行：这张订单里这个款的数 */
export interface StyleOrderRow {
  orderId: string
  no: string
  status: OrderStatus
  orderType: string
  brand: string | null
  /** 本款件数 */
  qty: number
  /** 锁定的生产 BOM 版本号；审核前是 null */
  techPackVersion: number | null
  requiredDate: string
  commitDate: string | null
  merchName: string | null
}

// ---------------------------------------------------------------- 需求订单：详情

/** 明细的一行 = 一个款式 SKU（ord_order_item） */
export interface OrderItem {
  id: string
  seq: number
  styleId: string
  styleSkuId: string
  /** 款号-颜色码-尺码 */
  skuCode: string
  colorId: string
  colorCode: string
  colorName: string
  colorHex: string | null
  sizeCode: string
  qty: number
  /** 单价；没填是 null */
  price?: number | null
  /** 金额 = 件数 × 单价，后端算；没单价是 null */
  amount?: number | null
  /** 行交期；null = 用单头的要求交期 */
  lineDueDate: string | null
  /** 审核时锁定的生产 BOM；审核前是 null */
  techPackId: string | null
  /** 已排产、已回货，下游回写 */
  scheduledQty: number
  returnedQty: number
  remark: string | null
}

/** 明细里一个款的公共信息：页面按款画颜色 × 尺码矩阵，一个款一块 */
export interface OrderStyle {
  styleId: string
  styleCode: string
  styleName: string
  aliasName: string | null
  image: Attachment | null
  /** 尺码组的尺码，按顺序，矩阵的列 */
  sizes: string[]
  /** 审核时锁定的生产 BOM 版本；审核前是 null */
  techPackId: string | null
  techPackVersion: number | null
  /** 这个款现在生效的生产 BOM；没有是 null（提交会被拦）。审核前显示它，审核后和锁定的不一样时提示"现在生效的是 V3" */
  currentPackId: string | null
  currentPackVersion: number | null
  /** 本款件数、金额 */
  qty: number
  amount?: number | null
}

/** GET /api/orders/{id} */
export interface OrderDetail extends DocHeader<OrderStatus, OrderAction> {
  orderType: string
  /** 客户、客户订单号：贸易代工留的口子（TRADE 关时都是 null，页面不显示） */
  customerId: string | null
  customerName: string | null
  customerOrderNo: string | null
  contractNo: string | null
  brand: string | null
  orderDate: string
  requiredDate: string
  /** 承诺交期。待审核时是上一级审批人填的（多级审批），审核对话框拿它当默认值；还没人填是 null */
  commitDate: string | null
  salesId: string
  salesName: string | null
  merchId: string
  merchName: string | null
  currency: string
  /** 送货要求 */
  deliveryReq: string | null
  totalQty: number
  totalAmount?: number
  source: OrderSource
  scheduledQty: number
  returnedQty: number
  styles: OrderStyle[]
  /** 全部明细，按款、颜色、尺码排好；页面按 styleId 分块 */
  items: OrderItem[]
  /** 没审完的变更单（草稿或待审核），顶上出提示"变更单 BG… 待审核：…"；没有是 null */
  pendingChange: { id: string; no: string; status: DocStatus; summary: string } | null
}

// ---------------------------------------------------------------- 需求订单：新建、保存

/** 明细的一行。逐 SKU 独立保留单价、行交期与备注；整款/尺码控件仅显式应用时批改 */
export interface OrderItemForm {
  styleSkuId: string
  /** 正整数；0 的格子不发 */
  qty: number
  price?: number | null
  lineDueDate: string | null
  remark: string | null
}

/** 新建、保存草稿共用的列。保存是整体覆盖：没填的发 null，明细发全部 */
export interface OrderForm {
  /** 必填：页面默认填我的默认主体（user.defaultCompanyId） */
  companyId: string
  /** 字典 order_type；TRADE 关时没有"客户订单" */
  orderType: string
  /** TRADE 关着，一律发 null */
  customerId: string | null
  customerOrderNo: string | null
  contractNo: string | null
  brand: string | null
  orderDate: string
  requiredDate: string
  salesId: string | null
  merchId: string | null
  /** 字典 currency，默认 CNY */
  currency: string
  deliveryReq: string | null
  remark: string | null
  items: OrderItemForm[]
}

/** 新建多出来的：附件（和单据公共的附件同一组） */
export interface OrderCreateForm extends OrderForm {
  files: FileVO[]
}

/** 保存草稿多出来的：打开时拿到的版本号 */
export interface OrderUpdateForm extends OrderForm {
  version: number
  /** 页面装载时已保存的主体；X-Company-Id 同它，companyId 可明确改为新主体。 */
  expectedCompanyId?: string
}

/** 录单矩阵里一个款的一个 SKU */
export interface OrderStyleSku {
  id: string
  colorId: string
  sizeCode: string
  code: string
  /** 停用的 SKU 那一格灰掉、不能填 */
  status: EnableStatus
}

/** 录单矩阵里一个款的一个颜色（行） */
export interface OrderStyleColor extends ColorOption {
  /** 能不能下这个颜色：颜色在款上停用了、SKU 都停用了、已生效的生产 BOM 里没给它配料，都不能下 */
  orderable: boolean
  /** 不能下的原因，如"资料包 V2 里没给这个颜色配料"；能下是 null */
  reason: string | null
}

/**
 * GET /api/orders/style-options?styleIds=：录单、提变更时画矩阵要的款式信息（DataPick 选完款式再取）。
 * 草稿、停用、没有生效生产 BOM 的款也返回，problem 里写为什么不能下，页面标出来；提交时后端照样会拦
 */
export interface OrderStyleOption {
  styleId: string
  code: string
  name: string
  aliasName: string | null
  image: Attachment | null
  status: StyleStatus
  techPackStatus: TechPackStatus
  /** 现在生效的生产 BOM；没有是 null */
  currentPackId: string | null
  currentPackVersion: number | null
  sizes: string[]
  /** 款上的颜色，按顺序（停用的也在，orderable = false） */
  colors: OrderStyleColor[]
  skus: OrderStyleSku[]
  /** 吊牌价，单价输入框的提示 */
  tagPrice: number | null
  /** 不能下单的原因："草稿款不能下单""已停用""没有生效的生产 BOM：先在款式的资料包里建好……"；能下是 null */
  problem: string | null
}

/** 导入时整批订单共用的单头（导入对话框里填，拼进导入地址的查询参数）。模板里只有明细的列（PRD §6.5.2） */
export interface OrderImportHead {
  /** 必填：默认填我的默认主体（user.defaultCompanyId） */
  companyId: string
  orderType: string
  brand: string | null
  /** 要求交期；行交期没填的用它 */
  requiredDate: string
  /** 业务员；不给 = 导入的人（原型的导入对话框里没有这一项） */
  salesId: string | null
  /** 跟单员；不给 = 导入的人（同业务员） */
  merchId: string | null
}

// ---------------------------------------------------------------- 需求订单：审核

/** 审核（POST /api/orders/{id}/audit）：每一级都要填承诺交期，带出上一级填的，以最后一级的为准 */
export interface OrderAuditReq extends DocActionReq {
  /** yyyy-MM-dd */
  commitDate: string
}

/** 评审：一个款审核后会锁哪一版生产 BOM */
export interface OrderReviewPack {
  styleId: string
  styleCode: string
  styleName: string
  /** 现在生效的生产 BOM；没有是 null */
  techPackId: string | null
  version: number | null
  /** 不能审的原因（"没有生效的生产 BOM""黑色还没配料"）；能审是 null */
  problem: string | null
}

/** 评审：缺口大的一种料（按生产 BOM 粗算，和可用量比；PRD §6.5.4） */
export interface OrderReviewShortage {
  materialSkuId: string
  skuCode: string
  materialName: string
  skuColorName: string | null
  skuSpec: string | null
  /** 库存单位，字典 unit */
  unit: string
  /** 需求（含损耗、备料比）、可用（库存 + 赶得上的在途 − 别的算料单占着的）、缺口，库存单位 */
  need: number
  available: number
  gap: number
  /** 采购周期，天；物料没填是 null */
  leadDays: number | null
  image: Attachment | null
}

/** 评审：一个默认加工厂手上的活 */
export interface OrderReviewLoad {
  factoryId: string
  factoryName: string
  /** 这张订单里哪些款的默认加工厂是它 */
  styleCodes: string[]
  /** 在制件数 = 已下单、生产中的生产单的件数 − 已回货 */
  wipQty: number
  /** 在制的生产单张数 */
  orderCount: number
}

/**
 * GET /api/board/orders/{id}/review：审核对话框里的三块（后端在 board 域，架构 §3.3）。
 * 只是参考，不拦审核；能不能审还是看详情的 actions、审核接口的检查
 */
export interface OrderReview {
  packs: OrderReviewPack[]
  /** 缺口最大的 5 种料（按缺口 × 单价排）；都够是 [] */
  shortages: OrderReviewShortage[]
  /** 款式没设默认加工厂的不出 */
  loads: OrderReviewLoad[]
}

// ---------------------------------------------------------------- 排单（后端在 production 域：POST /api/production-orders/schedule）

/** 加工方式字典 process_mode 的内置项：包工包料。选它生成成品采购单，别的（清加工、半包）生成外发加工单 */
export const PROCESS_MODE_FULL = 'FULL'

/** 排单对话框里一个款的一个 SKU */
export interface ScheduleSku {
  orderItemId: string
  styleSkuId: string
  colorId: string
  sizeCode: string
  orderQty: number
  scheduledQty: number
  /** 还没排的 = 订单数 − 已排产，不小于 0；对话框里默认填它 */
  remainQty: number
  /** 这个 SKU 最多还能排多少 = ⌊订单数 ×（1 + 溢装比例）⌋ − 已排产（溢装比例默认 3%，Q09） */
  maxQty: number
}

/** 排单对话框里一个款（只列还有没排完的款） */
export interface ScheduleStyle {
  styleId: string
  styleCode: string
  styleName: string
  image: Attachment | null
  /** 订单锁定的生产 BOM，生产单会带上它 */
  techPackId: string
  techPackVersion: number
  /** 锁定版本的加工费（填了的，没填取价格档案的清加工、再半包），外发加工的单价旁边提示"资料包加工费 38.00"；都没有是 null */
  laborPrice?: number | null
  /** 款式的默认加工厂；没设是 null */
  defaultFactoryId: string | null
  defaultFactoryName: string | null
  sizes: string[]
  /** 这张订单里这个款有的颜色 */
  colors: ColorOption[]
  skus: ScheduleSku[]
}

/** GET /api/production-orders/schedule-prefill?orderId=：排单对话框打开时带出的东西 */
export interface SchedulePrefill {
  orderId: string
  orderNo: string
  commitDate: string | null
  requiredDate: string
  /** 默认交期 = 承诺交期 − 10 天（没有承诺交期按要求交期） */
  defaultDueDate: string
  /** 默认生产跟单 = 订单的跟单员 */
  merchId: string
  merchName: string | null
  styles: ScheduleStyle[]
}

/** 一家工厂做这个款，一种加工方式的现价 */
export interface ScheduleModePrice {
  /** 字典 process_mode 的 value */
  processMode: string
  /** 价格档案里的现价，元 / 件；没有是 null（要手填） */
  price?: number | null
  priceSourceId: string | null
  effectiveDate: string | null
}

/**
 * GET /api/production-orders/schedule-quote?orderId=&styleId=&factoryId=：选了加工厂（或打开时用默认加工厂）带出的单价和结算信息。
 * 一次给齐每种加工方式的现价，切加工方式不用再问
 */
export interface ScheduleQuote {
  factoryId: string
  factoryName: string
  /** 能不能下大货单：合作中、分类里有成衣厂或加工厂才行；不能时 reason 写原因，对话框里标红 */
  usable: boolean
  reason: string | null
  /** 工厂档案上的结算方式（字典 settle_method）、税率 %，排单不改就用它们 */
  settleMethod: string | null
  taxPct: number | null
  prices?: ScheduleModePrice[]
  /** 默认加工方式：按字典 process_mode 的顺序，档案里有现价的第一种；都没有是字典第一项（原型 firstMode） */
  defaultMode: string
}

/** 排单的一块 = 生成一张生产单（一个加工厂 + 一个款式） */
export interface ScheduleBlockItem {
  orderItemId: string
  qty: number
}

export interface ScheduleBlock {
  styleId: string
  factoryId: string
  /** 字典 process_mode；FULL 生成成品采购单，别的生成外发加工单 */
  processMode: string
  /** 外发加工单是加工费，成品采购单是成品单价（含料），元 / 件 */
  unitPrice?: number
  /** 带出来的那条价格档案（ScheduleModePrice.priceSourceId）；手填的、档案里没有的发 null */
  priceSourceId: string | null
  /** 单价和档案现价不一样时必填，审核生产单时记进价格档案（Q25） */
  priceChangeReason: string | null
  dueDate: string
  merchId: string
  /** null = 按工厂档案的结算方式 */
  settleMethod: string | null
  /** 条款：质量要求、延期扣款…… */
  terms: string | null
  remark: string | null
  /** 件数是 0 的不发；一块一个都没有的整块不发 */
  items: ScheduleBlockItem[]
}

export interface ScheduleReq {
  orderId: string
  blocks: ScheduleBlock[]
}

/** 超排（1004）时 data 里的一条 */
export interface ScheduleOverRow {
  /** SKU 码 */
  sku: string
  orderQty: number
  scheduledQty: number
  /** 这次要排的（几块加起来） */
  thisQty: number
  /** 上限 = ⌊订单数 × 1.03⌋ */
  maxQty: number
  /** 如"最多还能排 3 件" */
  message: string
}

/** 排单成功：生成的生产单草稿（外发加工单、成品采购单），点单号用 docRoute */
export type ScheduleResult = DocRef[]

// ---------------------------------------------------------------- 订单变更单

/** 变更单只用这四个状态。驳回回到草稿，rejectReason 不空，页面显示"已驳回" */
export type ChangeStatus = Extract<DocStatus, 'DRAFT' | 'PENDING' | 'APPROVED' | 'VOIDED'>

export const CHANGE_STATUS: Record<ChangeStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  PENDING: { label: '待审核', tone: 'pending' },
  APPROVED: { label: '已审核', tone: 'active' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/** 草稿且被驳回过（rejectReason 不空）时用它代替 CHANGE_STATUS.DRAFT */
export const CHANGE_REJECTED: { label: string; tone: StatusTone } = { label: '已驳回', tone: 'danger' }

/** 变更单的按钮：通用的状态动作（submit、withdraw、reject、audit、void）+ 改草稿、删草稿 */
export type ChangeAction = DocAction | 'edit' | 'remove'

/** 变更类型（ord_change.change_types），后端按改了什么算出来，不用选 */
export type ChangeType = 'QTY' | 'DUE' | 'COLOR_SIZE' | 'PRICE' | 'TECH_PACK' | 'CANCEL'

export const CHANGE_TYPE: Record<ChangeType, string> = {
  QTY: '数量',
  DUE: '交期',
  COLOR_SIZE: '配色配码',
  PRICE: '单价',
  TECH_PACK: '资料包版本',
  CANCEL: '取消订单',
}

/** 责任方（ord_change.responsible_party）。CUSTOMER 是贸易代工留的，TRADE 关时不出 */
export type ResponsibleParty = 'SELF' | 'FACTORY' | 'SUPPLIER' | 'CUSTOMER'

export const RESPONSIBLE_PARTY: Record<ResponsibleParty, string> = {
  SELF: '我方',
  FACTORY: '工厂',
  SUPPLIER: '供应商',
  CUSTOMER: '客户',
}

/** 变更单列表的一行 */
export interface OrderChangeRow {
  id: string
  no: string
  status: ChangeStatus
  /** 上次被驳回的原因；不是"驳回后还没重新提交"的是 null */
  rejectReason: string | null
  orderId: string
  orderNo: string
  changeTypes: ChangeType[]
  /** 改了什么，后端拼好的一句话："27CF001-KQ-M：120 → 100；要求交期：03-20 → 03-25" */
  summary: string
  reason: string
  responsibleParty: ResponsibleParty
  feeNote?: string | null
  createByName: string | null
  createTime: string
  auditByName: string | null
  auditTime: string | null
  actions: ChangeAction[]
}

export interface OrderChangeQuery {
  /** 变更单号、订单号、SKU 码；一次查几个（README §3.2）时变更单号、订单号精确查 */
  keyword?: string
  status?: ChangeStatus[]
  /** 和 status=DRAFT 一起用：true 只要被驳回的（页面的"已驳回"），false 只要没被驳回的（页面的"草稿"）；不给 = 都要 */
  rejected?: boolean
  orderId?: string | null
  changeType?: ChangeType
  /** 提交（制单）日期，yyyy-MM-dd，两头都含 */
  createDateFrom?: string
  createDateTo?: string
  mine?: boolean
}

/** 变更明细的一处改动（ord_change_item） */
export interface ChangeItemView {
  id: string
  seq: number
  changeType: Exclude<ChangeType, 'CANCEL'>
  /** 改的是订单哪一行；加新 SKU 时是 null */
  orderItemId: string | null
  styleId: string | null
  styleCode: string | null
  styleSkuId: string | null
  skuCode: string | null
  /** 数量、配色配码：原 → 新（加 SKU 时原是 0，减掉一个 SKU 时新是 0） */
  fromQty: number | null
  toQty: number | null
  /** 交期：原 → 新 */
  fromDate: string | null
  toDate: string | null
  /** 单价（按款）：原 → 新 */
  fromPrice?: number | null
  toPrice?: number | null
  /** 资料包版本（按款）：原 → 新 */
  fromTechPackId: string | null
  fromVersion: number | null
  toTechPackId: string | null
  toVersion: number | null
  /** 这一处的说明，如"27CF001-KQ-M：120 → 100（已排 80）" */
  text: string
}

/** GET /api/order-changes/{id} */
export interface OrderChangeDetail extends DocHeader<ChangeStatus, ChangeAction> {
  orderId: string
  orderNo: string
  orderStatus: OrderStatus
  changeTypes: ChangeType[]
  /** 取消整张订单 */
  cancel: boolean
  reason: string
  responsibleParty: ResponsibleParty
  feeNote?: string | null
  rejectReason: string | null
  summary: string
  items: ChangeItemView[]
}

/** 数量、配色配码：一个 SKU 的新件数 */
export interface ChangeQtyForm {
  styleSkuId: string
  qty: number
}

/** 单价：一个款的新单价（这个款的每个 SKU 都改成它） */
export interface ChangePriceForm {
  styleId: string
  price: number
}

/** 资料包版本：一个款换到哪一版（同款生产 BOM 的已生效版） */
export interface ChangeTechPackForm {
  styleId: string
  techPackId: string
}

/**
 * 提变更、改草稿共用的列（整体覆盖）。后端拿它和订单现在的数比，一样的不算改动，变更类型也由它算出来。
 * 取消订单时 cancel = true，下面三组发 []、requiredDate 发 null
 */
export interface OrderChangeForm {
  cancel: boolean
  reason: string
  responsibleParty: ResponsibleParty
  /** 无 order:price:query 时整字段省略，服务器保留旧费用说明。 */
  feeNote?: string | null
  /** 新的要求交期；不改发 null */
  requiredDate: string | null
  /** 矩阵里每一格的新件数，没改的也发（新加的颜色、尺码也在里面）；改成 0 = 不要这个 SKU 了 */
  quantities: ChangeQtyForm[]
  /** 要改单价的款；无 order:price:query 时整段省略，不发缺 price 的占位行。 */
  prices?: ChangePriceForm[]
  /** 要换资料包版本的款；不换的不发 */
  techPacks: ChangeTechPackForm[]
}

/** 提变更多出来的：哪张订单、附件（变更依据：审批截图、邮件、聊天记录） */
export interface OrderChangeCreateForm extends OrderChangeForm {
  orderId: string
  files: FileVO[]
}

/** 改草稿多出来的：打开时拿到的版本号 */
export interface OrderChangeUpdateForm extends OrderChangeForm {
  version: number
}
