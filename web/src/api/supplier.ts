import { http, type RequestOptions } from '@/utils/request'
import type { PageParams } from '@/api/system'
import type { PageResult } from '@/types/system'
import type { DocLog } from '@/types/biz'
import type {
  CategoryNode, CertForm, SupplierBank, SupplierCert, SupplierChange, SupplierDetail, SupplierForm, SupplierOption, SupplierQuery,
  SupplierRow, SupplierSaveResult, SupplierUsage,
} from '@/types/supplier'

const base = '/api/suppliers'

/** 供应商（RFC 004 §8）。会被批量条逐条调的动作多收一个 options，批量时传 { silent: true } */
export const supplierApi = {
  page: (params: SupplierQuery & PageParams, options?: RequestOptions) => http.get<PageResult<SupplierRow>>(base, params, options),
  get: (id: string, options?: RequestOptions) => http.get<SupplierDetail>(`${base}/${id}`, undefined, options),
  /** 银行信息全号：要 bank 权限，每调一次记一条操作日志 */
  bank: (id: string) => http.get<SupplierBank>(`${base}/${id}/bank`),
  create: (data: SupplierForm) => http.post<SupplierSaveResult>(base, data),
  update: (id: string, data: SupplierForm) => http.put<SupplierSaveResult>(`${base}/${id}`, data),
  remove: (id: string) => http.delete<void>(`${base}/${id}`),

  /** 申请准入：缺资料时 1000，data 是缺项清单 */
  submit: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/submit`, undefined, options),
  withdraw: (id: string) => http.post<void>(`${base}/${id}/withdraw`),
  audit: (id: string) => http.post<void>(`${base}/${id}/audit`),
  reject: (id: string, reason: string) => http.post<void>(`${base}/${id}/reject`, { reason }),
  disable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/disable`, undefined, options),
  enable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/enable`, undefined, options),
  blacklist: (id: string, reason: string) => http.post<void>(`${base}/${id}/blacklist`, { reason }),
  unblacklist: (id: string, reason: string) => http.post<void>(`${base}/${id}/unblacklist`, { reason }),
  logs: (id: string) => http.get<DocLog[]>(`${base}/${id}/logs`),

  /** 关键字段改动：全部（新的在前）、批准、驳回、撤回 */
  changes: (id: string) => http.get<SupplierChange[]>(`${base}/${id}/changes`),
  approveChange: (id: string, changeId: string) => http.post<void>(`${base}/${id}/changes/${changeId}/approve`),
  rejectChange: (id: string, changeId: string, reason: string) =>
    http.post<void>(`${base}/${id}/changes/${changeId}/reject`, { reason }),
  withdrawChange: (id: string, changeId: string) => http.post<void>(`${base}/${id}/changes/${changeId}/withdraw`),

  certs: (id: string) => http.get<SupplierCert[]>(`${base}/${id}/certs`),
  addCert: (id: string, data: CertForm) => http.post<string>(`${base}/${id}/certs`, data),
  updateCert: (id: string, certId: string, data: CertForm) => http.put<void>(`${base}/${id}/certs/${certId}`, data),
  removeCert: (id: string, certId: string) => http.delete<void>(`${base}/${id}/certs/${certId}`),

  /**
   * 下拉（SupplierSelect）：登录就能调，最多 50 条。usage 不传 = SAMPLE。
   * includeId：编辑旧记录时把已经选中的那家带上，它停用了、不在前 50 条里也照样显示名字
   */
  /** category：逗号隔开多个，任一个就算 */
  options: (params: { category?: string; usage?: SupplierUsage; keyword?: string; includeId?: string }) =>
    http.get<SupplierOption[]>(`${base}/options`, params),
}

/** 擅长品类的树（basic 域，登录就能调）。拿不到时由调用方降级，不弹消息 */
export const categoryTreeApi = {
  tree: () => http.get<CategoryNode[]>('/api/categories/tree', undefined, { silent: true }),
}
