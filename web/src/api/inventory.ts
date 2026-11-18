import { download, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type { DocActionReq } from '@/types/biz'
import type {
  CountCreated, CountCreateForm, CountDetail, CountForm, CountOpenWarehouse, CountQuery, CountRow, InvDocCreated, InvDocCreateForm,
  InvDocDetail, InvDocDraft, InvDocForm, InvDocFromSourceReq, InvDocQuery, InvDocRow, StockLogQuery, StockLogRow, StockQty, StockQuery,
  StockRow, StockSummaryRow,
} from '@/types/inventory'
import { docApi, documentOptions } from './biz'
import type { SeekResult } from '@/composables/useSeekTable'
import type { PageParams } from './system'

/**
 * 库存（docs/api/04-库存.md）：物料入库单、出库单（一组接口按 kind 分）、物料盘点单、库存、库存日志。
 * 审核、反审核、作废、删除、操作记录、附件、下游单据这些单据公共的接口在 docApi 里（README §4）。
 * 没过过账的草稿删（remove），过过账又反审核回来的作废（act(id, 'void')），看详情的 actions 给哪个。
 * 会被批量条逐条调的多收一个 options，批量时传 { silent: true }
 */

// ---------- 物料入库单、物料出库单 ----------

const docBase = '/api/inventory-docs'

export const invDocApi = {
  /** act(id, 'audit' | 'unaudit' | 'void', { version })、remove（删没过过账的草稿）、logs、attachments、references、notices */
  ...docApi(docBase),
  /** 要 inventory:doc:list；kind 必填 */
  page: (params: PageParams & InvDocQuery) => http.get<PageResult<InvDocRow>>(docBase, params),
  get: (id: string) => http.get<InvDocDetail>(`${docBase}/${id}`),
  /** 手工新建（退料入库不挂源单、其他入库、调拨、其他出库），存草稿 */
  create: (data: InvDocCreateForm) => http.post<InvDocCreated>(docBase, data),
  /** 改草稿，整体覆盖，原来的行带 id。源单生成的草稿只能删行、改数量缸号卷数备注，不能加源单以外的行；过过账的行不能删、不能换物料 */
  update: (id: string, data: InvDocForm) => http.put<number>(`${docBase}/${id}`, data),
  export: (params: InvDocQuery) =>
    download(`${docBase}/export`, params, params.kind === 'IN' ? '物料入库单.xlsx' : '物料出库单.xlsx'),

  /**
   * 采购入库、采购退货：带出单头默认值和明细（不存库），对话框照它画。itemIds 不传 = 这张采购单上还能入（能退）的全部行
   */
  prefillFromPurchase: (purchaseOrderId: string, params: { docType: 'PURCHASE_IN' | 'PURCHASE_RETURN'; itemIds?: string[] }) =>
    http.get<InvDocDraft>(`${docBase}/from-purchase/${purchaseOrderId}`, params),
  /** 由采购单生成（存草稿，或 post = true 时马上过账）。"待到料"勾几行点"入库"时按采购单分，每张调一次 */
  fromPurchase: async (purchaseOrderId: string, data: InvDocFromSourceReq, options?: RequestOptions) =>
    http.post<InvDocCreated>(`${docBase}/from-purchase/${purchaseOrderId}`, snapshotRequestBody(data), await documentOptions('/api/purchase-orders', purchaseOrderId, options)),
  /** 领料出库、退料入库：带出明细（生产单那边算的应领、已领）。领料的 fromWarehouseId 不传 = 第一个启用的物料仓 */
  prefillFromProduction: (productionOrderId: string, params: { docType: 'ISSUE_OUT' | 'RETURN_IN'; fromWarehouseId?: string }) =>
    http.get<InvDocDraft>(`${docBase}/from-production/${productionOrderId}`, params),
  fromProduction: async (productionOrderId: string, data: InvDocFromSourceReq, options?: RequestOptions) =>
    http.post<InvDocCreated>(`${docBase}/from-production/${productionOrderId}`, snapshotRequestBody(data), await documentOptions('/api/production-orders', productionOrderId, options)),
}

// ---------- 物料盘点单 ----------

const countBase = '/api/inventory-counts'

export const countApi = {
  /** act(id, 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit' | 'void', body)、remove（删没过过账的草稿）、logs、attachments */
  ...docApi(countBase),
  page: (params: PageParams & CountQuery) => http.get<PageResult<CountRow>>(countBase, params),
  get: (id: string) => http.get<CountDetail>(`${countBase}/${id}`),
  /** 新建：按范围带出明细、拍下账面数。这个仓库已经有没审核的盘点单回 1000，消息里有那张的单号 */
  create: (data: CountCreateForm) => http.post<CountCreated>(countBase, data),
  /** 存草稿（实盘数、备注、删行），整体覆盖；过过账的行不能删 */
  update: (id: string, data: CountForm) => http.put<number>(`${countBase}/${id}`, data),
  /** 从物料库加物料：账面数按现在的库存拍（期初录入是 0）；已经在单上的跳过。返回加完的详情 */
  addItems: (id: string, data: { version: number; skuIds: string[] }) => http.post<CountDetail>(`${countBase}/${id}/items`, data),
  /** 重新取账面数（草稿）：取账面以后有过进出的行重拍账面数、清空实盘数，要重数；没动过的行不变。范围里新出现的 SKU 补进来，一行不删。返回新的详情 */
  refreshBook: (id: string, body: DocActionReq) => http.post<CountDetail>(`${countBase}/${id}/refresh-book`, body),
  /** 有没审核的盘点单的仓库，新建对话框里灰掉 */
  openWarehouses: () => http.get<CountOpenWarehouse[]>(`${countBase}/open-warehouses`),
  /** 导出盘点表（Excel：SKU 码、物料编码、名称、颜色、规格、单位、账面数、实盘数、备注；实盘数、备注填单上现有的，盘了一半导出再导回不丢） */
  sheet: (id: string, no: string) => download(`${countBase}/${id}/sheet`, undefined, `盘点表-${no}.xlsx`),
  /** 导回实盘数用 ImportDialog：盘点表就是模板 */
  sheetUrl: (id: string) => `${countBase}/${id}/sheet`,
  importUrl: (id: string) => `${countBase}/${id}/import`,
  export: (params: CountQuery) => download(`${countBase}/export`, params, '物料盘点单.xlsx'),
}

// ---------- 库存、库存日志 ----------

export const stockApi = {
  /** 库存明细：仓库 × SKU（要 inventory:stock:list）。在途、低于最小库存另调 supplyApi.bySkus */
  page: (params: PageParams & StockQuery) => http.get<PageResult<StockRow>>('/api/stocks', params),
  /** 按物料汇总：所有仓合计 */
  summary: (params: PageParams & StockQuery) => http.get<PageResult<StockSummaryRow>>('/api/stocks/summary', params),
  /** 一个仓库几个 SKU 的现库存（表单"出仓库存"一列）。要 inventory:stock:list 或 inventory:doc:add */
  qty: (warehouseId: string, skuIds: string[], options?: RequestOptions) =>
    http.get<StockQty[]>('/api/stocks/qty', { warehouseId, skuIds }, options),
  export: (params: StockQuery) => download('/api/stocks/export', params, '库存.xlsx'),
  exportSummary: (params: StockQuery) => download('/api/stocks/summary/export', params, '库存汇总.xlsx'),
}

export const stockLogApi = {
  seek: (params: StockLogQuery & { cursor?: string; size: number }) => http.get<SeekResult<StockLogRow>>('/api/stock-logs/seek', params),
  /** 新的在前（按 id 倒序） */
  page: (params: PageParams & StockLogQuery) => http.get<PageResult<StockLogRow>>('/api/stock-logs', params),
  export: (params: StockLogQuery) => download('/api/stock-logs/export', params, '库存日志.xlsx'),
}
