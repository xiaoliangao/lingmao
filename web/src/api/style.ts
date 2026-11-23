import { http, type RequestOptions } from '@/utils/request'
import type { FileVO, PageResult } from '@/types/system'
import type { Attachment, DocLog } from '@/types/biz'
import type { StyleCreateResult, StyleDetail, StylePickRow, StyleRow, StyleSku, StyleStatus, TechPackStatus } from '@/types/style'
import { attachmentBody } from './basic'
import type { PageParams } from './system'

/** 款式（RFC 005 §7）。会被批量条逐条调的动作多收一个 options，批量时传 { silent: true } */
const base = '/api/styles'

export interface StyleQuery {
  /** 同时包含全部所选公共标签；最多 30 个稳定 ID。 */
  tagIds?: string[]
  /** 款号、名称、花名、品名；有空格 = 按几个款号查 */
  keyword?: string
  /** 含下级 */
  categoryId?: string
  styleClass?: string
  brand?: string
  year?: number
  season?: string
  wave?: string
  designerId?: string
  status?: StyleStatus
  techPackStatus?: TechPackStatus
  /** 导出选中的 */
  ids?: string[]
}

/** 新建、修改共用的列。保存是整体覆盖：没填的发 null */
export interface StyleForm {
  name: string
  aliasName: string | null
  styleClass: string
  brand: string
  categoryId: string | null
  year: number | null
  season: string
  wave: string | null
  styleTag: string | null
  series: string | null
  gender: string | null
  designerId: string | null
  patternMakerId: string | null
  source: string | null
  sizeGroupId: string | null
  mainMaterialId: string | null
  productName: string | null
  composition: string | null
  execStandard: string | null
  safetyCategory: string | null
  tagPrice: number | null
  refCost: number | null
  defaultFactoryId: string | null
  /** yyyy-MM-dd */
  launchDate: string | null
  remark: string | null
}

/** 新建多出来的：款号怎么来、第一批颜色（建款时就按 颜色 × 尺码组 生成 SKU）、图片、设计附件 */
export interface StyleCreateForm extends StyleForm {
  codeMode: 'AUTO' | 'MANUAL'
  code: string | null
  colorIds: string[]
  images: FileVO[]
  files: FileVO[]
}

export const styleApi = {
  page: (params: PageParams & StyleQuery, options?: RequestOptions) => http.get<PageResult<StyleRow>>(base, params, options),
  get: (id: string) => http.get<StyleDetail>(`${base}/${id}`),
  logs: (id: string) => http.get<DocLog[]>(`${base}/${id}/logs`),
  /** 自动款号预览：不占号，保存时才取。设计师没编号、今天流水满了回 1000，消息说清楚怎么办 */
  codePreview: (styleClass: string, designerId: string | null) =>
    http.get<string>(`${base}/code-preview`, { styleClass, designerId: designerId ?? undefined }, { silent: true }),
  /** 选款式的弹窗：启用的款式（includeDraft 时草稿也列，录加工价用），关键字查款号、名称、花名、品牌；品类含下级（登录即可） */
  pick: (params: PageParams & { keyword?: string; categoryId?: string; includeDraft?: boolean }) =>
    http.get<PageResult<StylePickRow>>(`${base}/pick`, params),
  create: ({ images, files, ...data }: StyleCreateForm) =>
    http.post<StyleCreateResult>(base, { ...data, images: attachmentBody(images), files: attachmentBody(files) }),
  update: (id: string, data: StyleForm) => http.put<void>(`${base}/${id}`, data),
  remove: (id: string, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
  enable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/enable`, undefined, options),
  disable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/disable`, undefined, options),
  replaceImages: (id: string, files: FileVO[]) => http.put<Attachment[]>(`${base}/${id}/images`, attachmentBody(files)),
  replaceFiles: (id: string, files: FileVO[]) => http.put<Attachment[]>(`${base}/${id}/files`, attachmentBody(files)),

  // ---------- 颜色、SKU ----------
  addColors: (id: string, colorIds: string[]) => http.post<void>(`${base}/${id}/colors`, { colorIds }),
  /** 传这个款上全部颜色的新顺序 */
  reorderColors: (id: string, colorIds: string[]) => http.put<void>(`${base}/${id}/colors/order`, { colorIds }),
  enableColor: (id: string, colorId: string) => http.post<void>(`${base}/${id}/colors/${colorId}/enable`),
  disableColor: (id: string, colorId: string) => http.post<void>(`${base}/${id}/colors/${colorId}/disable`),
  /** 只有还没生成 SKU 的颜色能去掉 */
  removeColor: (id: string, colorId: string) => http.delete<void>(`${base}/${id}/colors/${colorId}`),
  skus: (id: string) => http.get<StyleSku[]>(`${base}/${id}/skus`),
  /** 按 启用的颜色 × 尺码组 补齐没有的 SKU，返回新生成了几个 */
  generateSkus: (id: string) => http.post<number>(`${base}/${id}/skus/generate`),
  updateSku: (id: string, skuId: string, data: { barcode: string | null; tagPrice: number | null }) =>
    http.put<void>(`${base}/${id}/skus/${skuId}`, data),
  removeSku: (id: string, skuId: string) => http.delete<void>(`${base}/${id}/skus/${skuId}`),
  enableSku: (id: string, skuId: string) => http.post<void>(`${base}/${id}/skus/${skuId}/enable`),
  disableSku: (id: string, skuId: string) => http.post<void>(`${base}/${id}/skus/${skuId}/disable`),
}
