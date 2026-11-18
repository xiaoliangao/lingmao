// style 域（款式）的返回，和后端 module/style/dto 一一对应（RFC 005）。字段名、可能为 null 的地方都照后端写
import type { Attachment } from '@/types/biz'
import type { ColorOption, EnableStatus } from '@/types/basic'

export type StyleStatus = 'DRAFT' | 'ENABLED' | 'DISABLED'

/** 状态的叫法和标签颜色（StatusTag 的 tone） */
export const STYLE_STATUS: Record<StyleStatus, { label: string; tone: 'active' | 'pending' | 'off' }> = {
  DRAFT: { label: '草稿', tone: 'pending' },
  ENABLED: { label: '启用', tone: 'active' },
  DISABLED: { label: '停用', tone: 'off' },
}

/** 资料包状态：第 3 批资料包做了以后由它回写，现在都是 NONE */
export type TechPackStatus = 'NONE' | 'DEV' | 'PROD'

export const TECH_PACK_STATUS: Record<TechPackStatus, { label: string; tone: 'off' | 'progress' | 'done' }> = {
  NONE: { label: '无', tone: 'off' },
  DEV: { label: '研发中', tone: 'progress' },
  PROD: { label: '已生效', tone: 'done' },
}

/** 详情页的按钮（StyleDetailVO.actions） */
export type StyleAction = 'enable' | 'disable' | 'edit' | 'remove'

/** 列表的一行 */
export interface StyleRow {
  id: string
  code: string
  name: string
  aliasName: string | null
  /** 字典 style_class，款号开头两位 */
  styleClass: string
  /** 字典 brand 的 value */
  brand: string
  categoryId: string
  categoryName: string
  year: number
  season: string
  wave: string | null
  designerId: string | null
  designerName: string | null
  sizeGroupId: string
  sizeGroupName: string
  sizeCount: number
  colorCount: number
  skuCount: number
  tagPrice: number | null
  status: StyleStatus
  techPackStatus: TechPackStatus
  updateTime: string
  /** 主图，没有是 null */
  image: Attachment | null
  /** 款式上启用的颜色，按顺序 */
  colors: ColorOption[]
}

/** 款式上的一个颜色。status 是在这个款上停没停用，colorStatus 是基础资料里的颜色停没停用；skuCount > 0 的只能停用 */
export interface StyleColor {
  colorId: string
  code: string
  name: string
  hex: string | null
  seq: number
  status: EnableStatus
  colorStatus: EnableStatus
  skuCount: number
}

export interface StyleSku {
  id: string
  /** 款号-颜色编码-尺码 */
  code: string
  colorId: string
  colorCode: string
  colorName: string
  colorHex: string | null
  sizeCode: string
  barcode: string | null
  /** 空 = 用款式的吊牌价 */
  tagPrice: number | null
  status: EnableStatus
}

export interface StyleDetail {
  id: string
  code: string
  name: string
  aliasName: string | null
  styleClass: string
  brand: string
  categoryId: string
  categoryName: string
  /** 女装/上衣/衬衫 */
  categoryPath: string
  year: number
  season: string
  wave: string | null
  styleTag: string | null
  series: string | null
  gender: string | null
  designerId: string | null
  designerName: string | null
  patternMakerId: string | null
  patternMakerName: string | null
  source: string
  customerId: string | null
  customerStyleNo: string | null
  sampleNo: string | null
  sizeGroupId: string
  sizeGroupName: string
  sizes: string[]
  mainMaterialId: string | null
  mainMaterialCode: string | null
  mainMaterialName: string | null
  productName: string | null
  composition: string | null
  execStandard: string | null
  safetyCategory: string | null
  tagPrice: number | null
  refCost: number | null
  defaultFactoryId: string | null
  defaultFactoryCode: string | null
  /** 简称 */
  defaultFactoryName: string | null
  /** yyyy-MM-dd */
  launchDate: string | null
  status: StyleStatus
  techPackStatus: TechPackStatus
  remark: string | null
  /** 领猫导入带过来的附加属性（等级、版型库单号……），只看 */
  legacyAttrs: Record<string, string> | null
  createBy: string | null
  createByName: string | null
  createTime: string
  updateTime: string
  colors: StyleColor[]
  skuCount: number
  images: Attachment[]
  /** 设计附件：图稿、设计说明 */
  files: Attachment[]
  actions: StyleAction[]
}

/** 选款式的弹窗（DataPick kind="style"）的一行：启用的款式 */
export interface StylePickRow {
  id: string
  code: string
  name: string
  /** 只列启用的；带 includeDraft 时也有草稿 */
  status: StyleStatus
  aliasName: string | null
  brand: string
  categoryId: string
  categoryName: string
  year: number
  season: string
  tagPrice: number | null
  techPackStatus: TechPackStatus
  image: Attachment | null
  colors: ColorOption[]
}

export interface StyleCreateResult {
  id: string
  code: string
  skuCount: number
}
