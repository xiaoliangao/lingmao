import { download, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type {
  BoardCardKey, BoardCards, BoardFactoryRow, BoardQuery, IssueCloseForm, IssueCreated, IssueForm, IssueOption, IssueOptionQuery,
  MobileOrderRow, NodePlanReq, NodeReport, NodeReportForm, NodeReportResult, ProductionCounts, ProductionDetail, ProductionForm,
  ProductionIssue, ProductionMaterials, ProductionQuery, ProductionReceipt, ProductionRow, ReceiptCreated, ReceiptForm,
} from '@/types/production'
import { attachmentBody } from './basic'
import { docApi, documentOptions } from './biz'
import type { PageParams } from './system'

/**
 * 外发加工单、成品采购单（docs/api/05-生产.md）：一组接口，列表按 kind 分。权限码两种单共用 production:order:*：
 * list、add（排单，02）、edit、remove、submit、audit（反审核、结案、取消结案）、void、export；审核、驳回只要求登录，
 * 谁能审由多级审批按当前这一级判断（架构 §4.8）。进度、异常、回货另有 production:node:edit、production:issue:edit、
 * production:receipt:add、production:receipt:void。
 * 排单在 api/order.ts 的 scheduleApi；领料、退料走 04 的 invDocApi.prefillFromProduction / fromProduction。
 * 会被批量条逐条调的多收一个 options，批量时传 { silent: true }
 */
const base = '/api/production-orders'

export const productionApi = {
  /** act(id, 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit' | 'close' | 'reopen' | 'void', body, options)、remove、logs…… */
  ...docApi(base),
  page: (params: PageParams & ProductionQuery, options?: RequestOptions) => http.get<PageResult<ProductionRow>>(base, params, options),
  /** 状态页签上的数：筛选条件和列表一样（status、returned 不看） */
  counts: (params: Omit<ProductionQuery, 'status' | 'returned' | 'ids'>) => http.get<ProductionCounts>(`${base}/counts`, params),
  /** 不在数据范围里 404。地址的种类和单子对不上（/production/ 打开了 CP 单）时页面按 kind 换地址 */
  get: (id: string) => http.get<ProductionDetail>(`${base}/${id}`),
  /** 改草稿，返回新的 version。不是草稿、version 对不上 1002；加工方式换成了另一种单的 1000 */
  update: (id: string, data: ProductionForm) => http.put<number>(`${base}/${id}`, data),
  export: (params: ProductionQuery, fallbackName: string) => download(`${base}/export`, params, fallbackName),

  // ---------- 进度节点 ----------

  /** 改节点计划（已下单、生产中）：全部节点一起发；计划完成早于计划开始 400 */
  updatePlan: async (id: string, data: NodePlanReq, options?: RequestOptions) => http.put<{ planRevision: number }>(`${base}/${id}/nodes`, snapshotRequestBody(data), await documentOptions(base, id, options)),
  /** 报进度（电脑、手机共用）。已经报过完成的节点再"只报开始" 1000 */
  report: async (id: string, nodeId: string, { photos, ...data }: NodeReportForm, options?: RequestOptions) =>
    http.post<NodeReportResult>(`${base}/${id}/nodes/${nodeId}/reports`, snapshotRequestBody({ ...data, photos: attachmentBody(photos) }), await documentOptions(base, id, options)),
  /** 一个节点的全部汇报记录，新的在前 */
  nodeReports: (id: string, nodeId: string) => http.get<NodeReport[]>(`${base}/${id}/nodes/${nodeId}/reports`),

  // ---------- 领料页签 ----------

  /** 应领、已领、还差（只算自购的行）和挂在这张单上的领料、退料单 */
  materials: (id: string) => http.get<ProductionMaterials>(`${base}/${id}/materials`),
  /** 给 04 选生产单用：领料列还有没领齐的，returnable = true 列已领 > 0 的（要 inventory:doc:add） */
  issueOptions: (params: PageParams & IssueOptionQuery) => http.get<PageResult<IssueOption>>(`${base}/issue-options`, params),

  // ---------- 异常 ----------

  /** 全部异常，没关闭的在前 */
  issues: (id: string) => http.get<ProductionIssue[]>(`${base}/${id}/issues`),
  /** 上报（电脑、手机共用）。没传照片 400；生产单不是已下单、生产中 1000 */
  reportIssue: async (id: string, { photos, ...data }: IssueForm, options?: RequestOptions) =>
    http.post<IssueCreated>(`${base}/${id}/issues`, snapshotRequestBody({ ...data, photos: attachmentBody(photos) }), await documentOptions(base, id, options)),
  /** 开始处理：待处理 → 处理中，处理人记我。已经不是待处理 1002 */
  startIssue: async (id: string, issueId: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/issues/${issueId}/start`, undefined, await documentOptions(base, id, options)),
  /** 关闭：处理措施必填（400）。已经关了 1002 */
  closeIssue: async (id: string, issueId: string, data: IssueCloseForm, options?: RequestOptions) => http.post<void>(`${base}/${id}/issues/${issueId}/close`, snapshotRequestBody(data), await documentOptions(base, id, options)),

  // ---------- 回货 ----------

  /** 全部回货单（含作废的），新的在前 */
  receipts: (id: string) => http.get<ProductionReceipt[]>(`${base}/${id}/receipts`),
  /** 登记回货（建 + 审核一步）。超过 ⌊件数 × 1.03⌋ 1004（data 是 ReceiptOverRow[]） */
  receive: async (id: string, { photos, ...data }: ReceiptForm, options?: RequestOptions) =>
    http.post<ReceiptCreated>(`${base}/${id}/receipts`, snapshotRequestBody({ ...data, photos: attachmentBody(photos) }), await documentOptions(base, id, options)),
  /** 作废一张回货单：已回、次品减回去，生产单、订单的状态重算。原因必填 */
  voidReceipt: async (id: string, receiptId: string, reason: string, options?: RequestOptions) =>
    http.post<void>(`${base}/${id}/receipts/${receiptId}/void`, { reason }, await documentOptions(base, id, options)),

  // ---------- 手机报工 ----------

  /** 我的跟单：生产跟单是我、已下单或生产中，按风险排（要 production:mobile:list）。点进去用 get 取详情 */
  myFollow: () => http.get<MobileOrderRow[]>(`${base}/my-follow`),
}

/** 生产看板（要 production:board:list）。列表的行和外发加工单列表一样，表格组件共用 */
export const boardApi = {
  cards: (params: BoardQuery) => http.get<BoardCards>('/api/production-board/cards', params),
  /** 默认按风险排（逾期 > 节点延期 > 有异常 > 临期），再按交期 */
  page: (params: PageParams & BoardQuery & { card?: BoardCardKey }) =>
    http.get<PageResult<ProductionRow>>('/api/production-board/orders', params),
  /** 按工厂看：factoryId 不看（点一行再筛进去）；合作中的成衣厂、加工厂都列，手上没活的也列 */
  factories: (params: Omit<BoardQuery, 'factoryId'>) => http.get<BoardFactoryRow[]>('/api/production-board/factories', params),
}
