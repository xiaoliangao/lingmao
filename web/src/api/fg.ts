import { download, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type {
  FgDocCreated, FgDocCreateForm, FgDocDetail, FgDocForm, FgDocQuery, FgDocRow, FgFromReceiptReq, FgPendingReceiptQuery,
  FgPendingReceiptRow, FgReceiptDraft, FgReceiptStocked, FgShipOrderOption, FgShipPrefill, FgShippedRow, FgStockByStyleRow,
  FgStockLogQuery, FgStockLogRow, FgStockQty, FgStockQuery, FgStockRow, FgStyleOption,
} from '@/types/fg'
import { docApi } from './biz'
import { currentCompanyScope } from '@/utils/companyScope'
import { sessionEpoch } from '@/utils/token'
import type { PageParams } from './system'

/**
 * 成品（docs/api/07-成品.md）：成品入库单、出库单（一组接口按 kind 分）、成品库存、库存日志。
 * 审核、反审核、作废、删除、操作记录、附件这些单据公共的在 docApi 里（README §4）。
 * 会被批量条逐条调的多收一个 options，批量时传 { silent: true }
 */

const docBase = '/api/fg-docs'

export const fgDocApi = {
  /** act(id, 'audit' | 'unaudit' | 'void', { version })、remove（删没过过账的草稿）、logs、attachments、references */
  ...docApi(docBase),
  /** 要 fg:doc:list；kind 必填 */
  page: (params: PageParams & FgDocQuery) => http.get<PageResult<FgDocRow>>(docBase, params),
  get: (id: string) => http.get<FgDocDetail>(`${docBase}/${id}`),
  /** 其他入库、发货出库、其他出库，存草稿 */
  create: (data: FgDocCreateForm) => http.post<FgDocCreated>(docBase, data),
  /** 改草稿，整单覆盖；返回新的 version */
  update: (id: string, data: FgDocForm) => http.put<number>(`${docBase}/${id}`, data),
  export: (params: FgDocQuery) => download(`${docBase}/export`, params, params.kind === 'IN' ? '成品入库单.xlsx' : '成品出库单.xlsx'),
  /** 画矩阵要的款式（DataPick 选完款再取） */
  styleOptions: (styleIds: string[]) => http.get<FgStyleOption[]>(`${docBase}/style-options`, { styleIds }),

  /** 待入库的回货单 */
  pendingReceipts: (params: PageParams & FgPendingReceiptQuery) =>
    http.get<PageResult<FgPendingReceiptRow>>(`${docBase}/pending-receipts`, params),
  /** 从回货单带出（不存库）。全部入完、回货单作废了、一个成品仓都没有回 1000 */
  prefillFromReceipt: (receiptId: string, options?: RequestOptions) =>
    http.get<FgReceiptDraft>(`${docBase}/from-receipt/${receiptId}`, undefined, options),
  /** 由回货单生成（存草稿，或 post = true 时马上过账）。批量入库逐条调，items 发 null */
  fromReceipt: async (receiptId: string, data: FgFromReceiptReq, options?: RequestOptions) => {
    const frozen = snapshotRequestBody(data)
    const captured = { ...options, authEpoch: options?.authEpoch ?? sessionEpoch(), companyId: options?.companyId ?? currentCompanyScope() }
    if (!captured.companyId) captured.companyId = (await fgDocApi.prefillFromReceipt(receiptId, captured)).companyId
    return http.post<FgDocCreated>(`${docBase}/from-receipt/${receiptId}`, frozen, captured)
  },

  /** 能发货的需求订单（下拉，最多 50 条）；给了仓带上这个仓里的库存件数 */
  shipOrders: (params: { keyword?: string; warehouseId?: string | null }) =>
    http.get<FgShipOrderOption[]>(`${docBase}/ship-orders`, params),
  /** 发货带出；改草稿时 docId 给这张单（已发里不算它） */
  shipPrefill: (params: { orderId: string; warehouseId?: string | null; docId?: string | null }) =>
    http.get<FgShipPrefill>(`${docBase}/ship-prefill`, params),

  /** 其他入库的草稿导入明细（ImportDialog） */
  itemsTemplateUrl: `${docBase}/items-template`,
  importUrl: (id: string) => `${docBase}/${id}/items/import`,

  /** 需求订单详情的"发 n"（要 fg:doc:list） */
  shipped: (orderId: string) => http.get<FgShippedRow[]>(`${docBase}/shipped`, { orderId }),
  /** 生产单回货页签的"已入库"（要 fg:doc:list） */
  byReceipts: (receiptIds: string[]) => http.get<FgReceiptStocked[]>(`${docBase}/by-receipts`, { receiptIds }),
}

export const fgStockApi = {
  page: (params: PageParams & FgStockQuery) => http.get<PageResult<FgStockRow>>('/api/fg-stocks', params),
  byStyle: (params: PageParams & FgStockQuery) => http.get<PageResult<FgStockByStyleRow>>('/api/fg-stocks/by-style', params),
  /** 一个仓几个 SKU 的现库存（表单"库存 n"）。要 fg:stock:list 或 fg:doc:add */
  qty: (warehouseId: string, skuIds: string[], options?: RequestOptions) =>
    http.get<FgStockQty[]>('/api/fg-stocks/qty', { warehouseId, skuIds }, options),
  export: (params: FgStockQuery) => download('/api/fg-stocks/export', params, '成品库存.xlsx'),
  exportByStyle: (params: FgStockQuery) => download('/api/fg-stocks/by-style/export', params, '成品库存按款.xlsx'),
}

export const fgStockLogApi = {
  /** 新的在前；只能翻到前 1 万行 */
  page: (params: PageParams & FgStockLogQuery) => http.get<PageResult<FgStockLogRow>>('/api/fg-stock-logs', params),
  export: (params: FgStockLogQuery) => download('/api/fg-stock-logs/export', params, '成品库存日志.xlsx'),
}
