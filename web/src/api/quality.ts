import { http, snapshotRequestBody } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type {
  QcCreated, QcForm, QcKind, QcOrderOption, QcPendingRow, QcQuery, QcRow, QcSource, QcSupplierStat,
} from '@/types/quality'
import type { PageParams } from './system'

/**
 * 质检（docs/api/10-质量.md，RFC 019 §6）。写操作带记录（来源单）的主体：create 传来源单的 companyId，
 * 改、删、返修传记录的 companyId
 */
const base = '/api/qc-inspections'

export const qcApi = {
  /** 要 quality:inspection:list */
  page: (params: PageParams & QcQuery) => http.get<PageResult<QcRow>>(base, params),
  /** 待检：最近 30 天已审核还没验的采购入库、回货，加上返修完成等再验的回货 */
  pending: (params: PageParams & { kind?: QcKind; companyId?: string; keyword?: string }) =>
    http.get<PageResult<QcPendingRow>>(`${base}/pending`, params),
  /** 不合格、返修：尾期验货不合格还没返修完的 */
  reworks: () => http.get<QcRow[]>(`${base}/reworks`),
  /** 供应商质量（默认最近 90 天） */
  supplierStats: (params: { dateFrom?: string; dateTo?: string; kind?: QcKind }) =>
    http.get<QcSupplierStat[]>(`${base}/supplier-stats`, params),
  /** 记质检前看来源单；现在不能验的回 1000（说为什么） */
  source: (kind: QcKind, sourceId: string) => http.get<QcSource>(`${base}/source`, { kind, sourceId }),
  /** 记产前样、中检时选生产单（最多 20 张） */
  orderOptions: (keyword: string) => http.get<QcOrderOption[]>(`${base}/order-options`, { keyword }),
  get: (id: string) => http.get<QcRow>(`${base}/${id}`),
  create: (data: QcForm, companyId: string) => http.post<QcCreated>(base, snapshotRequestBody(data), { companyId }),
  /** 返回新的 version */
  update: (id: string, data: QcForm, companyId: string) => http.put<number>(`${base}/${id}`, snapshotRequestBody(data), { companyId }),
  remove: (id: string, companyId: string) => http.delete<void>(`${base}/${id}`, { companyId }),
  /** 安排返修，返回新的 version */
  rework: (id: string, body: { version: number; note: string }, companyId: string) =>
    http.post<number>(`${base}/${id}/rework`, snapshotRequestBody(body), { companyId }),
  /** 返修完成：回到待检，返回新的 version */
  reworkDone: (id: string, body: { version: number }, companyId: string) =>
    http.post<number>(`${base}/${id}/rework-done`, snapshotRequestBody(body), { companyId }),
}
