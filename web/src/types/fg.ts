// fg 域（成品入库单、成品出库单、成品库存、库存日志）的返回和请求，和后端 module/fg/dto 一一对应（docs/api/07-成品.md）。
// 数量都是件（整数）；单价、金额只有发货出库有，而且要看订单价权限（order:price:query），没有的接口不返回
import type { Attachment, DocAction, DocHeader, DocSaved, StatusTone } from '@/types/biz'
import type { EnableStatus } from '@/types/basic'

/** 一个款式 SKU 的显示字段：单据明细、库存、日志都带。名称、颜色、图是查询时现取的 */
export interface StyleSkuBrief {
  styleSkuId: string
  /** 款号-颜色码-尺码 */
  skuCode: string
  styleId: string
  styleCode: string
  styleName: string
  colorId: string
  colorName: string
  colorHex: string | null
  sizeCode: string
  /** 款式主图，没有是 null */
  image: Attachment | null
}

/**
 * GET /api/fg-docs/style-options?styleIds=：画颜色 × 尺码矩阵要的款式（SkuMatrix 的 sizes、colors、skus）。
 * 停用的颜色、SKU 也在（老款的期初库存、退回的货要能录），成品单据不按停用灰掉
 */
export interface FgStyleOption {
  styleId: string
  code: string
  name: string
  image: Attachment | null
  sizes: string[]
  colors: { id: string; code: string; name: string; hex: string | null }[]
  /** id 是款式 SKU id，也是矩阵的格子 id */
  skus: { id: string; colorId: string; sizeCode: string; code: string; status: EnableStatus }[]
}

// ---------------------------------------------------------------- 成品入库单、成品出库单（一张表 fg_doc，按 kind 分）

/** IN 成品入库单（CR）/ OUT 成品出库单（CC） */
export type FgDocKind = 'IN' | 'OUT'

export const FG_DOC_KIND_LABEL: Record<FgDocKind, string> = { IN: '成品入库单', OUT: '成品出库单' }

export type FgDocType = 'RECEIPT_IN' | 'OTHER_IN' | 'SHIP_OUT' | 'OTHER_OUT'

/** 一种类型在表单上长什么样（RFC 008 §3.1）。后端 fg.entity.FgDocType，两边一起改 */
export interface FgDocTypeMeta {
  label: string
  kind: FgDocKind
  /** 页头能手工新建；回货入库只能从"待入库"生成 */
  manual: boolean
  /** 原因字典（必填）；null = 没有原因这一格 */
  reasonDict: 'fg_in_reason' | 'fg_out_reason' | null
}

export const FG_DOC_TYPE: Record<FgDocType, FgDocTypeMeta> = {
  RECEIPT_IN: { label: '回货入库', kind: 'IN', manual: false, reasonDict: null },
  OTHER_IN: { label: '其他入库', kind: 'IN', manual: true, reasonDict: 'fg_in_reason' },
  SHIP_OUT: { label: '发货出库', kind: 'OUT', manual: true, reasonDict: null },
  OTHER_OUT: { label: '其他出库', kind: 'OUT', manual: true, reasonDict: 'fg_out_reason' },
}

/** 同物料入库单（04 §2.1）：审核即过账；过过账又反审核回来的草稿不能删、只能作废 */
export type FgDocStatus = 'DRAFT' | 'APPROVED' | 'VOIDED'

export const FG_DOC_STATUS: Record<FgDocStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  APPROVED: { label: '已审核', tone: 'active' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/** remove 删没过过账的草稿、void 作废过过账的草稿，两个只会有一个 */
export type FgDocAction = Extract<DocAction, 'audit' | 'unaudit' | 'void'> | 'edit' | 'remove'

export const FG_DOC_LOG_ACTION: Record<string, string> = {
  audit: '审核过账',
  unaudit: '反审核（库存改回去）',
}

/** 第一款的显示字段（列表"款式"一列） */
export interface FgStyleBrief {
  styleId: string
  styleCode: string
  styleName: string
  image: Attachment | null
}

export interface FgDocRow {
  id: string
  no: string
  kind: FgDocKind
  docType: FgDocType
  status: FgDocStatus
  /** 批量审核要带回去 */
  version: number
  companyId: string
  /** 入库单是入仓，出库单是出仓 */
  warehouseId: string
  warehouseName: string
  /** 回货入库：回货单、生产单（和 kind）、加工厂；别的是 null */
  receiptId: string | null
  receiptNo: string | null
  prdOrderNo: string | null
  prdOrderKind: 'OUTSOURCE' | 'BUY' | null
  factoryName: string | null
  /** 发货出库：需求订单 */
  orderId: string | null
  orderNo: string | null
  /** 其他入库、其他出库：字典值 */
  reason: string | null
  shipTo: string | null
  logisticsNo: string | null
  firstStyle: FgStyleBrief | null
  styleCount: number
  totalQty: number
  /** 发货出库、有看价权限才有 */
  totalAmount?: number | null
  /** yyyy-MM-dd */
  docDate: string
  remark: string | null
  createByName: string | null
  auditByName: string | null
  createTime: string
  actions: FgDocAction[]
}

export interface FgDocQuery {
  companyId?: string | null
  receiptId?: string | null
  /** 必填 */
  kind: FgDocKind
  docType?: FgDocType
  status?: FgDocStatus[]
  warehouseId?: string | null
  styleId?: string | null
  /** 回货入库的加工厂 */
  factoryId?: string | null
  /** 发货出库的需求订单 */
  orderId?: string | null
  docDateFrom?: string
  docDateTo?: string
  /** 单号、回货单号、生产单号、订单号、物流单号、款号；一次查几个时单号精确查 */
  keyword?: string
  mine?: boolean
  /** 导出选中的 */
  ids?: string[]
}

export interface FgDocItem extends StyleSkuBrief {
  id: string
  seq: number
  qty: number
  /** 回货入库：回货明细 id */
  prdReceiptItemId: string | null
  /** 发货出库：订单行 id */
  orderItemId: string | null
  price?: number | null
  amount?: number | null
  remark: string | null
  /** 草稿出库单：出仓现在的库存；别的是 null */
  stockQty: number | null
  /** 草稿的回货入库、发货出库：这一行最多能填多少（含本单已填的）；别的是 null */
  maxQty: number | null
}

export interface FgDocDetail extends DocHeader<FgDocStatus, FgDocAction> {
  kind: FgDocKind
  docType: FgDocType
  warehouseId: string
  warehouseName: string
  /** 回货入库的回货单 */
  receipt: {
    id: string
    no: string
    prdOrderId: string
    prdOrderNo: string
    prdOrderKind: 'OUTSOURCE' | 'BUY'
    factoryName: string
    receiptDate: string
  } | null
  /** 发货出库的需求订单 */
  order: { id: string; no: string; deliveryReq: string | null } | null
  docDate: string
  reason: string | null
  shipTo: string | null
  logisticsNo: string | null
  totalQty: number
  totalAmount?: number | null
  /** 过过账（有库存日志）：草稿时 actions 给 void 不给 remove */
  posted: boolean
  /** 单上各款画矩阵的数据，按单上的顺序 */
  styles: FgStyleOption[]
  items: FgDocItem[]
}

/** 一行明细：一个 SKU 一行，qty 是正整数 */
export interface FgDocItemForm {
  styleSkuId: string
  qty: number
  remark: string | null
}

/** 改草稿（PUT /api/fg-docs/{id}），整单覆盖。类型、主体、回货单、订单建好就不能改 */
export interface FgDocForm {
  companyId: string
  version: number
  warehouseId: string
  /** yyyy-MM-dd，不能晚于今天 */
  docDate: string
  reason: string | null
  shipTo: string | null
  logisticsNo: string | null
  remark: string | null
  items: FgDocItemForm[]
}

/** 手工新建（POST /api/fg-docs）：其他入库、发货出库、其他出库 */
export interface FgDocCreateForm extends Omit<FgDocForm, 'version'> {
  docType: Exclude<FgDocType, 'RECEIPT_IN'>
  /** 发货出库必填 */
  orderId: string | null
}

export interface FgDocCreated extends DocSaved {
  /** post = true 时是 APPROVED */
  status: FgDocStatus
}

// ---------------------------------------------------------------- 回货入库

export interface FgPendingReceiptQuery {
  companyId?: string | null
  factoryId?: string | null
  receiptDateFrom?: string
  receiptDateTo?: string
  /** 回货单号、生产单号、款号 */
  keyword?: string
}

/** 待入库的一张回货单。数量都是件：良品 = 回货 − 次品，还没入 = 良品 − 已入（没作废的成品入库单，草稿也算） */
export interface FgPendingReceiptRow {
  companyId: string
  reservedQty: number
  postedQty: number
  remainingQty: number
  receiptId: string
  receiptNo: string
  prdOrderId: string
  prdOrderNo: string
  prdOrderKind: 'OUTSOURCE' | 'BUY'
  styleId: string
  styleCode: string
  styleName: string
  image: Attachment | null
  factoryId: string
  factoryName: string
  qty: number
  defectQty: number
  goodQty: number
  stockedQty: number
  remainQty: number
  /** 回货单上的收货仓 */
  warehouseId: string | null
  warehouseName: string | null
  receiptDate: string
}

/** 从回货单带出（GET /api/fg-docs/from-receipt/{receiptId}）：单头默认值 + 矩阵 + 每行 */
export interface FgReceiptDraft {
  companyId: string
  receiptId: string
  receiptNo: string
  prdOrderNo: string
  prdOrderKind: 'OUTSOURCE' | 'BUY'
  factoryName: string
  receiptDate: string
  /** 默认入仓：回货单的收货仓（是启用的成品仓时），否则第一个启用的成品仓 */
  warehouseId: string
  warehouseName: string
  style: FgStyleOption
  /** 还没入是 0 的行不带 */
  items: {
    prdReceiptItemId: string
    styleSkuId: string
    qty: number
    defectQty: number
    goodQty: number
    reservedQty: number
    postedQty: number
    remainingQty: number
    stockedQty: number
    /** 还没入的良品，也是默认值 */
    maxQty: number
  }[]
}

/**
 * 由回货单生成（POST /api/fg-docs/from-receipt/{receiptId}）。没填的单头发 null，按带出的默认值建。
 * items 是 null = 每行按还没入的全入（批量入库用）；给了就按给的，0 的行不建。post 一律发 false：自己建的单不能自己审（Q10），true 后端总回 1000
 */
export interface FgFromReceiptReq {
  companyId: string
  clientActionId: string
  post: boolean
  docDate: string | null
  warehouseId: string | null
  remark: string | null
  items: { prdReceiptItemId: string; qty: number }[] | null
}

// ---------------------------------------------------------------- 发货出库

/** 能发货的需求订单（下拉） */
export interface FgShipOrderOption {
  companyId: string
  id: string
  no: string
  /** 字典 order_type */
  orderType: string
  status: string
  statusLabel: string
  /** 款号，几个款逗号隔开 */
  styleCodes: string
  totalQty: number
  shippedQty: number
  /** 给了 warehouseId 时：这个仓里订单上 SKU 的库存件数；没给是 null */
  stockQty: number | null
}

export interface FgShipPrefill {
  orderId: string
  orderNo: string
  companyId: string
  /** 订单的送货要求，"发到"的默认值 */
  deliveryReq: string | null
  styles: FgStyleOption[]
  lines: {
    orderItemId: string
    styleSkuId: string
    orderQty: number
    /** 本单以外已审核的发货出库 */
    shippedQty: number
    /** ⌊订单数 × 1.03⌋ − 已发，不小于 0 */
    maxQty: number
    /** 出仓现库存；没给仓是 0 */
    stockQty: number
    price?: number | null
  }[]
}

// ---------------------------------------------------------------- 给上游页面的

/** 需求订单详情的"发 n"：已审核的发货出库 */
export interface FgShippedRow {
  styleSkuId: string
  shippedQty: number
}

/** 一张回货单的入库占用；单据预览最多 20 张，数量按真实全部占用计算。 */
export interface FgReceiptStocked {
  receiptId: string
  documents: { id: string; no: string; status: FgDocStatus; qty: number }[]
  documentsTotal: number
  hasMoreDocuments: boolean
  reservedQty: number
  postedQty: number
  remainingQty: number
}

// ---------------------------------------------------------------- 库存、库存日志

export interface FgStockQuery {
  companyId?: string | null
  warehouseId?: string | null
  styleId?: string | null
  /** 款号、款名、SKU 码 */
  keyword?: string
  /** 列出数量是 0 的行；默认不列 */
  includeZero?: boolean
  /** 导出选中的（明细） */
  ids?: string[]
}

export interface FgStockRow extends StyleSkuBrief {
  companyId: string
  id: string
  warehouseId: string
  warehouseName: string
  qty: number
  /** 最近变动 */
  updateTime: string
}

/** 按款汇总：一款一行，几个仓的加在一起；点开画只读矩阵 */
export interface FgStockByStyleRow extends FgStyleOption {
  companyId: string
  totalQty: number
  /** 款式 SKU id → 件数；没有的是 0 */
  qtys: Record<string, number>
}

export interface FgStockQty {
  companyId: string
  warehouseId: string
  styleSkuId: string
  qty: number
}

export interface FgStockLogQuery {
  companyId?: string | null
  warehouseId?: string | null
  styleSkuId?: string | null
  styleId?: string | null
  changeType?: FgDocType[]
  bizDateFrom?: string
  bizDateTo?: string
  /** 单号、款号、SKU 码 */
  keyword?: string
}

export interface FgStockLogRow extends StyleSkuBrief {
  companyId: string
  locationId: string
  reversalOfLogId: string | null
  requestId: string | null
  id: string
  bizDate: string
  changeType: FgDocType
  docId: string
  docKind: FgDocKind
  docNo: string
  warehouseId: string
  warehouseName: string
  /** 入正出负 */
  changeQty: number
  balanceQty: number
  createByName: string | null
  createTime: string
}

// ---------------------------------------------------------------- 1001、1004 的清单

/** 1001 库存不够：data 是 FgShortRow[] */
export interface FgShortRow {
  /** 单上第几行 */
  seqs: string
  warehouseName: string
  skuCode: string
  styleName: string
  colorName: string
  sizeCode: string
  stockQty: number
  needQty: number
  shortQty: number
}

/** 1004 超出上限：data 是 FgOverRow[] */
export interface FgOverRow {
  seq: number
  skuCode: string
  /** 回货单号 / 订单号 */
  sourceNo: string
  thisQty: number
  maxQty: number
  /** "订单 120 件，溢装 3%，已发 0" / "良品 396，已入 300" */
  reason: string
}
