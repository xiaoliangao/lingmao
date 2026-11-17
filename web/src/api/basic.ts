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
    enable: (id: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/enable`, undefined, options),
    disable: (id: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/disable`, undefined, options),
    remove: (id: number, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
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
  create: (data: CompanyForm) => http.post<number>('/api/companies', data),
  update: (id: number, data: CompanyForm) => http.put<void>(`/api/companies/${id}`, data),
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
  create: (data: ColorForm) => http.post<number>('/api/colors', data),
  update: (id: number, data: ColorForm) => http.put<void>(`/api/colors/${id}`, data),
  ...statusApi('/api/colors'),
}

// ---------- 品类、物料分类（树） ----------

export interface CategoryForm {
  parentId: number | null
  name: string
  sort: number
  /** 只有物料分类的二级有：2 位字母，物料编码的前缀 */
  code?: string | null
}

export const categoryApi = {
  /** 全部，停用的也在（基础页用） */
  tree: () => http.get<TreeNode[]>('/api/categories'),
  /** 只有启用的（下拉用，登录即可） */
  enabledTree: () => http.get<TreeNode[]>('/api/categories/tree'),
  create: (data: CategoryForm) => http.post<number>('/api/categories', data),
  update: (id: number, data: CategoryForm) => http.put<void>(`/api/categories/${id}`, data),
  ...statusApi('/api/categories'),
}

export const materialCategoryApi = {
  tree: () => http.get<TreeNode[]>('/api/material-categories'),
  enabledTree: () => http.get<TreeNode[]>('/api/material-categories/tree'),
  create: (data: CategoryForm) => http.post<number>('/api/material-categories', data),
  update: (id: number, data: CategoryForm) => http.put<void>(`/api/material-categories/${id}`, data),
  ...statusApi('/api/material-categories'),
}

// ---------- 尺码组 ----------

export const sizeGroupApi = {
  list: () => http.get<SizeGroup[]>('/api/size-groups'),
  options: () => http.get<SizeGroup[]>('/api/size-groups/options'),
  /** sizes：空格、逗号、顿号隔开；baseSize 不填取中间那个 */
  create: (data: { name: string; sizes: string; baseSize?: string | null }) => http.post<number>('/api/size-groups', data),
  rename: (id: number, name: string) => http.put<void>(`/api/size-groups/${id}`, { name }),
  /** 只能往后追加，已有的尺码不能改、不能删（款式按位置存着它） */
  appendSizes: (id: number, sizes: string) => http.post<void>(`/api/size-groups/${id}/sizes`, { sizes }),
  ...statusApi('/api/size-groups'),
}

// ---------- 仓库 ----------

export interface WarehouseForm {
  code: string
  name: string
  type: WarehouseType
  address?: string | null
  keeperId?: number | null
  phone?: string | null
  remark?: string | null
}

export const warehouseApi = {
  page: (params: PageParams & { keyword?: string; type?: WarehouseType; status?: number }) =>
    http.get<PageResult<Warehouse>>('/api/warehouses', params),
  options: (type?: WarehouseType) => http.get<WarehouseOption[]>('/api/warehouses/options', { type }),
  create: (data: WarehouseForm) => http.post<number>('/api/warehouses', data),
  update: (id: number, data: WarehouseForm) => http.put<void>(`/api/warehouses/${id}`, data),
  ...statusApi('/api/warehouses'),
}

// ---------- 物料 ----------

export interface MaterialQuery {
  keyword?: string
  categoryId?: number
  materialType?: string
  status?: number
  /** 导出选中的 */
  ids?: number[]
}

/** 新建、修改物料共用（修改时分类、库存单位要和原来一样，后端不让改） */
export interface MaterialForm {
  name: string
  categoryId: number | null
  materialType: string
  stockUnit: string
  purchaseUnit?: string | null
  conversionFactor?: number | null
  widthCm?: number | null
  weightGsm?: number | null
  composition?: string | null
  texture?: string | null
  defaultSupplierId?: number | null
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
  get: (id: number) => http.get<MaterialDetail>(`/api/materials/${id}`),
  logs: (id: number) => http.get<DocLog[]>(`/api/materials/${id}/logs`),
  /** 选 SKU 的弹窗：启用物料的启用 SKU。关键字查 SKU 码、物料编码、名称、货号、颜色名；分类含下级 */
  pick: (params: PageParams & { keyword?: string; categoryId?: number }) =>
    http.get<PageResult<MaterialPickRow>>('/api/materials/pick', params),
  /** 建档查重：同分类同名、同默认供应商同货号，只提示不拦 */
  dupCheck: (params: { categoryId?: number | null; name?: string; defaultSupplierId?: number | null; supplierItemNo?: string | null; excludeId?: number }) =>
    http.get<MaterialDup[]>('/api/materials/dup-check', params),
  /** 颜色 × 规格两两组合建 SKU；都不选就是一个不带颜色、规格的 SKU */
  create: (data: MaterialForm & { colorIds: number[]; specs: SkuSpec[] }) => http.post<number>('/api/materials', data),
  update: (id: number, data: MaterialForm) => http.put<void>(`/api/materials/${id}`, data),
  /** 新建、编辑物料时当场加一个物料类型（字典 material_type 的一项），返回新选项；名称重复 1000。备料比不填 = 3 */
  addType: (data: { label: string; reservePct: number | null }) => http.post<DictOption>('/api/materials/types', data),
  replaceImages: (id: number, files: FileVO[]) => http.put<Attachment[]>(`/api/materials/${id}/images`, attachmentBody(files)),
  addSkus: (id: number, data: { colorIds: number[]; specs: SkuSpec[] }) => http.post<MaterialSku[]>(`/api/materials/${id}/skus`, data),
  updateSku: (id: number, skuId: number, data: SkuForm) => http.put<void>(`/api/materials/${id}/skus/${skuId}`, data),
  removeSku: (id: number, skuId: number) => http.delete<void>(`/api/materials/${id}/skus/${skuId}`),
  enableSku: (id: number, skuId: number) => http.post<void>(`/api/materials/${id}/skus/${skuId}/enable`),
  disableSku: (id: number, skuId: number) => http.post<void>(`/api/materials/${id}/skus/${skuId}/disable`),
  replaceSkuImages: (id: number, skuId: number, files: FileVO[]) =>
    http.put<Attachment[]>(`/api/materials/${id}/skus/${skuId}/images`, attachmentBody(files)),
  ...statusApi('/api/materials'),
}
