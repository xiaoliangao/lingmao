// basic 域（基础资料）的返回，和后端 module/basic/dto、entity 一一对应。字段名、可能为 null 的地方都照后端写。
// 数量、单价这些 NUMERIC 列后端发的是 JSON 数字，显示一律走 utils/format.ts
import type { Attachment } from '@/types/biz'

/** 主数据的状态：1 启用 0 停用 */
export type EnableStatus = 0 | 1

/** 主体公司（bas_company） */
export interface Company {
  id: string
  code: string
  name: string
  shortName: string
  creditCode: string | null
  address: string | null
  phone: string | null
  status: EnableStatus
  updateTime: string
}

/** 主体公司下拉（GET /api/companies/options，登录即可） */
export interface CompanyOption {
  id: string
  code: string
  shortName: string
}

export interface Color {
  id: string
  /** 2-3 位，进 SKU 码，建好不改 */
  code: string
  name: string
  /** 色系，字典 color_family */
  family: string | null
  /** #RRGGBB */
  hex: string | null
  status: EnableStatus
}

export interface ColorOption {
  id: string
  code: string
  name: string
  hex: string | null
}

/** 品类、物料分类的树（物料分类的二级有 code，品类没有）。后端不发 null 字段：顶层没有 parentId */
export interface TreeNode {
  id: string
  parentId?: string | null
  code?: string | null
  name: string
  level: number
  sort: number
  status: EnableStatus
  children?: TreeNode[]
}

export interface SizeGroup {
  id: string
  name: string
  baseSize: string
  status: EnableStatus
  /** 按顺序 */
  sizes: string[]
}

export type WarehouseType = 'MATERIAL' | 'PRODUCT' | 'FACTORY'

export const WAREHOUSE_TYPE_LABEL: Record<WarehouseType, string> = {
  MATERIAL: '物料仓',
  PRODUCT: '成品仓',
  FACTORY: '工厂仓',
}

export interface Warehouse {
  id: string
  code: string
  name: string
  type: WarehouseType
  /** 工厂仓绑的供应商 */
  factoryId: string | null
  factoryName: string | null
  address: string | null
  keeperId: string | null
  keeperName: string | null
  phone: string | null
  status: EnableStatus
  remark: string | null
  updateTime: string
}

export interface WarehouseOption {
  id: string
  code: string
  name: string
  type: WarehouseType
  factoryId: string | null
}

/** 物料的默认来源。CUSTOMER_SUPPLIED（客供）是代工用的，代工开关关着时不收 */
export type MaterialSource = 'PURCHASE' | 'FACTORY' | 'CUSTOMER_SUPPLIED'

export const MATERIAL_SOURCE_LABEL: Record<MaterialSource, string> = {
  PURCHASE: '自购',
  FACTORY: '厂购',
  CUSTOMER_SUPPLIED: '客供',
}

/** 物料列表的一行（MaterialListVO） */
export interface MaterialRow {
  id: string
  code: string
  name: string
  categoryId: string
  categoryCode: string
  categoryName: string
  parentCategoryName: string | null
  materialType: string
  stockUnit: string
  purchaseUnit: string
  conversionFactor: number
  widthCm: number | null
  weightGsm: number | null
  composition: string | null
  defaultSupplierId: string | null
  defaultSupplierName: string | null
  supplierItemNo: string | null
  refPrice?: number | null
  status: EnableStatus
  skuCount: number
  updateTime: string
  image: Attachment | null
}

/** 物料本身（bas_material） */
export interface Material {
  id: string
  code: string
  name: string
  categoryId: string
  materialType: string
  stockUnit: string
  purchaseUnit: string
  conversionFactor: number
  widthCm: number | null
  weightGsm: number | null
  composition: string | null
  texture: string | null
  defaultSupplierId: string | null
  supplierItemNo: string | null
  refPrice?: number | null
  moq: number | null
  minPackQty: number
  leadDays: number | null
  year: number | null
  season: string | null
  defaultSource: MaterialSource
  defaultLossPct: number | null
  reservePct: number | null
  status: EnableStatus
  remark: string | null
  /** 领猫导入的附加属性，原样存着，只看 */
  legacyAttrs: Record<string, string> | null
  createTime: string
  updateTime: string
}

export interface MaterialSku {
  id: string
  code: string
  colorId: string | null
  colorCode: string | null
  colorName: string | null
  colorHex: string | null
  spec: string | null
  specCode: string | null
  supplierColorNo: string | null
  supplierItemNo: string | null
  /** 空 = 和物料的参考单价一样 */
  price?: number | null
  safetyStock: number | null
  status: EnableStatus
  remark: string | null
  image: Attachment | null
}

export interface MaterialDetail {
  material: Material
  categoryCode: string
  categoryName: string
  parentCategoryName: string | null
  defaultSupplierName: string | null
  skus: MaterialSku[]
  images: Attachment[]
}

/** 选物料 SKU 弹窗（DataPick）的一行：启用物料的启用 SKU。price 是 SKU 单价，空的用物料参考单价；image 是 SKU 图，没有用物料主图 */
export interface MaterialPickRow {
  skuId: string
  skuCode: string
  materialId: string
  materialCode: string
  materialName: string
  materialType: string
  categoryId: string
  categoryName: string
  colorId: string | null
  colorCode: string | null
  colorName: string | null
  colorHex: string | null
  spec: string | null
  stockUnit: string
  purchaseUnit: string
  conversionFactor: number
  price?: number | null
  image: Attachment | null
}

/** 建档查重的一条（只提示不拦） */
export interface MaterialDup {
  id: string
  code: string
  name: string
  supplierItemNo: string | null
  defaultSupplierName: string | null
  /** 同分类同名 / 同供应商同货号 */
  reason: string
}
