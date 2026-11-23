import { ApiError, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { DocLog } from '@/types/biz'
import type { PageResult } from '@/types/system'
import type { ObjectTagSnapshot, PublicTag, TagObjectType, TagQuery, TagReplace } from '@/types/tag'
import { TAG_OPTION_LIMIT } from '@/types/tag'
import type { PageParams } from './system'

const companyObjects = new Set<TagObjectType>(['SAMPLE', 'ORDER', 'PURCHASE_ORDER', 'INV_DOC', 'INV_COUNT', 'PRODUCTION_ORDER', 'PRODUCTION_RECEIPT', 'FG_DOC'])
function objectOptions(type: TagObjectType, companyId: string | null | undefined, options?: RequestOptions): RequestOptions {
  if (companyObjects.has(type) && !companyId) throw new ApiError(400, '缺少这张单据的主体，请重新读取单据')
  // 共享资料与实际 NULL 主体的算料单不猜测归属。
  return { ...options, companyId: companyId ?? undefined }
}
function objectUrl(type: TagObjectType, id: string) {
  if (!/^[1-9]\d*$/.test(id)) throw new ApiError(400, '对象编号不合法')
  return '/api/tags/objects/' + type + '/' + id
}

export const tagApi = {
  options(query: TagQuery = {}, options?: RequestOptions) {
    if ((query.includeIds?.length ?? 0) > TAG_OPTION_LIMIT) throw new ApiError(400, '一次最多读取 100 个标签')
    return http.get<PublicTag[]>('/api/tags', { ...query, includeIds: query.includeIds?.join(',') }, options)
  },
  filterOptions(query: TagQuery = {}, options?: RequestOptions) {
    if ((query.includeIds?.length ?? 0) > TAG_OPTION_LIMIT) throw new ApiError(400, '一次最多读取 100 个标签')
    return http.get<PublicTag[]>('/api/tags/filter-options', { ...query, includeIds: query.includeIds?.join(',') }, options)
  },
  read: (type: TagObjectType, id: string, companyId?: string | null, options?: RequestOptions) =>
    http.get<ObjectTagSnapshot>(objectUrl(type, id), undefined, objectOptions(type, companyId, options)),
  replace: (type: TagObjectType, id: string, body: TagReplace, companyId?: string | null, options?: RequestOptions) =>
    http.post<ObjectTagSnapshot>(objectUrl(type, id) + '/replace', snapshotRequestBody(body), objectOptions(type, companyId, options)),
  rename: (id: string, body: { version: number; name: string }, options?: RequestOptions) =>
    http.post<PublicTag>('/api/tags/' + id + '/rename', snapshotRequestBody(body), options),
  archive: (id: string, version: number, options?: RequestOptions) =>
    http.post<PublicTag>('/api/tags/' + id + '/archive', { version }, options),
  logs: (id: string, params: PageParams, options?: RequestOptions) =>
    http.get<PageResult<DocLog>>('/api/tags/' + id + '/logs', params, options),
}
