import { http, type RequestOptions } from '@/utils/request'
import type { DictOption, FileVO, PageResult } from '@/types/system'
import type { Attachment, DocLog } from '@/types/biz'
import type {
  Color, ColorOption, Company, CompanyOption, MaterialDetail, MaterialDup, MaterialPickRow, MaterialRow, MaterialSku, MaterialSource, SizeGroup,
  TreeNode, Warehouse, WarehouseOption, WarehouseType,
} from '@/types/basic'
import type { PageParams } from './system'

/**
 * 基础资料的接口（RFC 003）。启用、停用、删除每种资源都一样，最后一个参数都收 RequestOptions：
 * 批量操作条（BatchBar）逐条调时传 { silent: true }，出错由它汇总
 */
function statusApi(base: string) {
  return {
    enable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/enable`, undefined, options),
    disable: (id: string, options?: RequestOptions) => http.post<void>(`${base}/${id}/disable`, undefined, options),
    remove: (id: string, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
  }
}

/** 附件整组替换时传回去的样子（去掉 id） */
export function attachmentBody(files: FileVO[]) {
  return files.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size }))
}

// ---------- 主体公司 ----------

export interface CompanyForm {
  code: string
  name: string
  shortName: string
  creditCode?: string | null
  address?: string | null
  phone?: string | null
}

export const companyApi = {
  list: () => http.get<Company[]>('/api/companies'),
  /** 启用的主体，下拉用（登录即可） */
  options: () => http.get<CompanyOption[]>('/api/companies/options'),
  create: (data: CompanyForm) => http.post<string>('/api/companies', data),
  update: (id: string, data: CompanyForm) => http.put<void>(`/api/companies/${id}`, data),
  ...statusApi('/api/companies'),
}

// ---------- 颜色 ----------

export interface ColorForm {
  code: string
  name: string
  family?: string | null
  hex?: string | null
}

export const colorApi = {
  page: (params: PageParams & { keyword?: string; status?: number; family?: string }) =>
    http.get<PageResult<Color>>('/api/colors', params),
  /** 启用的颜色，下拉、选颜色用（登录即可） */
  options: () => http.get<ColorOption[]>('/api/colors/options'),
  create: (data: ColorForm) => http.post<string>('/api/colors', data),
  update: (id: string, data: ColorForm) => http.put<void>(`/api/colors/${id}`, data),
  ...statusApi('/api/colors'),
}

// ---------- 品类、物料分类（树） ----------

export interface CategoryForm {
  parentId: string | null
  name: string
  sort: number
  /** 物料分类：2 位字母或数字，二级必填、一级选填，同一上级下不重复（RFC 018 §8）。品类选填：1-8 位字母数字，款号规则里有"品类编码"一段时用 */
  code?: string | null
}

export const categoryApi = {
  /** 全部，停用的也在（基础页用） */
  tree: () => http.get<TreeNode[]>('/api/categories'),
  /** 只有启用的（下拉用，登录即可） */
  enabledTree: () => http.get<TreeNode[]>('/api/categories/tree'),
  create: (data: CategoryForm) => http.post<string>('/api/categories', data),
  update: (id: string, data: CategoryForm) => http.put<void>(`/api/categories/${id}`, data),
  ...statusApi('/api/categories'),
}

export const materialCategoryApi = {
  tree: () => http.get<TreeNode[]>('/api/material-categories'),
  enabledTree: () => http.get<TreeNode[]>('/api/material-categories/tree'),
  create: (data: CategoryForm) => http.post<string>('/api/material-categories', data),
  update: (id: string, data: CategoryForm) => http.put<void>(`/api/material-categories/${id}`, data),
  ...statusApi('/api/material-categories'),
}

// ---------- 尺码组 ----------

export const sizeGroupApi = {
  list: () => http.get<SizeGroup[]>('/api/size-groups'),
  options: () => http.get<SizeGroup[]>('/api/size-groups/options'),
  /** sizes：空格、逗号、顿号隔开；baseSize 不填取中间那个 */
  create: (data: { name: string; sizes: string; baseSize?: string | null }) => http.post<string>('/api/size-groups', data),
  rename: (id: string, name: string) => http.put<void>(`/api/size-groups/${id}`, { name }),
  /** 只能往后追加，已有的尺码不能改、不能删（款式按位置存着它） */
  appendSizes: (id: string, sizes: string) => http.post<void>(`/api/size-groups/${id}/sizes`, { sizes }),
  ...statusApi('/api/size-groups'),
}

// ---------- 仓库 ----------

export interface WarehouseForm {
  code: string
  name: string
  type: WarehouseType
  address?: string | null
  keeperId?: string | null
  phone?: string | null
  remark?: string | null
}

export const warehouseApi = {
  page: (params: PageParams & { keyword?: string; type?: WarehouseType; status?: number }) =>
    http.get<PageResult<Warehouse>>('/api/warehouses', params),
  options: (type?: WarehouseType) => http.get<WarehouseOption[]>('/api/warehouses/options', { type }),
  create: (data: WarehouseForm) => http.post<string>('/api/warehouses', data),
  update: (id: string, data: WarehouseForm) => http.put<void>(`/api/warehouses/${id}`, data),
  ...statusApi('/api/warehouses'),
}

// ---------- 物料 ----------

export interface MaterialQuery {
  keyword?: string
  categoryId?: string
  materialType?: string
  status?: number
  /** 导出选中的 */
  ids?: string[]
}

/** 新建、修改物料共用（修改时分类、库存单位要和原来一样，后端不让改） */
export interface MaterialForm {
  name: string
  categoryId: string | null
  materialType: string
  stockUnit: string
  purchaseUnit?: string | null
  conversionFactor?: number | null
  widthCm?: number | null
  weightGsm?: number | null
  composition?: string | null
  texture?: string | null
  /** 面料功能；物料类型不是面料、里料时后端不存 */
  fabricFunction?: string | null
  withTag?: boolean | null
  /** 功能吊牌的名称；withTag 是 true 时必填，不是 true 时后端不存 */
  tagNames?: string | null
  defaultSupplierId?: string | null
  supplierItemNo?: string | null
  refPrice?: number | null
  moq?: number | null
  minPackQty?: number | null
  leadDays?: number | null
  year?: number | null
  season?: string | null
  defaultSource?: MaterialSource | null
  defaultLossPct?: number | null
  reservePct?: number | null
  remark?: string | null
}

export interface SkuSpec {
  spec?: string | null
  /** 不填由规格算：去掉空格和末尾的 cm、mm、码，转大写 */
  specCode?: string | null
}

export interface SkuForm {
  supplierColorNo?: string | null
  supplierItemNo?: string | null
  price?: number | null
  safetyStock?: number | null
  remark?: string | null
}

export const materialApi = {
  page: (params: PageParams & MaterialQuery, options?: RequestOptions) => http.get<PageResult<MaterialRow>>('/api/materials', params, options),
  get: (id: string) => http.get<MaterialDetail>(`/api/materials/${id}`),
  logs: (id: string) => http.get<DocLog[]>(`/api/materials/${id}/logs`),
  /** 选 SKU 的弹窗：启用物料的启用 SKU。关键字查 SKU 码、物料编码、名称、货号、颜色名；分类含下级 */
  pick: (params: PageParams & { keyword?: string; categoryId?: string }) =>
    http.get<PageResult<MaterialPickRow>>('/api/materials/pick', params),
  /**
   * 新建物料的编码预览（RFC 018 §4.6）：按适用于这个物料类型的编码规则，只看不取，保存时以真取到的为准。
   * 分类要是二级；规则编不出来回 1000（静默，表单上写原因）
   */
  codePreview: (categoryId: string, materialType: string) =>
    http.get<string>('/api/materials/code-preview', { categoryId, materialType }, { silent: true }),
  /** 建档查重：同分类同名、同默认供应商同货号，只提示不拦 */
  dupCheck: (params: { categoryId?: string | null; name?: string; defaultSupplierId?: string | null; supplierItemNo?: string | null; excludeId?: string }) =>
    http.get<MaterialDup[]>('/api/materials/dup-check', params),
  /** 颜色 × 规格两两组合建 SKU；都不选就是一个不带颜色、规格的 SKU */
  create: (data: MaterialForm & { colorIds: string[]; specs: SkuSpec[] }) => http.post<string>('/api/materials', data),
  update: (id: string, data: MaterialForm) => http.put<void>(`/api/materials/${id}`, data),
  /** 新建、编辑物料时当场加一个物料类型（字典 material_type 的一项），返回新选项；名称重复 1000。备料比不填 = 3 */
  addType: (data: { label: string; reservePct: number | null }) => http.post<DictOption>('/api/materials/types', data),
  replaceImages: (id: string, files: FileVO[]) => http.put<Attachment[]>(`/api/materials/${id}/images`, attachmentBody(files)),
  addSkus: (id: string, data: { colorIds: string[]; specs: SkuSpec[] }) => http.post<MaterialSku[]>(`/api/materials/${id}/skus`, data),
  updateSku: (id: string, skuId: string, data: SkuForm) => http.put<void>(`/api/materials/${id}/skus/${skuId}`, data),
  removeSku: (id: string, skuId: string) => http.delete<void>(`/api/materials/${id}/skus/${skuId}`),
  enableSku: (id: string, skuId: string) => http.post<void>(`/api/materials/${id}/skus/${skuId}/enable`),
  disableSku: (id: string, skuId: string) => http.post<void>(`/api/materials/${id}/skus/${skuId}/disable`),
  replaceSkuImages: (id: string, skuId: string, files: FileVO[]) =>
    http.put<Attachment[]>(`/api/materials/${id}/skus/${skuId}/images`, attachmentBody(files)),
  ...statusApi('/api/materials'),
}
