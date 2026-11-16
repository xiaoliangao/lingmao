import { http, type RequestOptions } from '@/utils/request'
import type { FileVO, PageResult } from '@/types/system'
import type { Attachment, DocLog } from '@/types/biz'
import type { StyleCreateResult, StyleDetail, StylePickRow, StyleRow, StyleSku, StyleStatus, TechPackStatus } from '@/types/style'
import { attachmentBody } from './basic'
import type { PageParams } from './system'

/** 款式（RFC 005 §7）。会被批量条逐条调的动作多收一个 options，批量时传 { silent: true } */
const base = '/api/styles'

export interface StyleQuery {
  /** 款号、名称、花名、品名；有空格 = 按几个款号查 */
  keyword?: string
  /** 含下级 */
  categoryId?: number
  styleClass?: string
  brand?: string
  year?: number
  season?: string
  wave?: string
  designerId?: number
  status?: StyleStatus
  techPackStatus?: TechPackStatus
  /** 导出选中的 */
  ids?: number[]
}

/** 新建、修改共用的列。保存是整体覆盖：没填的发 null */
export interface StyleForm {
  name: string
  aliasName: string | null
  styleClass: string
  brand: string
  categoryId: number | null
  year: number | null
  season: string
  wave: string | null
  styleTag: string | null
  series: string | null
  gender: string | null
  designerId: number | null
  patternMakerId: number | null
  source: string | null
  sizeGroupId: number | null
  mainMaterialId: number | null
  productName: string | null
  composition: string | null
  execStandard: string | null
  safetyCategory: string | null
  tagPrice: number | null
  refCost: number | null
  defaultFactoryId: number | null
  /** yyyy-MM-dd */
  launchDate: string | null
  remark: string | null
}

/** 新建多出来的：款号怎么来、第一批颜色（建款时就按 颜色 × 尺码组 生成 SKU）、图片、设计附件 */
export interface StyleCreateForm extends StyleForm {
  codeMode: 'AUTO' | 'MANUAL'
  code: string | null
  colorIds: number[]
  images: FileVO[]
  files: FileVO[]
}

export const styleApi = {
  page: (params: PageParams & StyleQuery, options?: RequestOptions) => http.get<PageResult<StyleRow>>(base, params, options),
  get: (id: number) => http.get<StyleDetail>(`${base}/${id}`),
  logs: (id: number) => http.get<DocLog[]>(`${base}/${id}/logs`),
  /** 自动款号预览：不占号，保存时才取。设计师没编号、今天流水满了回 1000，消息说清楚怎么办 */
  codePreview: (styleClass: string, designerId: number | null) =>
    http.get<string>(`${base}/code-preview`, { styleClass, designerId: designerId ?? undefined }, { silent: true }),
  /** 选款式的弹窗：启用的款式（includeDraft 时草稿也列，录加工价用），关键字查款号、名称、花名、品牌；品类含下级（登录即可） */
  pick: (params: PageParams & { keyword?: string; categoryId?: number; includeDraft?: boolean }) =>
    http.get<PageResult<StylePickRow>>(`${base}/pick`, params),
  create: ({ images, files, ...data }: StyleCreateForm) =>
    http.post<StyleCreateResult>(base, { ...data, images: attachmentBody(images), files: attachmentBody(files) }),
  update: (id: number, data: StyleForm) => http.put<void>(`${base}/${id}`, data),
  remove: (id: number, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
  enable: (id: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/enable`, undefined, options),
  disable: (id: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/disable`, undefined, options),
  replaceImages: (id: number, files: FileVO[]) => http.put<Attachment[]>(`${base}/${id}/images`, attachmentBody(files)),
  replaceFiles: (id: number, files: FileVO[]) => http.put<Attachment[]>(`${base}/${id}/files`, attachmentBody(files)),

  // ---------- 颜色、SKU ----------
  addColors: (id: number, colorIds: number[]) => http.post<void>(`${base}/${id}/colors`, { colorIds }),
  /** 传这个款上全部颜色的新顺序 */
  reorderColors: (id: number, colorIds: number[]) => http.put<void>(`${base}/${id}/colors/order`, { colorIds }),
  enableColor: (id: number, colorId: number) => http.post<void>(`${base}/${id}/colors/${colorId}/enable`),
  disableColor: (id: number, colorId: number) => http.post<void>(`${base}/${id}/colors/${colorId}/disable`),
  /** 只有还没生成 SKU 的颜色能去掉 */
  removeColor: (id: number, colorId: number) => http.delete<void>(`${base}/${id}/colors/${colorId}`),
  skus: (id: number) => http.get<StyleSku[]>(`${base}/${id}/skus`),
  /** 按 启用的颜色 × 尺码组 补齐没有的 SKU，返回新生成了几个 */
  generateSkus: (id: number) => http.post<number>(`${base}/${id}/skus/generate`),
  updateSku: (id: number, skuId: number, data: { barcode: string | null; tagPrice: number | null }) =>
    http.put<void>(`${base}/${id}/skus/${skuId}`, data),
  removeSku: (id: number, skuId: number) => http.delete<void>(`${base}/${id}/skus/${skuId}`),
  enableSku: (id: number, skuId: number) => http.post<void>(`${base}/${id}/skus/${skuId}/enable`),
  disableSku: (id: number, skuId: number) => http.post<void>(`${base}/${id}/skus/${skuId}/disable`),
}
