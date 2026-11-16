import { http, type RequestOptions } from '@/utils/request'
import type { DocRef } from '@/types/biz'
import type { DeliveryRow, HomeKpi, HomeTodoQuery, HomeTodos, UpstreamDocType, UpstreamQuery, UpstreamRow } from '@/types/home'

/**
 * 首页（docs/api/06-首页.md）：后端在 board 域，只读、登录就能调，里面每一块按当前用户的权限和数据范围出。
 * 三块分开取：一块慢了、出错了不挡别的；首页 onActivated 时三块都重取
 */
export const homeApi = {
  /** 四个概况格子 */
  kpi: () => http.get<HomeKpi>('/api/board/home/kpi'),
  /** 待办（分组、具体到单据）和"其他提醒"的数。管理层默认全部，能按"我的"、负责人筛 */
  todos: (params: HomeTodoQuery = {}) => http.get<HomeTodos>('/api/board/home/todos', params),
  /** 近期交期：7 天内要交的和已经逾期的需求订单、生产单，最多 8 条 */
  deliveries: () => http.get<DeliveryRow[]>('/api/board/home/deliveries'),
}

/** 提醒挂在哪种单据上，就走那种单据的接口去处理（docApi 的 notices/{id}/handle） */
const UPSTREAM_DOC_BASE: Record<UpstreamDocType, string> = {
  PRODUCTION_ORDER: '/api/production-orders',
  PURCHASE_ORDER: '/api/purchase-orders',
  MRP_CALC: '/api/mrp-calcs',
}

/** 上游已变更、待处理（/upstream） */
export const upstreamApi = {
  /** 负责人是我的；有那种单据审核权限的人（管理层）看全部。不分页，按最新一条提醒的时间倒序 */
  list: (params: UpstreamQuery) => http.get<UpstreamRow[]>('/api/board/upstream', params),
  /** 处理一条提醒，remark 选填。批量条逐条调时传 { silent: true } */
  handle: (doc: Pick<DocRef, 'type' | 'id'>, noticeId: number, remark: string | null, options?: RequestOptions) =>
    http.post<void>(`${UPSTREAM_DOC_BASE[doc.type as UpstreamDocType]}/${doc.id}/notices/${noticeId}/handle`, { remark }, options),
}
