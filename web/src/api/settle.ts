import { download, http, snapshotRequestBody, upload, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type { Attachment, DocActionReq, DocLog } from '@/types/biz'
import type {
  CompareResult, DeductionForm, DeductionQuery, DeductionRow, PendingStatementRow, StatementCreated, StatementCreateForm,
  StatementDeductionForm, StatementDetail, StatementQuery, StatementRow,
} from '@/types/settle'
import type { PageParams } from './system'

/**
 * 对账结算（docs/api/09-对账.md，RFC 019 §5）。写操作要带单据的主体（X-Company-Id）：
 * 生成、登记扣款从请求体的 companyId 取；详情页用 bindCompanyApi(statementApi, () => detail.companyId)
 */

const base = '/api/statements'

export const statementApi = {
  /** 要 settle:statement:list */
  page: (params: PageParams & StatementQuery) => http.get<PageResult<StatementRow>>(base, params),
  /** 待对账：主体 × 供应商 × 月份，新的月份在前（最多 500 组） */
  pending: (params: { companyId?: string; partnerId?: string | null }) => http.get<PendingStatementRow[]>(`${base}/pending`, params),
  get: (id: string) => http.get<StatementDetail>(`${base}/${id}`),
  /** 生成：这段时间里这家还没对过的单据一张都没有报 1000 */
  create: (data: StatementCreateForm) => http.post<StatementCreated>(base, snapshotRequestBody(data)),
  /** 草稿刷新：后来审核的单据加进来（added 是几行）。详情页自己显示错误时传 { silent: true }，下同 */
  refresh: (id: string, body: DocActionReq, options?: RequestOptions) =>
    http.post<StatementCreated>(`${base}/${id}/refresh`, snapshotRequestBody(body), options),
  /** 草稿移出几行，返回新的 version */
  removeItems: (id: string, body: { version: number; itemIds: string[] }, options?: RequestOptions) =>
    http.post<number>(`${base}/${id}/remove-items`, snapshotRequestBody(body), options),
  /** 草稿加一笔扣款（同时登记到扣款登记），返回新的 version */
  addDeduction: (id: string, body: StatementDeductionForm, options?: RequestOptions) =>
    http.post<number>(`${base}/${id}/deductions`, snapshotRequestBody(body), options),
  /** send 发送、withdraw 撤回、confirm 确认（reason 是确认时写的一句）、void 作废（reason 是原因）。批量、自己显示错误时传 { silent: true } */
  act: (id: string, action: 'send' | 'withdraw' | 'confirm' | 'void', body: DocActionReq, options?: RequestOptions) =>
    http.post<void>(`${base}/${id}/${action}`, snapshotRequestBody(body), options),
  /** 上传对方的 Excel 比对；认不出表头报 1000 */
  compare: (id: string, version: number, file: File, options?: { silent?: boolean }) =>
    upload<CompareResult>(`${base}/${id}/compare`, file, { fields: { version: String(version) }, silent: options?.silent }),
  /** 写差异说明，只改传了的几处；note 传空串是清掉。返回新的 version */
  saveNotes: (id: string, body: { version: number; notes: { key: string; note: string | null }[] }, options?: RequestOptions) =>
    http.put<number>(`${base}/${id}/compare/notes`, snapshotRequestBody(body), options),
  /** 对方对账单的原件（最近一次比对传的） */
  theirFiles: (id: string) => http.get<Attachment[]>(`${base}/${id}/their-files`),
  export: (id: string, no: string) => download(`${base}/${id}/export`, undefined, `对账单-${no}.xlsx`),
  logs: (id: string) => http.get<DocLog[]>(`${base}/${id}/logs`),
}

const deductionBase = '/api/deductions'

export const deductionApi = {
  /** 要 settle:deduction:list */
  page: (params: PageParams & DeductionQuery) => http.get<PageResult<DeductionRow>>(deductionBase, params),
  /** 返回 id */
  create: (data: DeductionForm) => http.post<string>(deductionBase, snapshotRequestBody(data)),
  /** 进了没作废的对账单的不能改（1000）；不能换主体。返回新的 version */
  update: (id: string, data: DeductionForm) => http.put<number>(`${deductionBase}/${id}`, snapshotRequestBody(data)),
  /** 进了没作废的对账单的不能删（1000） */
  remove: (id: string, companyId: string) => http.delete<void>(`${deductionBase}/${id}`, { companyId }),
}
