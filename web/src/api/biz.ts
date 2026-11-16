import { http, type RequestOptions } from '@/utils/request'
import type { FileVO } from '@/types/system'
import type { ApprovalRule, ApprovalRuleForm, Attachment, DocAction, DocActionReq, DocLog, DocNotice, DocRef } from '@/types/biz'
import { attachmentBody } from './basic'

/**
 * 单据公共接口（docs/api/README.md §4）：每种单据下面都挂这几个，路径一样。各模块的 api 文件这样用：
 *
 *   const base = '/api/orders'
 *   export const orderApi = { ...docApi(base), page: …, get: …, audit: (id, body: OrderAuditReq) => … }
 *
 * 审核要多填东西的单据（需求订单填承诺交期），在模块里另写 audit，别的动作照样用 act
 */
export function docApi(base: string) {
  return {
    /** 状态动作。批量条逐条调时传 { silent: true }，出错不弹、由批量条汇总 */
    act: (id: number, action: DocAction, body: DocActionReq, options?: RequestOptions) =>
      http.post<void>(`${base}/${id}/${action}`, body, options),
    /** 删除草稿（提交以后只能作废） */
    remove: (id: number, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
    /** 操作记录，新的在前（DocLogList 的 load） */
    logs: (id: number) => http.get<DocLog[]>(`${base}/${id}/logs`),
    /** 附件（单据上传的随附文件，不是图片）：整组替换 */
    attachments: (id: number) => http.get<Attachment[]>(`${base}/${id}/attachments`),
    replaceAttachments: (id: number, files: FileVO[]) => http.put<Attachment[]>(`${base}/${id}/attachments`, attachmentBody(files)),
    /** "下游单据"页签：由这张单生成、引用它的单据，作废的不算 */
    references: (id: number) => http.get<DocRef[]>(`${base}/${id}/references`),
    /** 上游变更提醒，没处理的在前 */
    notices: (id: number) => http.get<DocNotice[]>(`${base}/${id}/notices`),
    /** 处理一条提醒（看过了、改好了），remark 选填 */
    handleNotice: (id: number, noticeId: number, remark: string | null) =>
      http.post<void>(`${base}/${id}/notices/${noticeId}/handle`, { remark }),
  }
}

/** 设置 › 审批（架构 §4.8）：金额超过多少再加一级。只给超级管理员（biz:approval-rule:list、:edit），已经提交的单不受影响 */
export const approvalRuleApi = {
  list: () => http.get<ApprovalRule[]>('/api/approval-rules'),
  create: (data: ApprovalRuleForm) => http.post<number>('/api/approval-rules', data),
  update: (id: number, data: ApprovalRuleForm) => http.put<void>(`/api/approval-rules/${id}`, data),
  remove: (id: number) => http.delete<void>(`/api/approval-rules/${id}`),
}
