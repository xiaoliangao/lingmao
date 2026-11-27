import type { MaterialSkuBrief } from './inventory'

/** 报表（甲方 11-27 答 Q66、Q67、Q53，RFC 019 §3）。都是后端现算的 */

/** 供应商绩效的筛选：按约定交期落在这段时间里的单算；次品率按这段时间里的回货 */
export interface PerfQuery {
  from: string
  to: string
  /** 供应商编码、简称、名称 */
  keyword?: string
}

/** 物料供应商的采购到货及时率：按采购行。合计行 supplierId 是 null */
export interface PurchasePerfRow {
  supplierId: string | null
  supplierCode: string | null
  supplierShortName: string | null
  /** 算进来的行（准时 + 逾期） */
  lineCount: number
  onTimeCount: number
  lateCount: number
  /** 交期还没到、也没到齐的行（不算） */
  pendingCount: number
  /** 百分数，一位小数；没有算进来的是 null */
  onTimeRate: number | null
  /** 逾期的平均晚几天 */
  avgLateDays: number | null
}

/** 加工厂的交期达成率、次品率。合计行 factoryId 是 null */
export interface FactoryPerfRow {
  factoryId: string | null
  factoryCode: string | null
  factoryShortName: string | null
  orderCount: number
  onTimeCount: number
  lateCount: number
  pendingCount: number
  onTimeRate: number | null
  dueQty: number
  /** 交期当天及之前回的良品 */
  qtyByDue: number
  qtyRate: number | null
  receiptQty: number
  defectQty: number
  defectRate: number | null
}

export interface PerfResult<T> {
  rows: T[]
  total: T
}

/** 各品类线在做的件数：一行一个品类线 × 一级品类 */
export interface LineWipRow {
  productLine: string
  categoryId: string | null
  categoryName: string | null
  orderCount: number
  /** 还没回的件数 */
  wipQty: number
  /** 其中已经过了交期的 */
  lateQty: number
  /** 这个月新排的件数 */
  monthNewQty: number
  /** 这个月回的良品 */
  monthGoodQty: number
}

/** 呆滞料：一个物料仓 × 一个物料 SKU */
export interface StaleMaterialRow extends MaterialSkuBrief {
  warehouseId: string
  warehouseName: string
  qty: number
  /** 最后一次出入库（盘点不算）；没有记录是 null */
  lastDate: string | null
  idleDays: number | null
  unitPrice: number | null
  amount: number | null
}

export interface StaleMaterialQuery {
  /** 多少天没动算呆滞；不传按设置的默认 */
  days?: number
  warehouseId?: string
  keyword?: string
}

/** 订单毛利：销售额 − 按资料包核价估的成本 */
export interface OrderMarginRow {
  id: string
  no: string
  brand: string | null
  productLine: string | null
  orderDate: string
  status: string
  totalQty: number
  currency: string | null
  salesAmount: number
  costAmount: number
  /** 算不了成本的件数（资料包没锁、核价算不出来） */
  costMissingQty: number
  /** 外币单是 null：没有汇率，不算 */
  margin: number | null
  marginRate: number | null
}

export interface OrderMarginQuery {
  from: string
  to: string
  /** 订单号、客户订单号、品牌 */
  keyword?: string
  productLine?: string
}

/** 这一页，和筛出来的全部单的合计（合计只算人民币的单） */
export interface OrderMarginPage {
  list: OrderMarginRow[]
  total: number
  salesAmount: number
  costAmount: number
  margin: number
  marginRate: number | null
  costMissingOrders: number
  /** 外币单几张：不算毛利、不进合计 */
  foreignOrders: number
}
