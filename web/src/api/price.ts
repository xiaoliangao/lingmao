import { http, type RequestOptions } from '@/utils/request'
import type { PageParams } from '@/api/system'
import type { PageResult } from '@/types/system'
import type { CurrentPrice, CurrentPriceQuery, PriceQuery, PriceRow, PriceSaveForm } from '@/types/price'

const base = '/api/prices'

/** 价格档案（RFC 006 §7）。没有单独的页面：物料、供应商、款式详情的"价格记录"页签用 */
export const priceApi = {
  /** 要 price:price:list */
  page: (params: PriceQuery & PageParams) => http.get<PageResult<PriceRow>>(base, params),
  /** 录价对话框的提示（要两个改价权限之一） */
  current: (params: CurrentPriceQuery, options?: RequestOptions) => http.get<CurrentPrice>(`${base}/current`, params, options),
  /** 录一条，返回 id。物料价要 price:material:edit，加工价要 price:process:edit */
  record: (data: PriceSaveForm) => http.post<number>(base, data),
  /** 作废（原因必填）。已经作废了是 1002 */
  void: (id: number, reason: string) => http.post<void>(`${base}/${id}/void`, { reason }),
}
