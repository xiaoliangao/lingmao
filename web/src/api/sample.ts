import { ApiError, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import { attachmentBody } from './basic'
import type { Attachment, DocLog, DocRef } from '@/types/biz'
import type { FileVO, PageResult } from '@/types/system'
import type { ConvertForm, ConvertPreview, ConvertResult, DispatchForm, NodeDoneForm, NodePlanForm, PlannedNode,
  ReviewForm, SampleActionResult, SampleCounts, SampleCreated, SampleCreateForm, SampleDetail, SampleImagesResult,
  SampleOrderFilesResult, SampleOrderForm, SampleQuery, SampleRef, SampleRemoved, SampleRow, SampleUpdateForm, SampleVersionPage } from '@/types/sample'

/** 公司来自当前查询/单头的快照；此域不回退到可变的默认主体。 */
export function sampleScope(companyId: string | null | undefined, options?: RequestOptions): RequestOptions & { companyId: string } {
  if (!companyId || !/^[1-9]\d*$/.test(companyId)) throw new ApiError(400, '请先选择样衣主体')
  if (options?.companyId && options.companyId !== companyId) throw new ApiError(409, '主体已变化，请重新读取')
  return { ...options, companyId }
}
function url(path: string, companyId: string, extra: Record<string, string | number> = {}): string {
  return `${path}?${new URLSearchParams({ companyId, ...Object.fromEntries(Object.entries(extra).map(([key, value]) => [key, String(value)])) })}`
}
function get<T>(path: string, companyId: string, params: object = {}, options?: RequestOptions) {
  return http.get<T>(path, { ...params, companyId }, sampleScope(companyId, options))
}
function post<T>(path: string, companyId: string, body: object, options?: RequestOptions) {
  return http.post<T>(url(path, companyId), snapshotRequestBody(body), sampleScope(companyId, options))
}
function put<T>(path: string, companyId: string, body: object, options?: RequestOptions) {
  return http.put<T>(url(path, companyId), snapshotRequestBody(body), sampleScope(companyId, options))
}
const base = '/api/samples'
const orders = '/api/sample-orders'
export const sampleApi = {
  page: (query: SampleQuery, options?: RequestOptions) => get<PageResult<SampleRow>>(base, query.companyId, query, options),
  counts: (query: SampleQuery, options?: RequestOptions) => get<SampleCounts>(`${base}/counts`, query.companyId, query, options),
  get: (id: string, companyId: string, options?: RequestOptions) => get<SampleDetail>(`${base}/${id}`, companyId, {}, options),
  versions: (id: string, companyId: string, params: { beforeVersion?: number; size?: number } = {}, options?: RequestOptions) =>
    get<SampleVersionPage>(`${base}/${id}/orders`, companyId, params, options),
  byStyle: (styleId: string, companyId: string, options?: RequestOptions) => get<SampleRef | null>(`${base}/by-style/${styleId}`, companyId, {}, options),
  create: (body: SampleCreateForm, options?: RequestOptions) => http.post<SampleCreated>(base, snapshotRequestBody(body), sampleScope(body.companyId, options)),
  update: (id: string, oldCompanyId: string, body: SampleUpdateForm, options?: RequestOptions) => put<SampleActionResult>(`${base}/${id}`, oldCompanyId, body, options),
  remove: (id: string, companyId: string, version: number, options?: RequestOptions) => http.delete<SampleRemoved>(url(`${base}/${id}`, companyId, { version }), sampleScope(companyId, options)),
  images: (id: string, companyId: string, version: number, files: FileVO[], options?: RequestOptions) =>
    put<SampleImagesResult>(`${base}/${id}/images`, companyId, { version, files: attachmentBody(files) }, options),
  reason: (id: string, companyId: string, action: 'stop' | 'reopen', body: { version: number; reason: string }, options?: RequestOptions) =>
    post<SampleActionResult>(`${base}/${id}/${action}`, companyId, body, options),
  convertPreview: (id: string, companyId: string, styleClass: string, options?: RequestOptions) => get<ConvertPreview>(`${base}/${id}/convert-preview`, companyId, { styleClass }, options),
  convert: (id: string, companyId: string, body: ConvertForm, options?: RequestOptions) => post<ConvertResult>(`${base}/${id}/convert`, companyId, body, options),
  logs: (id: string, companyId: string, page: number, size: number, options?: RequestOptions) => get<PageResult<DocLog>>(`${base}/${id}/logs`, companyId, { page, size }, options),
  references: (id: string, companyId: string, options?: RequestOptions) => get<DocRef[]>(`${base}/${id}/references`, companyId, {}, options),
}
export const sampleOrderApi = {
  update: (id: string, companyId: string, body: SampleOrderForm, options?: RequestOptions) => put<SampleActionResult>(`${orders}/${id}`, companyId, body, options),
  dispatch: (id: string, companyId: string, body: DispatchForm, options?: RequestOptions) => post<SampleActionResult>(`${orders}/${id}/dispatch`, companyId, body, options),
  dispatchPreview: (id: string, companyId: string, needDate: string, options?: RequestOptions) => get<{ nodes: PlannedNode[] }>(`${orders}/${id}/dispatch-preview`, companyId, { needDate }, options),
  nodePlans: (id: string, companyId: string, body: NodePlanForm, options?: RequestOptions) => put<SampleActionResult>(`${orders}/${id}/nodes`, companyId, body, options),
  nodeDone: (id: string, nodeId: string, companyId: string, body: NodeDoneForm, options?: RequestOptions) => post<SampleActionResult>(`${orders}/${id}/nodes/${nodeId}/done`, companyId, body, options),
  nodeUndo: (id: string, nodeId: string, companyId: string, body: Pick<NodePlanForm, 'version' | 'sampleVersionLock'>, options?: RequestOptions) =>
    http.delete<SampleActionResult>(url(`${orders}/${id}/nodes/${nodeId}/done`, companyId, body), sampleScope(companyId, options)),
  review: (id: string, companyId: string, body: ReviewForm, options?: RequestOptions) => post<SampleActionResult>(`${orders}/${id}/review`, companyId, body, options),
  files: (id: string, companyId: string, options?: RequestOptions) => get<Attachment[]>(`${orders}/${id}/attachments`, companyId, {}, options),
  replaceFiles: (id: string, companyId: string, locks: Pick<NodePlanForm, 'version' | 'sampleVersionLock'>, files: FileVO[], options?: RequestOptions) =>
    put<SampleOrderFilesResult>(`${orders}/${id}/attachments`, companyId, { ...locks, files: attachmentBody(files) }, options),
}
