import { download, http, snapshotRequestBody, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type { DocActionReq } from '@/types/biz'
import type {
  FollowForm, MrpCalcDetail, MrpCalcQuery, MrpCalcRow, MrpCreated, MrpItemForm, MrpOrderOption, OrderKitting, PendingArrivalCounts,
  PendingArrivalQuery, PendingArrivalRow, PurchaseCreated, PurchaseDefaults, PurchaseDefaultsQuery, PurchaseDetail, PurchaseForm,
  PurchaseQuery, PurchaseRow, PurchaseUpdateForm, ReorderPickItem, ReorderRow, ReservePct, SkuSupply,
} from '@/types/purchase'
import { docApi, documentOptions } from './biz'
import type { PageParams } from './system'

/**
 * 算料和采购（docs/api/03-算料采购.md）：算料单、采购单、待到料、齐料状态、补货提醒、物料的在途。
 * 单据公共的接口（状态动作、删除、操作记录、附件、下游单据、变更提醒）在 docApi 里（README §4）。
 * 会被批量条逐条调的多收一个 options，批量时传 { silent: true }
 */

// ---------- 算料单 ----------

const mrpBase = '/api/mrp-calcs'

export const mrpApi = {
  /** remove（删草稿）、logs、attachments、references、notices。算料单的动作不在 DocAction 里，不用 act，用下面的 confirm、close */
  ...docApi(mrpBase),
  /** 要 purchase:mrp:list */
  page: (params: PageParams & MrpCalcQuery) => http.get<PageResult<MrpCalcRow>>(mrpBase, params),
  get: (id: string) => http.get<MrpCalcDetail>(`${mrpBase}/${id}`),
  /** "选订单算料"对话框：已审核、生产中的需求订单 */
  orderOptions: (params: PageParams & { keyword?: string }) => http.get<PageResult<MrpOrderOption>>(`${mrpBase}/order-options`, params),
  /** 选订单算料，存成一张草稿算料单 */
  create: async (orderIds: string[], options?: RequestOptions) => http.post<MrpCreated>(mrpBase, snapshotRequestBody({ orderIds }), orderIds[0] ? await documentOptions('/api/orders', orderIds[0], options) : options),
  /** 草稿里改一行（一次改一格），返回改完的整张详情 */
  updateItem: (id: string, itemId: string, data: MrpItemForm) => http.put<MrpCalcDetail>(`${mrpBase}/${id}/items/${itemId}`, data),
  /** 草稿里删一行（特殊情况，如这种料改由工厂自备），返回删完的详情 */
  removeItem: (id: string, itemId: string, version: number) =>
    http.delete<MrpCalcDetail>(`${mrpBase}/${id}/items/${itemId}?version=${version}`),
  /** 备注：没关闭时随时能改 */
  updateRemark: (id: string, data: { version: number; remark: string | null }) => http.put<void>(`${mrpBase}/${id}/remark`, data),
  /** 确认：用最新的数再算一遍可用量，变了回 1000"可用量已变化，请重新计算" */
  confirm: (id: string, body: DocActionReq) => http.post<void>(`${mrpBase}/${id}/confirm`, body),
  /** 关闭：占用释放，不能再生成采购单；reason 选填 */
  close: (id: string, body: DocActionReq) => http.post<void>(`${mrpBase}/${id}/close`, body),
  /** 重新算料：草稿就地重算（返回的还是这张）；已确认的另算一张草稿新单（返回新单），新单确认时这张才关闭 */
  recalc: (id: string, body: DocActionReq) => http.post<MrpCreated>(`${mrpBase}/${id}/recalc`, body),
  /** 物料类型的备料比（算料页"备料比"按钮） */
  reservePcts: () => http.get<ReservePct[]>(`${mrpBase}/reserve-pcts`),
  saveReservePcts: (list: Pick<ReservePct, 'materialType' | 'pct'>[]) => http.put<void>(`${mrpBase}/reserve-pcts`, list),
}

// ---------- 采购单 ----------

const poBase = '/api/purchase-orders'

export const purchaseApi = {
  /** act(id, 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit' | 'close' | 'reopen' | 'void', body, options)、remove、logs…… */
  ...docApi(poBase),
  /** 要 purchase:order:list */
  page: (params: PageParams & PurchaseQuery, options?: RequestOptions) => http.get<PageResult<PurchaseRow>>(poBase, params, options),
  get: (id: string) => http.get<PurchaseDetail>(`${poBase}/${id}`),
  create: (data: PurchaseForm) => http.post<PurchaseCreated>(poBase, data),
  /** 返回新的 version */
  update: (id: string, data: PurchaseUpdateForm) => http.put<number>(`${poBase}/${id}`, data),
  /** 表单默认值：供应商的结算方式、税率、采购员，交货日期，每个 SKU 的默认单价和档案现价。前端不直接调价格接口 */
  defaults: (params: PurchaseDefaultsQuery, options?: RequestOptions) => http.get<PurchaseDefaults>(`${poBase}/defaults`, params, options),
  /** 算料单勾的行 → 按供应商拆成几张采购单草稿（草稿算料单先确认）。返回建好的采购单 */
  fromMrp: async (data: { calcId: string; itemIds: string[]; version: number }, options?: RequestOptions) => http.post<PurchaseCreated[]>(`${poBase}/from-mrp`, snapshotRequestBody(data), await documentOptions(mrpBase, data.calcId, options)),
  /** 补货清单勾的行 → 按供应商拆成几张备货采购单草稿 */
  fromReorder: (items: ReorderPickItem[], options?: RequestOptions) => http.post<PurchaseCreated[]>(`${poBase}/from-reorder`, { items }, options),
  /** 改一行的预计到货、跟进备注（已审核、部分到货的单；要 purchase:order:edit）。不用 version */
  follow: async (id: string, itemId: string, data: FollowForm, options?: RequestOptions) =>
    http.put<void>(`${poBase}/${id}/items/${itemId}/follow`, snapshotRequestBody(data), await documentOptions(poBase, id, options)),
  export: (params: PurchaseQuery) => download(`${poBase}/export`, params, '采购单.xlsx'),
}

// ---------- 待到料（物料入库单页的第一个页签） ----------

export const pendingArrivalApi = {
  /** 要 purchase:order:list；默认按"预计到货 ?? 交期"从早到晚 */
  page: (params: PageParams & PendingArrivalQuery) => http.get<PageResult<PendingArrivalRow>>(`${poBase}/pending-items`, params),
  /** 页签上的数 */
  counts: (params: Omit<PendingArrivalQuery, 'tab'>) => http.get<PendingArrivalCounts>(`${poBase}/pending-items/counts`, params),
}

// ---------- 齐料状态 ----------

export const kittingApi = {
  /**
   * 几张需求订单的齐料状态，每个 id 一条；后端校验整批订单的数据范围和主体。
   * 订单列表、生产单列表只传当前页的订单 id；生产列表也可查当前可见生产单关联的订单。
   */
  byOrders: (orderIds: string[]) =>
    orderIds.length ? http.get<OrderKitting[]>('/api/order-kitting', { orderIds }, { silent: true }) : Promise.resolve([] as OrderKitting[]),
}

// ---------- 补货提醒、物料的在途 ----------

export const reorderApi = {
  /** 补货清单（要 purchase:order:list）：默认每页 50、最多 100；差得多的在前。 */
  page: (params: PageParams) => http.get<PageResult<ReorderRow>>('/api/reorder-items', params),
}

export const supplyApi = {
  /** 库存页的"在途"列、标黄（要 inventory:stock:list 或 purchase:order:list）：拿当前页的 SKU id 调一次 */
  bySkus: (skuIds: string[]) =>
    skuIds.length ? http.get<SkuSupply[]>('/api/material-supply', { skuIds }, { silent: true }) : Promise.resolve([] as SkuSupply[]),
}
