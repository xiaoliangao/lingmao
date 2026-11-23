// inventory 域（物料入库单、物料出库单、物料盘点单、库存、库存日志）的返回和请求，和后端 module/inventory/dto 一一对应
// （docs/api/04-库存.md）。数量没写单位的都是库存单位，采购单位的字段名里带 purchase；单行 NUMERIC 保持既有契约，单据聚合 totalQty/totalAmount 为十进制字符串，显示走 utils/format.ts
import type { Attachment, DocAction, DocHeader, DocRef, DocSaved, StatusTone } from '@/types/biz'
import type { WarehouseType } from '@/types/basic'
import type { DecimalString } from '@/types/decimal'

/**
 * 一个物料 SKU 的显示字段。入库出库明细、盘点明细、库存、库存日志，以及算料行、采购明细都带这一组（采购那边 extends 它）。
 * 名称、颜色、图是查询时现取的，物料改了名旧单上跟着变；单价、换算系数这些"当时按什么算的"另存在单据行上
 */
export interface MaterialSkuBrief {
  materialSkuId: string
  /** 物料编码-颜色码[-规格码] */
  skuCode: string
  materialId: string
  materialCode: string
  materialName: string
  /** 字典 material_type */
  materialType: string
  /** 无色物料是 null */
  colorName: string | null
  colorHex: string | null
  spec: string | null
  /** 库存单位，字典 unit 的 value */
  stockUnit: string
  /** SKU 图，没有用物料主图，都没有是 null */
  image: Attachment | null
}

// ---------------------------------------------------------------- 物料入库单、物料出库单（一张表 inv_doc，按 kind 分）

/** IN 物料入库单（RK）/ OUT 物料出库单（CK）。一组接口，列表按 kind 分；页面、菜单是两个（/stock-in、/stock-out） */
export type InvDocKind = 'IN' | 'OUT'

export const INV_DOC_KIND_LABEL: Record<InvDocKind, string> = { IN: '物料入库单', OUT: '物料出库单' }

/**
 * 类型（PRD §6.6.5 的两张表）。TRADE 开时入库单多一种 CUSTOMER_IN 客供入库，关着时后端不收、不返回，这里不列
 */
export type InvDocType = 'PURCHASE_IN' | 'RETURN_IN' | 'OTHER_IN' | 'ISSUE_OUT' | 'PURCHASE_RETURN' | 'TRANSFER' | 'OTHER_OUT'

/** 一种类型在表单上长什么样：出仓、入仓能选哪种仓库（null = 没有这一格），往来单位，源单，能不能在页头手工新建，原因怎么填 */
export interface InvDocTypeMeta {
  label: string
  kind: InvDocKind
  /** 出仓能选的仓库类型；null = 这种单没有出仓 */
  from: WarehouseType[] | null
  /** 入仓能选的仓库类型；null = 没有入仓 */
  to: WarehouseType[] | null
  /** 往来：供应商（采购的两种）、加工厂（领料、退料，由源单或工厂仓带出）；null = 没有 */
  partner: 'SUPPLIER' | 'FACTORY' | null
  /** 源单：采购单、生产单（外发加工单 / 成品采购单）；null = 没有源单 */
  source: 'PURCHASE_ORDER' | 'PRODUCTION_ORDER' | null
  /** 源单必填：这种单只能从源单生成（from-purchase、from-production），页头不能手建 */
  sourceRequired: boolean
  /** 原因用哪个字典（下拉，必填）；null = 原因是选填的一行字 */
  reasonDict: 'stock_in_reason' | 'stock_out_reason' | null
}

/**
 * 页头"新建"按钮按 sourceRequired 取：false 的能手建。入库单：退料入库、其他入库；出库单：调拨、其他出库。
 * 采购入库从"待到料"或采购单详情来，采购退货从采购单详情来，领料出库从生产单或出库单页的"领料出库（选生产单）"来
 */
export const INV_DOC_TYPE: Record<InvDocType, InvDocTypeMeta> = {
  PURCHASE_IN: { label: '采购入库', kind: 'IN', from: null, to: ['MATERIAL', 'FACTORY'], partner: 'SUPPLIER', source: 'PURCHASE_ORDER', sourceRequired: true, reasonDict: null },
  RETURN_IN: { label: '退料入库', kind: 'IN', from: ['FACTORY'], to: ['MATERIAL'], partner: 'FACTORY', source: 'PRODUCTION_ORDER', sourceRequired: false, reasonDict: null },
  OTHER_IN: { label: '其他入库', kind: 'IN', from: null, to: ['MATERIAL'], partner: null, source: null, sourceRequired: false, reasonDict: 'stock_in_reason' },
  ISSUE_OUT: { label: '领料出库', kind: 'OUT', from: ['MATERIAL'], to: ['FACTORY'], partner: 'FACTORY', source: 'PRODUCTION_ORDER', sourceRequired: true, reasonDict: null },
  PURCHASE_RETURN: { label: '采购退货', kind: 'OUT', from: ['MATERIAL', 'FACTORY'], to: null, partner: 'SUPPLIER', source: 'PURCHASE_ORDER', sourceRequired: true, reasonDict: null },
  TRANSFER: { label: '调拨', kind: 'OUT', from: ['MATERIAL', 'FACTORY'], to: ['MATERIAL', 'FACTORY'], partner: null, source: null, sourceRequired: false, reasonDict: null },
  OTHER_OUT: { label: '其他出库', kind: 'OUT', from: ['MATERIAL'], to: null, partner: null, source: null, sourceRequired: false, reasonDict: 'stock_out_reason' },
}

/**
 * 没有待审核，审核即过账（PRD §6.6.5，数据库设计 §13.1）。没过过账的草稿直接删；
 * 过过账又反审核回草稿的不能删、只能作废（用户 11-17 定，库存日志指着它）
 */
export type InvDocStatus = 'DRAFT' | 'APPROVED' | 'VOIDED'

export const INV_DOC_STATUS: Record<InvDocStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  APPROVED: { label: '已审核', tone: 'active' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * 详情、列表行上能点的：audit 审核过账、unaudit 反审核（库存改回去），edit 改草稿；
 * remove 删没过过账的草稿、void 作废过过账的草稿，两个只会有一个
 */
export type InvDocAction = Extract<DocAction, 'audit' | 'unaudit' | 'void'> | 'edit' | 'remove'

/** 操作记录里的动作叫法（DocLogList 的 actions），通用的（提交、审核……）不用列 */
export const INV_DOC_LOG_ACTION: Record<string, string> = {
  audit: '审核过账',
  unaudit: '反审核（库存改回去）',
}

/** 列表的一行。物料只带第一行的，"等 n 种"用 itemCount */
export interface InvDocRow {
  id: string
  no: string
  kind: InvDocKind
  docType: InvDocType
  status: InvDocStatus
  /** 批量审核要带回去 */
  version: number
  fromWarehouseId: string | null
  fromWarehouseName: string | null
  toWarehouseId: string | null
  toWarehouseName: string | null
  /** 往来单位（供应商或加工厂，简称）；其他入库、调拨这类没有是 null */
  partnerId: string | null
  partnerName: string | null
  /** 源单号（采购单、生产单）；没有是 null */
  sourceNo: string | null
  /** 第一行物料，明细是空的（不会有）才是 null */
  firstItem: MaterialSkuBrief | null
  itemCount: number
  /** 各行库存单位数量直接相加，只显示 */
  totalQty: DecimalString
  totalAmount?: DecimalString | null
  /** 实际发生日期 yyyy-MM-dd */
  docDate: string
  /** 其他入库、其他出库是字典值（stock_in_reason / stock_out_reason），别的是手填的一行字或 null */
  reason: string | null
  createByName: string | null
  createTime: string
  /** 行上的按钮，批量条用（audit、remove……） */
  actions: InvDocAction[]
}

export interface InvDocQuery {
  /** 同时包含全部所选公共标签；最多 30 个稳定 ID。 */
  tagIds?: string[]
  /** 必填：IN 入库单列表 / OUT 出库单列表 */
  kind: InvDocKind
  docType?: InvDocType
  status?: InvDocStatus[]
  /** 出仓或入仓是它 */
  warehouseId?: string | null
  partnerId?: string | null
  docDateFrom?: string
  docDateTo?: string
  /** 单号、源单号、送货单号、物流单号、物料编码 / 名称 / SKU 码、缸号；一次查几个（README §3.2）时单号、源单号精确查 */
  keyword?: string
  mine?: boolean
  /** 导出选中的 */
  ids?: string[]
}

/** 明细的一行 */
export interface InvDocItem extends MaterialSkuBrief {
  /** 改草稿时原样带回（InvDocItemForm.id） */
  id: string
  seq: number
  /** 这一行过过账（有库存日志）：改草稿时不能删、不能换物料 */
  posted: boolean
  /** 库存单位，> 0；方向由单据类型定 */
  qty: number
  /** 采购入库、采购退货才有：采购单位、采购单位数量、换算系数（qty = purchaseQty × conversionFactor），别的是 null */
  purchaseUnit: string | null
  purchaseQty: number | null
  conversionFactor: number | null
  /** 元 / 库存单位；采购入库、退货 = 采购单价 ÷ 换算系数（后端算，前端不传）。手建的单选填，没填是 null */
  price?: number | null
  amount?: number | null
  /** 缸号：只记录，库存不按它分（Q07） */
  dyeLot: string | null
  rolls: number | null
  /** 源单明细：采购入库、退货是采购明细 id；领料、退料、手建的是 null */
  sourceItemId: string | null
  /** 超领原因：领的比建议数多时必填 */
  overReason: string | null
  remark: string | null
  /** 出仓现在的库存：草稿、有出仓时才带（表单"库存"一列），别的是 null */
  fromStockQty: number | null
}

export interface InvDocDetail extends DocHeader<InvDocStatus, InvDocAction> {
  kind: InvDocKind
  docType: InvDocType
  /** 源单（采购单、外发加工单 / 成品采购单），点单号用 docRoute(source)；没有是 null */
  source: DocRef | null
  fromWarehouseId: string | null
  fromWarehouseName: string | null
  fromWarehouseType: WarehouseType | null
  toWarehouseId: string | null
  toWarehouseName: string | null
  toWarehouseType: WarehouseType | null
  partnerId: string | null
  partnerName: string | null
  docDate: string
  reason: string | null
  supplierDeliveryNo: string | null
  logisticsNo: string | null
  totalQty: DecimalString
  totalAmount?: DecimalString | null
  /** 过过账（有库存日志）：草稿时 actions 给 void 不给 remove */
  posted: boolean
  items: InvDocItem[]
}

/** 手建的单（退料入库不挂源单、其他入库、调拨、其他出库）新建、改草稿共用的明细。保存是整体覆盖：没填的发 null */
export interface InvDocItemForm {
  /** 改草稿时，原来的行带回 InvDocItem.id，新加的行是 null；请求里没有的行删掉（过过账的行删不了，1000）。新建都是 null */
  id: string | null
  /** 源单生成的草稿：原样带回（采购明细 id），后端按它重新取单价、换算系数；手建的是 null */
  sourceItemId: string | null
  materialSkuId: string
  /** 库存单位。采购入库、采购退货填 purchaseQty，这一格发 null，由后端换算 */
  qty: number | null
  /** 采购单位数量：只有采购入库、采购退货填 */
  purchaseQty: number | null
  /** 元 / 库存单位，手建的单选填；源单生成的发什么都按源单算 */
  price?: number | null
  dyeLot: string | null
  rolls: number | null
  overReason: string | null
  remark: string | null
}

/** 改草稿（PUT /api/inventory-docs/{id}）。类型、kind、源单建好就不能改，不在这里 */
export interface InvDocForm {
  version: number
  companyId: string
  /** yyyy-MM-dd，不能晚于今天 */
  docDate: string
  fromWarehouseId: string | null
  toWarehouseId: string | null
  partnerId: string | null
  reason: string | null
  supplierDeliveryNo: string | null
  logisticsNo: string | null
  remark: string | null
  items: InvDocItemForm[]
}

/** 手工新建（POST /api/inventory-docs）：只收 sourceRequired = false 的类型，不挂源单。退料入库要挂生产单走 from-production */
export interface InvDocCreateForm extends Omit<InvDocForm, 'version'> {
  docType: InvDocType
}

/** 新建、由源单生成的返回 */
export interface InvDocCreated extends DocSaved {
  /** post = true 时已经审核过账了，是 APPROVED */
  status: InvDocStatus
}

// ---------- 由源单生成：采购单（采购入库、采购退货）、生产单（领料出库、退料入库） ----------

/**
 * 带出来的一行（GET …/from-purchase/{id}、…/from-production/{id}，不存库）。对话框照它画表，"本次"默认填 suggestQty。
 * 单位：采购入库、采购退货按采购单位（purchaseUnit）；领料、退料按库存单位（stockUnit）
 */
export interface InvDocDraftItem extends MaterialSkuBrief {
  /** 采购明细 id；领料、退料是 null（按 materialSkuId 对行） */
  sourceItemId: string | null
  purchaseUnit: string | null
  conversionFactor: number | null
  /** 采购单价（元 / 采购单位），采购的两种才有 */
  price?: number | null
  /** "本次"的默认值：采购入库 = 未入库；领料 = 还差（应领 − 已领）；采购退货、退料 = 0 */
  suggestQty: number
  /**
   * 本次最多：采购入库 = 采购数 ×（1 + 溢收 5%）−（已入库 − 已退货）；采购退货 = 已入库 − 已退货；退料 = 已领 − 已退；
   * 领料不限（超了要写原因）是 null。超了审核时报 1004
   */
  maxQty: number | null
  /** 采购的两种：采购数、已入库、已退货（采购单位）；别的是 null */
  orderedQty: number | null
  receivedQty: number | null
  returnedQty: number | null
  /** 领料、退料：应领（加工数 × BOM 用量 ×（1 + 损耗））、已领（领料 − 退料），库存单位；别的是 null */
  needQty: number | null
  issuedQty: number | null
  /** 缸号：采购入库的行留空让人填；退货、退料带不出来，也是 null */
  dyeLot: string | null
}

/** 带出来的单头默认值 + 明细 */
export interface InvDocDraft {
  /** 来源投影直接给出主体；仓管无需读取普通采购/生产详情。 */
  companyId: string
  docType: InvDocType
  /** 源单 */
  source: DocRef
  partnerId: string | null
  partnerName: string | null
  /** 采购入库：采购单的交货地点；采购退货：采购单的交货地点（料可能入到了别的仓，能改）；领料：第一个启用的物料仓；退料：加工厂的工厂仓 */
  fromWarehouseId: string | null
  fromWarehouseName: string | null
  toWarehouseId: string | null
  toWarehouseName: string | null
  items: InvDocDraftItem[]
}

/** 对话框里填好的一行。单位同 InvDocDraftItem.suggestQty；qty 是 0 的行不建 */
export interface InvDocFromSourceItem {
  sourceItemId: string | null
  materialSkuId: string
  qty: number
  dyeLot: string | null
  rolls: number | null
  /** 领料超过还差的数时必填 */
  overReason: string | null
  remark: string | null
}

/**
 * 由源单生成（POST …/from-purchase/{id}、…/from-production/{id}）。单头没填的发 null，按 InvDocDraft 的默认值建。
 * items 和 itemIds 二选一：
 * - 对话框填好了数：发 items，itemIds 发 null；
 * - "待到料"勾几行直接点"入库"（不弹对话框）：items 发 null，itemIds 发勾的采购明细 id，每行按 suggestQty 建草稿；两个都是 null = 源单上还能入的全部行
 * post = true：建好马上审核过账（"入库并过账"），一个事务，审核不过（1001、1004）连单据也不建；要 inventory:doc:add 和 :audit
 */
export interface InvDocFromSourceReq {
  /** 同一动作重试保留 UUID 和原始请求体 */
  clientActionId: string
  docType: InvDocType
  docDate: string | null
  fromWarehouseId: string | null
  toWarehouseId: string | null
  supplierDeliveryNo: string | null
  logisticsNo: string | null
  /** 采购退货的退货原因、退料的原因：一行字，选填 */
  reason: string | null
  remark: string | null
  items: InvDocFromSourceItem[] | null
  itemIds: string[] | null
  post: boolean
}

// ---------------------------------------------------------------- 1001、1004 的清单（全局的 BizErrorDialog 直接按字段画表）

/**
 * 1001 库存不足的一行：入库单、出库单、盘点单审核，反审核时也一样（PRD §6.6.5、§6.6.8，Q08）。
 * 整张单都试一遍，不够的全列出来（架构 §5.2）。同一仓同一 SKU 在单上有几行的合成一行。
 * 这张表直接显示：字段按这个顺序出，名称、单位都是翻好的中文。例："220g 斜纹棉-卡其：库存 0.14，差 0.86"
 */
export interface StockShortRow {
  /** 单上第几行，几行合成一行时"3、5" */
  seqs: string
  warehouseName: string
  skuCode: string
  /** 名称-颜色 规格，如"220g 斜纹棉-卡其" */
  materialName: string
  /** 现在的库存 */
  stockQty: number
  /** 这次要扣的（盘点是盘亏的数） */
  needQty: number
  /** 差多少 = needQty − stockQty */
  shortQty: number
  /** 库存单位的中文，如"米" */
  unit: string
}

/**
 * 1004 超出上限的一行：采购入库超收（净入库超过采购数 ×（1 + 溢收 5%），Q09）、采购退货超过可退、退料超过已领。
 * 审核时按最新的数再算一遍（草稿生成以后源单又入了一批，也在这里拦住，架构 §5.2）。这张表直接显示，字段按这个顺序出
 */
export interface InvOverRow {
  /** 单上第几行 */
  seq: number
  skuCode: string
  materialName: string
  /** 采购单号 / 生产单号 */
  sourceNo: string
  /** 这次的数 */
  thisQty: number
  /** 最多还能收 / 退 */
  maxQty: number
  /** 单位的中文：采购的两种是采购单位，退料是库存单位 */
  unit: string
  /** 上限怎么来的，如"采购 871 米，溢收 5%，已入库 500、已退 0" */
  reason: string
}

// ---------------------------------------------------------------- 物料盘点单（inv_count）

/** 日常盘点 / 期初录入（账面数都是 0，范围只能手选，已经有库存的 SKU 不能进） */
export type CountPurpose = 'DAILY' | 'OPENING'

export const COUNT_PURPOSE_LABEL: Record<CountPurpose, string> = { DAILY: '日常盘点', OPENING: '期初录入' }

export type CountScope = 'ALL' | 'CATEGORY' | 'PICKED'

export const COUNT_SCOPE_LABEL: Record<CountScope, string> = { ALL: '全部', CATEGORY: '按物料分类', PICKED: '手选物料' }

/**
 * 草稿 → 待审核 → 已审核（审核即过账），反审核回草稿。单级审核，不走多级审批。
 * 删除、作废同入库单：没过过账的草稿直接删，过过账又反审核回来的只能作废（用户 11-17 定）。作废的不算"没审核的"
 */
export type CountStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'VOIDED'

export const COUNT_STATUS: Record<CountStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  PENDING: { label: '待审核', tone: 'pending' },
  APPROVED: { label: '已审核', tone: 'active' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * submit、withdraw、reject、audit、unaudit、void（作废过过账的草稿），加上 edit 改实盘数、加删行、导回实盘数，
 * remove 删没过过账的草稿，refreshBook 重新取账面数（都是草稿）
 */
export type CountAction =
  | Extract<DocAction, 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit' | 'void'>
  | 'edit'
  | 'remove'
  | 'refreshBook'

export const COUNT_LOG_ACTION: Record<string, string> = {
  audit: '审核过账',
  unaudit: '反审核（调整退回去）',
  'add-items': '加物料',
  'refresh-book': '重新取账面数',
  'export-sheet': '导出盘点表',
  import: '导回实盘数',
}

export interface CountRow {
  id: string
  no: string
  warehouseId: string
  warehouseName: string
  purpose: CountPurpose
  scope: CountScope
  /** 范围的说法："全部物料" / "分类：面料-梭织、辅料-拉链" / "手选 3 种" */
  scopeText: string
  countDate: string
  counterId: string
  counterName: string
  itemCount: number
  /** 已填实盘的行数 */
  filledCount: number
  /** 盘盈、盘亏的行数（已填、差异不为 0 的） */
  gainCount: number
  lossCount: number
  status: CountStatus
  version: number
  actions: CountAction[]
  createTime: string
}

export interface CountQuery {
  /** 同时包含全部所选公共标签；最多 30 个稳定 ID。 */
  tagIds?: string[]
  /** 单号、仓库、盘点人、物料编码 / 名称 / SKU 码 */
  keyword?: string
  status?: CountStatus[]
  warehouseId?: string | null
  purpose?: CountPurpose
  countDateFrom?: string
  countDateTo?: string
  ids?: string[]
}

export interface CountItem extends MaterialSkuBrief {
  /** 取账面时这个库存行的变动次数；只读，升级前的旧行是 null（提交时当作有过进出） */
  bookRevision: number | null
  id: string
  seq: number
  /** 账面数：建单时（或草稿里重新取账面数时）的库存快照；期初录入是 0 */
  bookQty: number
  /** 实盘数，还没填是 null；提交时不能有 null */
  countedQty: number | null
  /** 差异 = 实盘 − 账面，正的盘盈、负的盘亏；实盘没填是 null */
  diffQty: number | null
  /** 已审核才有：过账时实际调了多少（= diffQty，为 0 的行是 0）；没审核是 null */
  adjustedQty: number | null
  remark: string | null
  /** 这一行过过账（反审核回来的草稿）：不能删 */
  posted: boolean
}

export interface CountCategory {
  id: string
  /** 二级分类名；parentName 是一级 */
  name: string
  parentName: string | null
}

export interface CountDetail extends DocHeader<CountStatus, CountAction> {
  warehouseId: string
  warehouseName: string
  warehouseType: WarehouseType
  purpose: CountPurpose
  scope: CountScope
  scopeText: string
  /** scope = CATEGORY 时选的分类；别的是空数组 */
  categories: CountCategory[]
  countDate: string
  counterId: string
  counterName: string
  /** 取账面数的时间 2026-11-15 10:30:00 */
  bookTime: string
  itemCount: number
  filledCount: number
  gainCount: number
  lossCount: number
  /**
   * 建单（取账面数）以后，这个仓库、这些 SKU 有几笔进出（库存日志条数）。待审核时审核确认框提示
   * "建单以后有 n 笔进出，盘点只调差异，不影响这些进出"，只提示不拦（PRD §6.6.8 规则 2）；别的状态是 0
   */
  movesSinceBook: number
  /** 过过账（有库存日志）：草稿时 actions 给 void 不给 remove */
  posted: boolean
  items: CountItem[]
}

/** 新建（POST /api/inventory-counts）：建单时按范围带出明细、拍下账面数 */
export interface CountCreateForm {
  companyId: string
  warehouseId: string
  purpose: CountPurpose
  scope: CountScope
  /** scope = CATEGORY 时必选，物料分类（二级）id；别的发 [] */
  categoryIds: string[]
  /** scope = PICKED 时手选的 SKU；期初录入可以是空的（建空单再导入），日常盘点至少一个 */
  skuIds: string[]
  countDate: string
  counterId: string
  remark: string | null
}

export interface CountCreated extends DocSaved {
  itemCount: number
}

/** 改草稿（PUT /api/inventory-counts/{id}）的一行：按 SKU 对上已有的行，账面数不改；单上没有的 SKU 不在这里加，走 addItems */
export interface CountItemForm {
  materialSkuId: string
  countedQty: number | null
  remark: string | null
}

/** 保存是整体覆盖：items 里没有的行删掉 */
export interface CountForm {
  version: number
  countDate: string
  counterId: string
  remark: string | null
  items: CountItemForm[]
}

/** 有没审核的盘点单的仓库（草稿、待审核）：新建对话框里这些仓灰掉，后面写"PD… 还没审" */
export interface CountOpenWarehouse {
  warehouseId: string
  countId: string
  countNo: string
  status: CountStatus
}

// ---------------------------------------------------------------- 库存、库存日志

/** 库存明细的一行：仓库 × 物料 SKU（物权、货位一期不显示：都是自有、不分货位） */
export interface StockRow extends MaterialSkuBrief {
  /** inv_stock.id */
  id: string
  warehouseId: string
  warehouseCode: string
  warehouseName: string
  warehouseType: WarehouseType
  qty: number
  /** SKU 上设的最小库存，没设是 null。低于没低于由采购那边算（supplyApi.bySkus），库存这边算不了在途和占用 */
  safetyStock: number | null
  /** 最近变动时间 2026-11-15 10:30:00 */
  updateTime: string
}

export interface StockQuery {
  /** 页签：MATERIAL 物料仓 / FACTORY 工厂仓 / 不传 = 全部（成品仓不在这里） */
  warehouseType?: Extract<WarehouseType, 'MATERIAL' | 'FACTORY'>
  warehouseId?: string | null
  /** SKU 码、物料编码、名称、仓库名；一次查几个（README §3.2）时 SKU 码、物料编码精确查 */
  keyword?: string
  /** 默认不列数量是 0 的行；true 也列 */
  includeZero?: boolean
  ids?: string[]
}

/** 按物料汇总的一行：所有仓合计 */
export interface StockSummaryRow extends MaterialSkuBrief {
  /** 物料仓合计、工厂仓合计、合计 */
  materialQty: number
  factoryQty: number
  totalQty: number
  safetyStock: number | null
}

/** GET /api/stocks/qty：表单"出仓库存"那一列用，一个仓库几个 SKU 的现库存；没有库存记录的 SKU 返回 0 */
export interface StockQty {
  materialSkuId: string
  qty: number
}

/** 库存日志的变动类型：入库单、出库单的类型，加上盘点单的盘盈、盘亏、期初录入（PRD §6.6.6） */
export type StockChangeType = InvDocType | 'COUNT_GAIN' | 'COUNT_LOSS' | 'OPENING'

export const STOCK_CHANGE_TYPE_LABEL: Record<StockChangeType, string> = {
  PURCHASE_IN: '采购入库',
  RETURN_IN: '退料入库',
  OTHER_IN: '其他入库',
  ISSUE_OUT: '领料出库',
  PURCHASE_RETURN: '采购退货',
  TRANSFER: '调拨',
  OTHER_OUT: '其他出库',
  COUNT_GAIN: '盘盈',
  COUNT_LOSS: '盘亏',
  OPENING: '期初',
}

/** 库存日志的一行：只增不改，反审核是反方向再记一条；调拨一张单两条（出仓一条、入仓一条） */
export interface StockLogRow extends Omit<MaterialSkuBrief, 'stockUnit'> {
  stockUnit: string | null
  unitPrice?: number | null
  amount?: number | null
  currency: string | null
  valuationStatus: 'UNKNOWN' | 'RECORDED'
  reversalOfLogId: string | null
  id: string
  /** 单据的实际发生日期 yyyy-MM-dd（列表的"日期"） */
  bizDate: string
  /** 记账时间 2026-11-15 10:30:00 */
  createTime: string
  changeType: StockChangeType
  /** 点单号去哪：docRoute({ type: docType, kind: docKind, id: docId, parentId: null }) */
  docType: 'INV_DOC' | 'INV_COUNT'
  /** 入库单 IN / 出库单 OUT；盘点单是 null */
  docKind: InvDocKind | null
  docId: string
  docNo: string
  warehouseId: string
  warehouseName: string
  /** 入为正、出为负 */
  changeQty: number
  /** 变动后结存 */
  balanceQty: number
  dyeLot: string | null
  /** 操作人昵称，系统做的是 null */
  operatorName: string | null
}

export interface StockLogQuery {
  warehouseId?: string | null
  /** 点库存一行看它的流水：带上仓库 + SKU */
  materialSkuId?: string | null
  changeType?: StockChangeType[]
  bizDateFrom?: string
  bizDateTo?: string
  /** 单号、SKU 码、物料名、缸号（PRD §6.6.5：按缸号查流水） */
  keyword?: string
}
