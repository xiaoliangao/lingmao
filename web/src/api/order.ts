import { http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type {
  ChangeStatus, OrderAuditReq, OrderChangeCreateForm, OrderChangeDetail, OrderChangeQuery, OrderChangeRow, OrderChangeUpdateForm,
  OrderCreateForm, OrderDetail, OrderImportHead, OrderQuery, OrderReview, OrderRow, OrderStatus, OrderStyleOption, OrderUpdateForm,
  ScheduleBlock, SchedulePrefill, ScheduleQuote, ScheduleResult, StyleOrderRow,
} from '@/types/order'
import { attachmentBody } from './basic'
import type { DocSaved } from '@/types/biz'
import { docApi, documentOptions } from './biz'
import type { PageParams } from './system'

/**
 * 需求订单（docs/api/02-需求订单.md）。权限码 order:order:*：list、add、edit、remove、submit、void、audit（反审核、结案、取消结案）、
 * import、export；审核、驳回只要求登录，谁能审由多级审批按当前这一级判断（架构 §4.8）。
 * 状态动作、附件、操作记录、下游单据、变更提醒用 docApi 的那几个；审核要填承诺交期，用这里的 audit
 */
const base = '/api/orders'

export const orderApi = {
  ...docApi(base),
  page: (params: PageParams & OrderQuery, options?: RequestOptions) => http.get<PageResult<OrderRow>>(base, params, options),
  /** 状态页签上的条数：筛选条件和列表一样（status 不看），返回每个状态几张，没有的状态不在里面 */
  counts: (params: Omit<OrderQuery, 'status' | 'ids'>) => http.get<Partial<Record<OrderStatus, number>>>(`${base}/counts`, params),
  get: (id: string) => http.get<OrderDetail>(`${base}/${id}`),
  /** 新建草稿，返回 id 和 version。"提交审核"就接着调 act(id, 'submit', { version }) */
  create: ({ files, ...data }: OrderCreateForm) => http.post<DocSaved>(base, { ...data, files: attachmentBody(files) }),
  /** 保存草稿：整单覆盖，返回新的 version（"保存并提交"接着用它提交）。不是草稿了、version 对不上回 1002 */
  update: (id: string, data: OrderUpdateForm) => http.put<number>(`${base}/${id}`, data),
  /**
   * 审核（每一级都填承诺交期）。中间一级审过单据还是待审核；最后一级审过锁定每个款的生产 BOM。
   * 不是当前这一级的审批人 403，状态变了 1002，有款没有生效的生产 BOM 1000
   */
  audit: async (id: string, body: OrderAuditReq, options?: RequestOptions) => http.post<void>(`${base}/${id}/audit`, snapshotRequestBody(body), await documentOptions(base, id, options)),
  /** 录单、提变更画矩阵用：几个款的尺码、颜色、SKU、能不能下（登录即可，订单页面用） */
  styleOptions: (styleIds: string[]) => http.get<OrderStyleOption[]>(`${base}/style-options`, { styleIds }),
  /** 款式详情的"需求订单"页签：含这个款的订单，按下单日期倒序（要 order:order:list，照数据范围） */
  byStyle: (styleId: string, params: PageParams) => http.get<PageResult<StyleOrderRow>>(`${base}/by-style`, { styleId, ...params }),
  /** ImportDialog 的 template-url、export 用的地址 */
  importTemplateUrl: `${base}/import-template`,
  exportUrl: `${base}/export`,
  /** ImportDialog 的 import-url：单头拼在查询参数里，文件还是 multipart 的 file。成功返回 { total, notes }，notes 里列建了哪几张单 */
  importUrl(head: OrderImportHead): string {
    const query = new URLSearchParams()
    Object.entries(head).forEach(([k, v]) => {
      if (v != null && v !== '') query.append(k, String(v))
    })
    return `${base}/import?${query}`
  },
}

/** 审核对话框的评审信息：资料包、缺口最大的料、默认加工厂在手（后端在 board 域；能看这张订单就能取） */
export const orderReviewApi = {
  get: (orderId: string) => http.get<OrderReview>(`/api/board/orders/${orderId}/review`),
}

/**
 * 排单（界面在订单详情，后端在 production 域：POST /api/production-orders/schedule，架构 §3.1、§7.1）。
 * 要 production:order:add。外发加工单、成品采购单本身的接口在 05-生产
 */
export const scheduleApi = {
  /** 打开对话框时带出：还有没排完的款、每个 SKU 还剩多少、默认交期和跟单 */
  prefill: (orderId: string) => http.get<SchedulePrefill>('/api/production-orders/schedule-prefill', { orderId }),
  /** 选了加工厂：每种加工方式的现价、结算方式、税率、能不能下单 */
  quote: (orderId: string, styleId: string, factoryId: string) =>
    http.get<ScheduleQuote>('/api/production-orders/schedule-quote', { orderId, styleId, factoryId }),
  /**
   * 生成生产单草稿：一块一张。超排 1004（data 是 ScheduleOverRow[]）；工厂不能用、单价没填、改了价没写原因 1000；
   * 订单不是已审核、生产中了 1002
   */
  schedule: async (orderId: string, blocks: ScheduleBlock[], options?: RequestOptions) =>
    http.post<ScheduleResult>('/api/production-orders/schedule', snapshotRequestBody({ orderId, blocks }), await documentOptions(base, orderId, options)),
}

/**
 * 订单变更单（docs/api/02-需求订单.md 的"变更单"）。权限码 order:change:*：list、add、edit、remove、submit、void、audit（审核、驳回）。
 * 单级审核，状态动作用 act
 */
const changeBase = '/api/order-changes'

export const orderChangeApi = {
  ...docApi(changeBase),
  page: (params: PageParams & OrderChangeQuery) => http.get<PageResult<OrderChangeRow>>(changeBase, params),
  /** 各状态几张。DRAFT 是全部草稿，REJECTED 是其中被驳回的几张（页面"草稿"页签 = DRAFT − REJECTED） */
  counts: (params: Omit<OrderChangeQuery, 'status' | 'rejected'>) =>
    http.get<Partial<Record<ChangeStatus | 'REJECTED', number>>>(`${changeBase}/counts`, params),
  get: (id: string) => http.get<OrderChangeDetail>(`${changeBase}/${id}`),
  /** 新建草稿，返回 id 和 version；"提交变更"就接着调 act(id, 'submit', { version })。没有改动、订单不能变更了、已经有没审完的变更单 1000 */
  create: ({ files, ...data }: OrderChangeCreateForm) => http.post<DocSaved>(changeBase, { ...data, files: attachmentBody(files) }),
  /** 改草稿（被驳回的改了再提交）：整体覆盖，返回新的 version */
  update: (id: string, data: OrderChangeUpdateForm) => http.put<number>(`${changeBase}/${id}`, data),
}
