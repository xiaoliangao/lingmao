import { ApiError, download, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult, FileVO } from '@/types/system'
import type { Attachment, DocAction, DocActionReq, DocLog, DocRef } from '@/types/biz'
import type { SeekResult } from '@/composables/useSeekTable'
import type {
  FgDocCreated, FgDocCreateForm, FgDocDetail, FgDocForm, FgDocQuery, FgDocRow, FgFromReceiptReq, FgPendingReceiptQuery,
  FgPendingReceiptRow, FgReceiptDraft, FgReceiptStocked, FgShipOrderOption, FgShipPrefill, FgShippedRow, FgStockByStyleRow,
  FgStockLogQuery, FgStockLogRow, FgStockQty, FgStockQuery, FgStockRow, FgStyleOption,
} from '@/types/fg'
import { attachmentBody } from './basic'
import { currentCompanyScope } from '@/utils/companyScope'
import { sessionEpoch } from '@/utils/token'
import type { PageParams } from './system'

/** FG 余额按主体分账；查询参数与请求头始终取同一个明确主体。 */
export function requireFgCompany(value: string | null | undefined): string {
  if (!value || !/^[1-9]\d*$/.test(value)) throw new ApiError(400, '请先选择主体')
  return value
}
function scope(companyId?: string | null, options?: RequestOptions): RequestOptions & { companyId: string } {
  const company = requireFgCompany(companyId ?? options?.companyId ?? currentCompanyScope())
  if (options?.companyId && options.companyId !== company) throw new ApiError(409, '主体已变化，请重新查询')
  return { ...options, companyId: company }
}
function get<T>(url: string, params: Record<string, unknown> = {}, options?: RequestOptions): Promise<T> {
  const scoped = scope(typeof params.companyId === 'string' ? params.companyId : undefined, options)
  return http.get<T>(url, { ...params, companyId: scoped.companyId }, scoped)
}
function checkIds(ids: readonly string[]) {
  if (ids.length > 100) throw new ApiError(400, '每次最多查询 100 个编号')
}
function companyUrl(url: string, companyId: string) { return `${url}?companyId=${encodeURIComponent(companyId)}` }
const docBase = '/api/fg-docs'

export const fgDocApi = {
  page: (params: PageParams & FgDocQuery, options?: RequestOptions) => get<PageResult<FgDocRow>>(docBase, { ...params }, options),
  get: (id: string, options?: RequestOptions) => get<FgDocDetail>(`${docBase}/${id}`, {}, options),
  create: (data: FgDocCreateForm, options?: RequestOptions) => http.post<FgDocCreated>(docBase, data, scope(data.companyId, options)),
  update: (id: string, data: FgDocForm, options?: RequestOptions) => http.put<number>(`${docBase}/${id}`, data, scope(data.companyId, options)),
  act: (id: string, action: DocAction, body: DocActionReq, options?: RequestOptions) => {
    const scoped = scope(undefined, options)
    return http.post<void>(companyUrl(`${docBase}/${id}/${action}`, scoped.companyId), snapshotRequestBody(body), scoped)
  },
  remove: (id: string, version: number, options?: RequestOptions) => {
    const scoped = scope(undefined, options)
    return http.delete<void>(`${companyUrl(`${docBase}/${id}`, scoped.companyId)}&version=${version}`, scoped)
  },
  logs: (id: string, options?: RequestOptions) => get<DocLog[]>(`${docBase}/${id}/logs`, {}, options),
  attachments: (id: string, options?: RequestOptions) => get<Attachment[]>(`${docBase}/${id}/attachments`, {}, options),
  replaceAttachments: (id: string, files: FileVO[], options?: RequestOptions) => {
    const scoped = scope(undefined, options)
    return http.put<Attachment[]>(companyUrl(`${docBase}/${id}/attachments`, scoped.companyId), snapshotRequestBody(attachmentBody(files)), scoped)
  },
  references: (id: string, options?: RequestOptions) => get<DocRef[]>(`${docBase}/${id}/references`, {}, options),
  export: (params: FgDocQuery, options?: RequestOptions) => {
    const scoped = scope(params.companyId, options)
    return download(`${docBase}/export`, { ...params, companyId: scoped.companyId }, params.kind === 'IN' ? '成品入库单.xlsx' : '成品出库单.xlsx', scoped)
  },
  styleOptions: (styleIds: string[], options?: RequestOptions) => {
    checkIds(styleIds)
    return get<FgStyleOption[]>(`${docBase}/style-options`, { styleIds }, options)
  },
  pendingReceipts: (params: PageParams & FgPendingReceiptQuery, options?: RequestOptions) =>
    get<PageResult<FgPendingReceiptRow>>(`${docBase}/pending-receipts`, { ...params }, options),
  prefillFromReceipt: (receiptId: string, options?: RequestOptions, docId?: string) => get<FgReceiptDraft>(`${docBase}/from-receipt/${receiptId}`, { docId }, options),
  fromReceipt: (receiptId: string, data: FgFromReceiptReq, options?: RequestOptions) => {
    const captured = scope(data.companyId, { ...options, authEpoch: options?.authEpoch ?? sessionEpoch() })
    return http.post<FgDocCreated>(companyUrl(`${docBase}/from-receipt/${receiptId}`, captured.companyId), snapshotRequestBody(data), captured)
  },
  shipOrders: (params: { companyId?: string | null; keyword?: string; warehouseId?: string | null }, options?: RequestOptions) =>
    get<FgShipOrderOption[]>(`${docBase}/ship-orders`, { ...params }, options),
  shipPrefill: (params: { companyId?: string | null; orderId: string; warehouseId?: string | null; docId?: string | null }, options?: RequestOptions) =>
    get<FgShipPrefill>(`${docBase}/ship-prefill`, { ...params }, options),
  itemsTemplateUrl: (companyId: string) => companyUrl(`${docBase}/items-template`, companyId),
  importUrl: (id: string, companyId: string, version: number) => `${companyUrl(`${docBase}/${id}/items/import`, companyId)}&version=${version}`,
  shipped: (orderId: string, options?: RequestOptions) => get<FgShippedRow[]>(`${docBase}/shipped`, { orderId }, options),
  byReceipts: (receiptIds: string[], options?: RequestOptions) => {
    checkIds(receiptIds)
    return get<FgReceiptStocked[]>(`${docBase}/by-receipts`, { receiptIds }, options)
  },
}

export const fgStockApi = {
  page: (params: PageParams & FgStockQuery, options?: RequestOptions) => get<PageResult<FgStockRow>>('/api/fg-stocks', { ...params }, options),
  byStyle: (params: PageParams & FgStockQuery, options?: RequestOptions) => get<PageResult<FgStockByStyleRow>>('/api/fg-stocks/by-style', { ...params }, options),
  qty: (warehouseId: string, skuIds: string[], options?: RequestOptions) => {
    checkIds(skuIds)
    return get<FgStockQty[]>('/api/fg-stocks/qty', { warehouseId, skuIds }, options)
  },
  export: (params: FgStockQuery, options?: RequestOptions) => {
    const scoped = scope(params.companyId, options)
    return download('/api/fg-stocks/export', { ...params, companyId: scoped.companyId }, '成品库存.xlsx', scoped)
  },
  exportByStyle: (params: FgStockQuery, options?: RequestOptions) => {
    const scoped = scope(params.companyId, options)
    return download('/api/fg-stocks/by-style/export', { ...params, companyId: scoped.companyId }, '成品库存按款.xlsx', scoped)
  },
}
export const fgStockLogApi = {
  page: (params: FgStockLogQuery & { cursor?: string; size: number }, options?: RequestOptions) => get<SeekResult<FgStockLogRow>>('/api/fg-stock-logs', { ...params }, options),
  export: (params: FgStockLogQuery, options?: RequestOptions) => {
    const scoped = scope(params.companyId, options)
    return download('/api/fg-stock-logs/export', { ...params, companyId: scoped.companyId }, '成品库存日志.xlsx', scoped)
  },
}
