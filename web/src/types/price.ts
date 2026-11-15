/** 价格档案（RFC 006）。数量、单价是后端的 BigDecimal，到前端是 JSON 数字 */

/** 物料价：供应商 × 物料（SKU 选填，不填 = 所有颜色同价）；加工价：工厂 × 款式 × 加工方式 */
export type PriceType = 'MATERIAL' | 'PROCESS'

export const PRICE_TYPE_LABEL: Record<PriceType, string> = { MATERIAL: '物料价', PROCESS: '加工价' }

/** 列表的一行。名称是后端从各域批量取来填的；prevPrice、nextPrice、nextDate 只在"只看现价"时有 */
export interface PriceRow {
  id: number
  type: PriceType
  supplierId: number
  supplierCode: string | null
  /** 简称 */
  supplierName: string | null
  materialId: number | null
  materialCode: string | null
  materialName: string | null
  materialSkuId: number | null
  skuCode: string | null
  skuColorName: string | null
  skuSpec: string | null
  styleId: number | null
  /** 第 4c 步接上款式以前是 null */
  styleCode: string | null
  styleName: string | null
  /** 字典 process_mode 的值 */
  processMode: string | null
  price: number
  /** 字典 unit 的值：物料价是录的时候物料的采购单位，加工价是件 */
  unit: string
  /** 物料后来换了采购单位：这条价不再参与取现价 */
  unitChanged: boolean
  taxPct: number
  taxIncluded: boolean
  freightIncluded: boolean
  /** 2026-11-15 */
  effectiveDate: string
  reason: string | null
  remark: string | null
  /** 单据记进来的（第 3、4 批起）：来源单据的类型、id、单号；手工录的是 null */
  sourceType: string | null
  sourceId: number | null
  sourceNo: string | null
  voided: boolean
  voidReason: string | null
  voidBy: number | null
  voidByName: string | null
  voidTime: string | null
  createBy: number | null
  createByName: string | null
  createTime: string
  /** 现价那一行：上一条（生效日更早的）的价 */
  prevPrice: number | null
  /** 以后生效的最早一条 */
  nextPrice: number | null
  nextDate: string | null
}

export interface PriceQuery {
  type?: PriceType
  supplierId?: number
  materialId?: number
  materialSkuId?: number
  styleId?: number
  processMode?: string
  /** true：每组一行的现价（还没生效的组显示最早那条） */
  current?: boolean
  /** 全部历史时连作废的一起列 */
  includeVoided?: boolean
}

/** 一条价的要紧几项（取现价用） */
export interface PriceRef {
  id: number
  price: number
  unit: string
  taxPct: number
  taxIncluded: boolean
  freightIncluded: boolean
  effectiveDate: string
  /** 不是 null = 这个 SKU 单独定的价 */
  materialSkuId: number | null
}

/** 录价对话框的提示：那一天的现价（物料价先找 SKU 的、再找物料的）、单位、供应商的默认税率 */
export interface CurrentPrice {
  current: PriceRef | null
  unit: string | null
  supplierTaxPct: number | null
}

export interface CurrentPriceQuery {
  type: PriceType
  supplierId: number
  materialId?: number
  materialSkuId?: number
  styleId?: number
  processMode?: string
  date?: string
}

/** 录一条。税率不填 = 供应商的；含税、含运不填 = 含；生效日期不填 = 今天 */
export interface PriceSaveForm {
  type: PriceType
  supplierId: number
  materialId: number | null
  materialSkuId: number | null
  styleId: number | null
  processMode: string | null
  price: number
  taxPct: number | null
  taxIncluded: boolean
  freightIncluded: boolean
  effectiveDate: string
  reason: string | null
  remark: string | null
}
