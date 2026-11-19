// purchase 域（算料单、采购单、待到料、齐料状态、补货提醒）的返回和请求，和后端 module/purchase/dto 一一对应（docs/api/03-算料采购.md）。
// 数量没写单位的都是库存单位；采购单位的字段注释里写明。NUMERIC 列到前端是 JSON 数字，显示走 utils/format.ts
import type { DocAction, DocHeader, DocSaved, StatusTone } from '@/types/biz'
import type { WarehouseType } from '@/types/basic'
import type { InvDocKind, InvDocStatus, InvDocType, MaterialSkuBrief } from '@/types/inventory'
import type { SupplierStatus } from '@/types/supplier'

// ---------------------------------------------------------------- 算料单（mrp_calc）

/** 算料单自己的状态（不是 DocStatus）：草稿 → 已确认 → 已关闭。已确认、没关闭的算料单构成软占用（架构 §5.4） */
export type MrpStatus = 'DRAFT' | 'CONFIRMED' | 'CLOSED'

export const MRP_STATUS: Record<MrpStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  CONFIRMED: { label: '已确认', tone: 'active' },
  CLOSED: { label: '已关闭', tone: 'closed' },
}

/** 关闭原因：订单的生产单都领完料（自动）/ 手动关闭 / 重新算料的新单确认了（自动） */
export type MrpCloseReason = 'ISSUED' | 'MANUAL' | 'RECALC'

export const MRP_CLOSE_REASON_LABEL: Record<MrpCloseReason, string> = {
  ISSUED: '生产单都领完料',
  MANUAL: '手动关闭',
  RECALC: '重新算料',
}

/**
 * 详情页的按钮：edit 改行（草稿四格都能改，已确认只能换供应商）、删行（草稿），confirm 确认，close 关闭，recalc 重新算料，
 * remove 删草稿，toPurchase 生成采购单（没关闭、没有重新算料的草稿、还有没转完的行、有 purchase:order:add）
 */
export type MrpAction = 'edit' | 'confirm' | 'close' | 'recalc' | 'remove' | 'toPurchase'

/** 操作记录里的动作叫法（DocLogList 的 actions）。statuses 传 MRP_STATUS 的 label */
export const MRP_LOG_ACTION: Record<string, string> = {
  create: '算料',
  confirm: '确认',
  close: '关闭',
  recalc: '重新算料',
  'update-item': '改行',
  'remove-item': '删行',
  'to-purchase': '生成采购单',
  converted: '已转采购变了',
}

/** 结果块：PURCHASE 自购（要算、要买）/ FACTORY 厂购（只列不算）。TRADE 开时多一块 CUSTOMER 待客户提供，关着时不出现 */
export type MrpBlock = 'PURCHASE' | 'FACTORY'

/** 列表的一行 */
export interface MrpCalcRow {
  id: string
  no: string
  status: MrpStatus
  /** 品类线（字典 product_line，RFC 013 §2） */
  productLine: string
  orderNos: string[]
  /** 订单里的款号，去重 */
  styleCodes: string[]
  /** 自购里建议采购量 > 0 的行数（"要买 n 种"） */
  buyCount: number
  /** 其中已经全转了采购的行数（"转采购"进度 convertedCount / buyCount） */
  convertedCount: number
  /** 要料日期 yyyy-MM-dd；没排单、没承诺交期算不出来是 null */
  needDate: string | null
  /** 重新算料出来的新单（草稿或已确认）的 id、单号，列表上"→ SL…"；没有是 null。新单确认时这张才关闭 */
  nextCalcId: string | null
  nextCalcNo: string | null
  createByName: string | null
  createTime: string
}

export interface MrpCalcQuery {
  /** 算料单号、订单号、款号；一次查几个（README §3.2）时这三个都精确查 */
  keyword?: string
  status?: MrpStatus[]
  createBy?: string | null
  createDateFrom?: string
  createDateTo?: string
  mine?: boolean
}

/** 本次使用在途用了哪条采购行（mrp_calc_transit），"明细"里列 */
export interface MrpTransitUse {
  purchaseItemId: string
  purchaseOrderId: string
  purchaseNo: string
  purchaseType: PurchaseType
  /** 计算时那行的预计到货（没填用交期） */
  eta: string | null
  /** 用的数量 */
  qty: number
  /** 其中已经到货的（齐料按它算） */
  arrivedQty: number
}

/** 赶不上的在途：预计到货晚于要料日期，只列出来、不扣（计算时的快照） */
export interface MrpLateTransit {
  purchaseItemId: string
  purchaseOrderId: string
  purchaseNo: string
  purchaseType: PurchaseType
  eta: string | null
  /** 当时还能用的数（去掉别的算料单占着的） */
  freeQty: number
}

/** 算料结果的一行（mrp_calc_item）。数字是计算时存下的；草稿里改了本次使用、建议采购，后端重算净需求、预计金额 */
export interface MrpItem extends MaterialSkuBrief {
  id: string
  seq: number
  block: MrpBlock
  /** 供应商：BOM 行上的，没有用物料的默认供应商；都没有是 null（生成采购单前要在行上选） */
  supplierId: string | null
  supplierName: string | null
  /** 供应商现在不是合作中：生成采购单会被拦，行上标出来 */
  supplierStatus: SupplierStatus | null
  /** 毛需求 = Σ 件数 × 单件用量 ×（1 + 损耗率） */
  grossQty: number
  /** 用的备料比 %（物料上设的，没设按物料类型） */
  reservePct: number
  /** 需求 = 毛需求 ×（1 + 备料比） */
  needQty: number
  /** 可用库存 = 物料仓库存 − 别的算料单占着的 */
  availStockQty: number
  /** 本次使用库存：默认 min（可用库存，需求），草稿里能改小 */
  useStockQty: number
  /** 可用在途：赶得上要料日期的在途，去掉别的算料单占着的 */
  availTransitQty: number
  /** 本次使用在途：默认 min（可用在途，需求 − 本次使用库存），草稿里能改小；用的哪几条见 transitUses */
  useTransitQty: number
  /** 赶不上的在途，只看；哪几条见 lateTransits */
  lateTransitQty: number
  /** 净需求 = 需求 − 本次使用库存 − 本次使用在途 */
  netQty: number
  /** 计算时物料的采购单位（字典 unit）、换算系数（1 采购单位 = n 库存单位） */
  purchaseUnit: string
  conversionFactor: number
  /** 建议采购量，采购单位：净需求 ÷ 换算系数，向上取整到最小包装，不小于起订量；草稿里能直接改（不小于已转采购） */
  suggestQty: number
  /** 参考单价（元 / 采购单位）：这家供应商的价格档案现价，没有用 SKU 单价、物料参考单价；都没有是 null */
  refPrice?: number | null
  /** 预计金额 = 建议采购量 × 参考单价 */
  estAmount?: number | null
  /** 已转采购量，采购单位：生成采购单时加，采购单草稿改数量、删除、作废时跟着改 */
  convertedQty: number
  /** 这一行（连同重新算料带过来的）生成的采购行净入库（已入库 − 已退货），库存单位 */
  receivedQty: number
  /** 齐料还差多少 = 需求 − 本次使用库存 − 本次使用在途里已到的 − 已入库，不小于 0。0 显示"齐"，> 0 显示"差 n"（PRD §6.6.3）；厂购的行是 0 */
  shortQty: number
  /** 用到它的订单号、款号 */
  orderNos: string[]
  styleCodes: string[]
  transitUses: MrpTransitUse[]
  lateTransits: MrpLateTransit[]
  /** 能勾去生成采购单：自购、建议采购量 > 已转采购、算料单没关闭、没有重新算料的草稿 */
  canConvert: boolean
}

/** 和上一张比（重新算料出来的单才有）：需求变了的行 */
export interface MrpDiffRow extends MaterialSkuBrief {
  block: MrpBlock
  /** 上一张的需求；上一张没有这种料是 0 */
  fromNeedQty: number
  /** 这一张的需求；这一张没有了是 0 */
  toNeedQty: number
  /** 变化，正的是多了 */
  diffQty: number
}

export interface MrpOrderRef {
  orderId: string
  orderNo: string
  styleCodes: string[]
}

/** 详情。auditBy、auditTime 在这里是确认人、确认时间；approval 一直是 null */
export interface MrpCalcDetail extends DocHeader<MrpStatus, MrpAction> {
  /** 品类线：选的订单的线，一次只算一条线 */
  productLine: string
  orders: MrpOrderRef[]
  styleCodes: string[]
  /** 要料日期：几张订单里最早的"裁剪"计划开始，没排单的按承诺交期倒推；算不出来是 null */
  needDate: string | null
  prevCalcId: string | null
  prevCalcNo: string | null
  nextCalcId: string | null
  nextCalcNo: string | null
  closeReason: MrpCloseReason | null
  /** 手动关闭的人；自动关闭是 null */
  closeByName: string | null
  closeTime: string | null
  /** 自购的预计金额合计、已转采购的金额合计（按参考单价） */
  estAmount?: number
  convertedAmount?: number
  /** 自购、厂购都在这里，按 block 分开画 */
  items: MrpItem[]
  /** 重新算料出来的单：和上一张比需求变了的行（没变是空数组）；不是重新算料出来的是 null */
  diff: MrpDiffRow[] | null
}

/** "选订单算料"对话框的一行：已审核、生产中的需求订单（只有它们能算料） */
export interface MrpOrderOption {
  orderId: string
  orderNo: string
  /** 字典 order_type */
  orderType: string
  /** 品类线：不同线的订单要分开算料 */
  productLine: string
  styleCodes: string[]
  totalQty: number
  commitDate: string | null
  /** 这张订单算过的算料单（作废、重新算料换掉的也列，好知道算过几次） */
  calcs: { id: string; no: string; status: MrpStatus }[]
}

export type MrpCreated = DocSaved

/**
 * 草稿里改一行（PUT /api/mrp-calcs/{id}/items/{itemId}），一次改一格：
 * - 改了本次使用库存或本次使用在途：后端按新值重分在途、重算净需求和建议采购量，发来的 suggestQty 不看；
 * - 只改了建议采购量：照填的存（不小于已转采购）；
 * - 换了供应商：参考单价按新供应商的现价重取。
 * 本次使用只能改小：库存不超过 min（可用库存，需求），在途不超过 min（可用在途，需求 − 本次使用库存），超了 400
 * 已确认的单只能换供应商（这一行还没转完），另外三格发原值，变了回 1000
 */
export interface MrpItemForm {
  version: number
  supplierId: string | null
  useStockQty: number
  useTransitQty: number
  suggestQty: number
}

/** 物料类型的备料比（字典 material_type 的数值列）：物料上没设备料比时用它，只影响以后新算的单 */
export interface ReservePct {
  /** 字典 material_type 的 value */
  materialType: string
  label: string
  /** 0 ~ 50 */
  pct: number
}

// ---------------------------------------------------------------- 采购单（pur_order）

export type PurchaseType = 'ORDER' | 'STOCK' | 'SAMPLE'

export const PURCHASE_TYPE_LABEL: Record<PurchaseType, string> = { ORDER: '订单采购', STOCK: '备货采购', SAMPLE: '打样采购' }

/** 采购单用全套通用状态 */
export type PurchaseStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'RUNNING' | 'DONE' | 'CLOSED' | 'VOIDED'

/** 按采购单叫：RUNNING 部分到货、DONE 已到齐 */
export const PURCHASE_STATUS: Record<PurchaseStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  PENDING: { label: '待审核', tone: 'pending' },
  APPROVED: { label: '已审核', tone: 'active' },
  RUNNING: { label: '部分到货', tone: 'progress' },
  DONE: { label: '已到齐', tone: 'done' },
  CLOSED: { label: '已结案', tone: 'closed' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * 详情、列表行上能点的：通用动作，加 edit 改草稿、remove 删草稿、receive 到货入库（已审核、部分到货，要 inventory:doc:add）、
 * return 退货（部分到货、已到齐，要 inventory:doc:add）。打印不受状态限制，不在这里
 */
export type PurchaseAction = DocAction | 'edit' | 'remove' | 'receive' | 'return'

export const PURCHASE_LOG_ACTION: Record<string, string> = {
  close: '结案',
  reopen: '取消结案',
  receive: '入库',
  return: '退货',
  follow: '改预计到货、跟进',
  'price-archive': '单价记进价格档案',
  'from-mrp': '由算料单生成',
  'from-reorder': '由补货提醒生成',
  status: '到货状态变了',
}

/** 列表的一行 */
export interface PurchaseRow {
  id: string
  no: string
  supplierId: string
  /** 简称 */
  supplierName: string
  /** 供应商现在的状态：不是合作中的，列表上标出来（"供应商已停用"：能继续入库，不能新建） */
  supplierStatus: SupplierStatus
  purchaseType: PurchaseType
  /** 品类线（字典 product_line，RFC 013 §2） */
  productLine: string
  /** 第一行物料，"等 n 种"用 itemCount */
  firstItem: MaterialSkuBrief | null
  itemCount: number
  /** 各行采购单位数量直接相加，只显示 */
  totalQty: number
  totalAmount?: number
  orderDate: string
  deliveryDate: string
  warehouseId: string
  warehouseName: string
  /** 净入库合计 =（已入库 − 已退货），采购单位；"到货"进度 = receivedQty / totalQty，草稿、待审核、作废显示"—" */
  receivedQty: number
  buyerId: string
  buyerName: string
  companyName: string
  status: PurchaseStatus
  /** 批量提交、审核要带回去 */
  version: number
  /** 行上的按钮，批量条用（submit、audit……；audit 只给当前这一级的审批人） */
  actions: PurchaseAction[]
  createTime: string
}

export interface PurchaseQuery {
  /** 当前待我审批；忽略 mine / owner，仍要求模块列表权限 */
  auditForMe?: boolean
  /** 采购单号、供应商、物料编码 / 名称 / SKU 码、订单号、款号、算料单号；一次查几个（README §3.2）时单号、款号、物料编码精确查 */
  keyword?: string
  status?: PurchaseStatus[]
  purchaseType?: PurchaseType
  /** 品类线：几条线的人才有这个筛选 */
  productLine?: string
  supplierId?: string | null
  buyerId?: string | null
  orderDateFrom?: string
  orderDateTo?: string
  mine?: boolean
  ids?: string[]
}

/** 明细的一行（pur_order_item）。数量、单价、已入库、已退货都按采购单位 */
export interface PurchaseItem extends MaterialSkuBrief {
  id: string
  seq: number
  /** SKU 级的供应商货号，没有用物料的；都没有是 null */
  supplierItemNo: string | null
  supplierColorNo: string | null
  /** 下单时物料的采购单位、换算系数（存在行上，物料以后改了不影响） */
  purchaseUnit: string
  conversionFactor: number
  qty: number
  /** 元 / 采购单位 */
  price?: number
  amount?: number
  /** 默认单价取自哪条价格档案；手填、没有档案是 null */
  priceSourceId: string | null
  /** 价格档案里这家供应商在下单日的现价（"档案 18.50"）；没有档案是 null。和 price 不一样时要有改价原因 */
  archivePrice?: number | null
  priceChangeReason: string | null
  /** 行交期，null = 用单头交货日期 */
  lineDueDate: string | null
  /** 实际用的交期 = 行交期 ?? 单头交货日期 */
  dueDate: string
  /** 来源算料行；手建的行是 null */
  calcItemId: string | null
  calcId: string | null
  calcNo: string | null
  /** 来源订单号、款号（从算料行带出）；手建的是空数组 */
  orderNos: string[]
  styleCodes: string[]
  receivedQty: number
  returnedQty: number
  /** 未入库 = 采购数 −（已入库 − 已退货），不小于 0（超收了也是 0） */
  pendingQty: number
  /** 预计到货日期、跟进备注：审核后在"待到料"里随时改（follow 接口） */
  expectDate: string | null
  followNote: string | null
}

/** 详情的"入库 / 退货"块：这张单生成的物料入库单、出库单（草稿也列，标"草稿"） */
export interface PurchaseStockDoc {
  id: string
  no: string
  /** 点单号：docRoute({ type: 'INV_DOC', kind, id, parentId: null }) */
  kind: InvDocKind
  docType: InvDocType
  status: InvDocStatus
  docDate: string
  /** 入库是入仓，退货是出仓 */
  warehouseName: string
  /** 各行采购单位数量相加 */
  purchaseQty: number
  /** 各行的缸号，去重 */
  dyeLots: string[]
  logisticsNo: string | null
}

export interface PlanStyle {
  styleId: string
  styleCode: string
  styleName: string
}

export interface PurchaseDetail extends DocHeader<PurchaseStatus, PurchaseAction> {
  supplierId: string
  supplierCode: string
  /** 全称；supplierShortName 是简称 */
  supplierName: string
  supplierShortName: string
  supplierStatus: SupplierStatus
  /** 打印用：供应商的联系人、电话，没有是 null */
  supplierContact?: string | null
  supplierPhone?: string | null
  purchaseType: PurchaseType
  /** 品类线：从算料单生成的跟算料单（改不了），手工建的选；供应商要做这条线 */
  productLine: string
  /** 从哪张算料单生成的；手建的、补货生成的是 null */
  calcId: string | null
  calcNo: string | null
  buyerId: string
  buyerName: string
  orderDate: string
  deliveryDate: string
  /** 交货地点：物料仓，或某个工厂仓（面料直发工厂） */
  warehouseId: string
  warehouseName: string
  warehouseType: WarehouseType
  /** 打印用：仓库地址 */
  warehouseAddress: string | null
  /** 字典 settle_method；草稿里可以空着（供应商上没设），提交时必填 */
  settleMethod: string | null
  /** 税率 %，同上 */
  taxPct: number | null
  taxIncluded: boolean
  freightIncluded: boolean
  totalQty: number
  totalAmount?: number
  /** 净入库合计（已入库 − 已退货），采购单位；到货进度 = receivedQty / totalQty */
  receivedQty: number
  returnedQty: number
  /** 计划用于款号：备货采购选填，只是备注，不预留（PRD §6.6.7） */
  planStyles: PlanStyle[]
  items: PurchaseItem[]
  stockDocs: PurchaseStockDoc[]
}

/** 新建、改草稿的一行。保存是整体覆盖：没填的发 null */
export interface PurchaseItemForm {
  /** 已有行的 id：没传价格时靠它保留服务端的价格。 */
  id?: string
  /** 从算料单生成的行原样带回（物料不能换，数量能改，算料单的已转采购跟着改）；手加的行是 null */
  calcItemId: string | null
  materialSkuId: string
  /** 采购单位 */
  qty: number | null
  /** null = 用默认单价（价格档案现价 → SKU 单价 → 物料参考单价）；和档案现价不一样时 priceChangeReason 必填 */
  price?: number | null
  priceChangeReason: string | null
  lineDueDate: string | null
  expectDate: string | null
}

/** 新建（POST /api/purchase-orders）。附件建好以后用 replaceAttachments 传 */
export interface PurchaseForm {
  companyId: string
  supplierId: string
  purchaseType: PurchaseType
  /** 品类线：只有一条线的人不用传（后端带上）；从算料单生成的改不了，传了也不看 */
  productLine?: string | null
  buyerId: string
  orderDate: string
  /** null = 下单日 + 各行物料采购周期最长的（没设的按 7 天） */
  deliveryDate: string | null
  warehouseId: string
  /** 草稿能发 null，提交时必填 */
  settleMethod: string | null
  taxPct: number | null
  taxIncluded: boolean
  freightIncluded: boolean
  remark: string | null
  /** 计划用于款号：只有备货采购收，别的类型发 [] */
  planStyleIds: string[]
  items: PurchaseItemForm[]
}

/** 改草稿（PUT /api/purchase-orders/{id}） */
export interface PurchaseUpdateForm extends PurchaseForm {
  version: number
  expectedCompanyId?: string
}

export type PurchaseCreated = DocSaved

/** 表单的默认值（GET /api/purchase-orders/defaults）：换供应商、加物料、改下单日期时取一次 */
export interface PurchaseDefaultsQuery {
  supplierId: string
  /** 取哪天的现价，不传 = 今天 */
  orderDate?: string
  /** 表单上全部行的 SKU（不只是新加的）：交货日期的默认值按全部行算 */
  skuIds?: string[]
}

/** 一个 SKU 在这家供应商的默认值 */
export interface PurchaseLineDefault {
  materialSkuId: string
  /** 默认单价（元 / 采购单位）：价格档案现价 → SKU 单价 → 物料参考单价；都没有是 null（要手填） */
  price?: number | null
  priceSourceId: string | null
  /** 档案现价；null = 这家还没有这种料的档案价（第一次报价），改价不用写原因 */
  archivePrice?: number | null
  purchaseUnit: string
  conversionFactor: number
  /** 物料的采购周期（天），没设是 null */
  leadDays: number | null
  supplierItemNo: string | null
  supplierColorNo: string | null
}

export interface PurchaseDefaults {
  /** 供应商上的结算方式（字典 settle_method）、税率、采购员；没设是 null */
  settleMethod: string | null
  taxPct: number | null
  buyerId: string | null
  buyerName: string | null
  /** 交货日期的默认值 = 下单日 + skuIds 里最长的采购周期（没设的按 7 天）；skuIds 空是 null */
  deliveryDate: string | null
  lines: PurchaseLineDefault[]
}

// ---------------------------------------------------------------- 待到料（物料入库单页的第一个页签，数据在采购）

/** 已逾期（今天 > 预计到货，没有预计用交期）/ 临期（3 天内）/ 正常 */
export type ArrivalRisk = 'LATE' | 'NEAR' | 'NORMAL'

export const ARRIVAL_RISK: Record<ArrivalRisk, { label: string; tone: StatusTone | null }> = {
  LATE: { label: '已逾期', tone: 'danger' },
  NEAR: { label: '临期', tone: 'pending' },
  NORMAL: { label: '正常', tone: null },
}

/** 待到料的一行：已审核、部分到货的采购单里没入齐的行（未入库 > 0）。数量按采购单位 */
export interface PendingArrivalRow extends MaterialSkuBrief {
  purchaseItemId: string
  purchaseOrderId: string
  purchaseNo: string
  purchaseType: PurchaseType
  supplierId: string
  supplierName: string
  buyerId: string
  buyerName: string
  /** 交货地点 */
  warehouseId: string
  warehouseName: string
  purchaseUnit: string
  qty: number
  receivedQty: number
  returnedQty: number
  /** 未入库 = 采购数 −（已入库 − 已退货） */
  pendingQty: number
  /** 交期 = 行交期 ?? 单头交货日期 */
  dueDate: string
  expectDate: string | null
  followNote: string | null
  /** 按哪天算逾期：预计到货 ?? 交期 */
  arriveDate: string
  /** 剩几天 = arriveDate − 今天，负数是逾期几天 */
  daysLeft: number
  risk: ArrivalRisk
  /**
   * 要料日期：订单采购看来源订单，备货采购被算料用了（本次使用在途）看用它的订单，取生产单"裁剪"的计划开始，
   * 没排单按承诺交期倒推；没人用的备货采购是 null（PRD §6.6.3）
   */
  needDate: string | null
  /** 晚于开裁：arriveDate 比要料日期晚几天；赶得上、没有要料日期是 0 */
  cutLateDays: number
  orderNos: string[]
  styleCodes: string[]
  calcNo: string | null
  /** receive 入库（要 inventory:doc:add），follow 改预计到货、跟进备注（要 purchase:order:edit） */
  actions: ('receive' | 'follow')[]
}

export interface PendingArrivalQuery {
  /** 页签：LATE 已逾期 / NEAR 3 天内 / CUT 晚于开裁；不传 = 全部 */
  tab?: 'LATE' | 'NEAR' | 'CUT'
  /** 采购单号、物料编码 / 名称 / SKU 码、供应商、订单号、款号、算料单号；一次查几个（README §3.2）时单号、款号、物料编码精确查 */
  keyword?: string
  supplierId?: string | null
  buyerId?: string | null
  /** 按交期（行交期 ?? 单头交货日期）筛 */
  dueDateFrom?: string
  dueDateTo?: string
}

/** 页签上的数（筛选条件同列表，不看 tab） */
export interface PendingArrivalCounts {
  all: number
  late: number
  near: number
  cut: number
}

/** 改预计到货、跟进备注（PUT …/items/{itemId}/follow）。两格都发，没填的发 null */
export interface FollowForm {
  expectDate: string | null
  followNote: string | null
}

// ---------------------------------------------------------------- 齐料状态（订单列表、生产单列表的"齐料"列）

/** 未算料 / 未到料 / 部分到料 / 齐料（PRD §6.6.3） */
export type KittingStatus = 'NONE' | 'NOT' | 'PARTIAL' | 'DONE'

export const KITTING_STATUS: Record<KittingStatus, { label: string; tone: StatusTone }> = {
  NONE: { label: '未算料', tone: 'off' },
  NOT: { label: '未到料', tone: 'pending' },
  PARTIAL: { label: '部分到料', tone: 'progress' },
  DONE: { label: '齐料', tone: 'done' },
}

/**
 * 一张需求订单的齐料状态（PRD §6.6.3）：看含这张订单的已确认算料单，"自购"每一行
 * 已入库 + 本次使用在途里已到的 + 本次使用库存 ≥ 需求就算齐（就是 MrpItem.shortQty = 0）。
 * 草稿、手动关闭、被重新算料换掉的不算；因领完料关闭的直接算齐料；一张都没有是未算料。
 * 一张算料单有几张订单的，几张订单看到的是同一组行（一期不按订单拆行）；自购一行都没有也算齐料
 */
export interface OrderKitting {
  orderId: string
  status: KittingStatus
  /** 齐了的行数 / 自购的行数；未算料时都是 0 */
  readyCount: number
  totalCount: number
  /** 标签上的字："部分到料 3/5"、"齐料"、"未算料" */
  label: string
  /** 参与计算的算料单 */
  calcIds: string[]
}

// ---------------------------------------------------------------- 补货提醒、物料的在途

/**
 * 补货清单的一行：设了最小库存、物料仓库存 + 全部在途 − 已被占用 < 最小库存的物料 SKU（PRD §6.6.7）。
 * 这里的在途不看赶不赶得上开裁；数量都是库存单位，只有 suggestQty 是采购单位
 */
export interface ReorderRow extends MaterialSkuBrief {
  /** 物料的默认供应商；没设是 null，要先在行上选 */
  defaultSupplierId: string | null
  defaultSupplierName: string | null
  defaultSupplierStatus: SupplierStatus | null
  /** 物料仓库存合计 */
  stockQty: number
  /** 送物料仓的全部在途（已审核、没入齐） */
  transitQty: number
  /** 别的算料单占着的（库存 + 在途） */
  heldQty: number
  safetyStock: number
  /** 差多少 = 最小库存 −（库存 + 在途 − 已被占用） */
  gapQty: number
  purchaseUnit: string
  conversionFactor: number
  /** 建议补货量，采购单位：差多少换成采购单位，按最小包装和起订量取整 */
  suggestQty: number
  /** 默认供应商的默认单价（元 / 采购单位）；换了供应商用 purchaseApi.defaults 重取 */
  price?: number | null
  /** 已经开了、还没审核的备货采购单里有这个 SKU 的（防重复生成；有的话这一行默认不勾） */
  openDrafts: { id: string; no: string; status: PurchaseStatus }[]
}

/** 勾选的一行：生成备货采购单 */
export interface ReorderPickItem {
  materialSkuId: string
  supplierId: string
  /** 采购单位，> 0 */
  qty: number
}

/** 库存页的"在途"列、"低于最小库存"标黄：库存那边算不了，按 SKU 来问采购（GET /api/material-supply） */
export interface SkuSupply {
  materialSkuId: string
  /** 按交货仓库分的在途（已审核、没入齐的采购行，库存单位）；没有在途是空数组 */
  transits: { warehouseId: string; qty: number }[]
  /** 别的算料单占着的 */
  heldQty: number
  safetyStock: number | null
  /** 物料仓库存 + 送物料仓的在途 − 已被占用 < 最小库存（没设最小库存是 false）：库存页这一行（物料仓的）标黄 */
  belowSafety: boolean
}
