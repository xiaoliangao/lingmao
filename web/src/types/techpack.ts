// techpack 域（资料包、核价、BOM 表导入、工艺单预览）的返回，和后端 module/techpack/dto 对应（docs/api/01-资料包.md）。
// 字段名照数据库设计 §9 的列名转 camelCase；可能为 null 的地方都写了。用量、单价、金额是后端的 BigDecimal，到前端是 JSON 数字
import type { Attachment, StatusTone } from '@/types/biz'
import type { ColorOption, EnableStatus, MaterialSource } from '@/types/basic'

// ---------------------------------------------------------------- 枚举和叫法

/**
 * 阶段（tp_tech_pack.stage）：一期只有生产 BOM，发工厂、算料，需求订单锁它（PRD §6.4.1）。
 * 研发不填物料清单（甲方 11-18 答 Q24），二期生产单自己的资料包才有别的阶段；页面上不显示阶段
 */
export type PackStage = 'PROD'

/** 版本的叫法："V2" */
export function packName(p: { version: number }): string {
  return 'V' + p.version
}

/** 版本状态：草稿能改；生效后只读，要改点"修订"；新版本生效时，旧的自动变已作废（还能看） */
export type PackStatus = 'DRAFT' | 'EFFECTIVE' | 'VOIDED'

export const PACK_STATUS: Record<PackStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  EFFECTIVE: { label: '已生效', tone: 'done' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * 资料包详情的按钮（后端按"状态 ∩ 权限 ∩ 业务条件"算好）：
 * edit 改草稿（含传工艺单、从 BOM 表导入）、remove 删草稿、publish 生效（草稿）；revise 修订（已生效，且还没有草稿）。
 * 工艺单的下载、预览不在这里：能看资料包就能看
 */
export type TechPackAction = 'edit' | 'remove' | 'publish' | 'revise'

/** 配料方式（tp_bom_item.sku_mode）：用哪个物料 SKU 怎么定 */
export type BomSkuMode = 'FIXED' | 'BY_COLOR' | 'BY_SIZE' | 'BY_COLOR_SIZE'

export const BOM_SKU_MODE: Record<BomSkuMode, string> = {
  FIXED: '固定',
  BY_COLOR: '随款色',
  BY_SIZE: '随尺码',
  BY_COLOR_SIZE: '随款色和尺码',
}

/** 用量方式（tp_bom_item.usage_mode）：统一 = 所有尺码一个用量；分码 = 每个尺码一个 */
export type BomUsageMode = 'UNIFORM' | 'BY_SIZE'

export const BOM_USAGE_MODE: Record<BomUsageMode, string> = {
  UNIFORM: '统一',
  BY_SIZE: '分码',
}

/** 核价按什么算（tp_tech_pack.cost_mode）：按外发 = 加工费取资料包上的加工费；按成品采购 = 加工费取成品单价（含料） */
export type CostMode = 'OUTSOURCE' | 'BUY'

export const COST_MODE: Record<CostMode, string> = {
  OUTSOURCE: '按外发算',
  BUY: '按成品采购算',
}

/**
 * 工艺单页签的文件分组（biz_attachment.category，RFC 011 §2）：工艺单、纸样和其他、导入的 BOM 表。
 * 顺序就是页面上的顺序；types 是这一组收的扩展名（后端同样查一遍）
 */
export type PackFileCategory = 'CRAFT_SHEET' | 'PATTERN' | 'BOM_SOURCE'

const SHEET_TYPES = ['xls', 'xlsx', 'pdf', 'jpg', 'jpeg', 'png', 'webp']

export const PACK_FILE_GROUPS: { category: PackFileCategory; label: string; types: string[] }[] = [
  { category: 'CRAFT_SHEET', label: '工艺单', types: SHEET_TYPES },
  { category: 'PATTERN', label: '纸样和其他', types: [...SHEET_TYPES, 'prj'] },
  { category: 'BOM_SOURCE', label: '导入的 BOM 表', types: ['xls', 'xlsx'] },
]

/** 一组最多挂几个 */
export const PACK_FILE_MAX = 20

/** 资料包操作记录里的动作叫法（DocLogList 的 actions）；状态叫法用 PACK_STATUS 的 label */
export const TECH_PACK_LOG_ACTIONS: Record<string, string> = {
  create: '新建',
  update: '保存',
  revise: '修订',
  publish: '生效',
  void: '被新版本替代',
  /** 11-18 去掉了导出 PDF，老记录里还有 */
  'export-pdf': '导出 PDF',
}

// ---------------------------------------------------------------- 版本列表（款式详情的资料包页签顶上）

/** 一个版本的要紧几项 */
export interface TechPackBrief {
  id: string
  stage: PackStage
  version: number
  status: PackStatus
  /** 本版说明，V2 起生效前必填 */
  versionNote: string | null
  publishByName: string | null
  /** 2026-11-15 10:30:00；没生效过是 null */
  publishTime: string | null
  createByName: string | null
  createTime: string
  updateTime: string
}

/** GET /api/tech-packs?styleId=：一个款的资料包概况。资料包页签、款式页顶上的"新颜色未配料"提示、基本信息里的"资料包核价"都用它 */
export interface TechPackOverview {
  styleId: string
  /** 全部版本，新版本在前 */
  packs: TechPackBrief[]
  /** 打开页签默认选哪一版：草稿 > 已生效 > 最新的一版；一个版本都没有是 null */
  defaultPackId: string | null
  /** 能不能新建：有 techpack:pack:add、款式没停用、还没有草稿。一个版本都没有时才出"建生产 BOM"按钮，有了就点"修订" */
  creatable: boolean
  /**
   * 已生效的生产 BOM 里"随款色""随款色和尺码"的行，还没给这些款色配料（款式后来加的颜色）。
   * 款式页顶上提示"新颜色 酒红 还没配料，订单不能下这个颜色"（PRD §6.2 规则 4）；没有生效的生产 BOM 时是空数组
   */
  unmatchedColors: ColorOption[]
  /** 资料包核价：已生效版的单件成本，unitCostPackId 是那一版。没有看成本权限、还没有生效的版本是 null */
  unitCost?: number | null
  unitCostPackId: string | null
}

// ---------------------------------------------------------------- 详情（物料清单、工艺单两个页签；核价另取）

/**
 * BOM 行实际用的一个物料 SKU（tp_bom_item_sku）。四种配料方式都在这里：
 * 固定 1 条（colorId、sizeCode 都是 null）；随款色每个款色 1 条；随尺码每个尺码 1 条；随款色和尺码每个款色 × 尺码 1 条
 */
export interface BomSku {
  /** 款色（款式的颜色）；固定、随尺码的是 null */
  colorId: string | null
  colorCode: string | null
  colorName: string | null
  colorHex: string | null
  /** 尺码；固定、随款色的是 null */
  sizeCode: string | null
  materialSkuId: string
  skuCode: string
  /** 物料 SKU 自己的颜色、规格，和物料名拼成"220g 斜纹棉-卡其" */
  skuColorName: string | null
  skuSpec: string | null
  /** 物料 SKU 停用了：页面标红，生效时会被拦 */
  skuStatus: EnableStatus
  /** 这个 SKU 单独的参考单价，元 / 库存单位；null = 用行上的（一种拉链三个长度三个价就落在这里） */
  refPrice?: number | null
  /** SKU 图，没有用物料主图，都没有是 null */
  image: Attachment | null
}

export interface BomSkuForm {
  colorId: string | null
  sizeCode: string | null
  materialSkuId: string
  refPrice?: number | null
}

/** 物料清单的一行（tp_bom_item） */
export interface BomItem {
  id: string
  seq: number
  /** 部位，字典 bom_part */
  part: string
  materialId: string
  materialCode: string
  materialName: string
  /** 字典 material_type；FABRIC 面料、LINING 里料进核价的"面料"块，别的进"辅料" */
  materialType: string
  /** 物料停用了：页面标红，生效时会被拦 */
  materialStatus: EnableStatus
  skuMode: BomSkuMode
  usageMode: BomUsageMode
  /** 统一用量（库存单位 / 件）；分码的行是 null */
  usageQty: number | null
  /** 分码用量：尺码 → 用量；统一的行是 {} */
  sizeUsages: Record<string, number>
  /** 用量单位 = 物料的库存单位，字典 unit */
  unit: string
  /** 损耗率 %，3 表示 3% */
  lossPct: number
  source: MaterialSource
  /** 供应商；厂购的行一般是 null */
  supplierId: string | null
  /** 简称 */
  supplierName: string | null
  /** 参考单价，元 / 库存单位（价格档案、物料单价是按采购单位的，带过来时已经换算好）；没有是 null */
  refPrice?: number | null
  /** 默认单价取自哪条价格档案；手改了也留着 */
  priceSourceId: string | null
  priceTaxIncluded?: boolean
  priceFreightIncluded?: boolean
  remark: string | null
  skus: BomSku[]
  /**
   * 单件成本 = 基码用量 ×（1 + 损耗率）× 单价；随款色的行取第一个款色的 SKU 单价，随尺码的行取基码那个 SKU 的。
   * 厂购、客供的行，或者算不出来（没单价、没用量）是 null
   */
  unitCost?: number | null
  /** 缩略图：第一个配料 SKU 的图，没有用物料主图 */
  image: Attachment | null
}

/** 保存时一行 BOM。单位不用传，后端取物料的库存单位存一份 */
export interface BomItemForm {
  /** 已有行的 id；本地新加的行不带。 */
  id?: string
  part: string
  materialId: string
  skuMode: BomSkuMode
  usageMode: BomUsageMode
  usageQty: number | null
  sizeUsages: Record<string, number>
  lossPct: number
  source: MaterialSource
  supplierId: string | null
  refPrice?: number | null
  priceSourceId: string | null
  priceTaxIncluded?: boolean
  priceFreightIncluded?: boolean
  remark: string | null
  skus: BomSkuForm[]
}

/** 加一行、换物料、换供应商、换配料时，问后端这一行该带什么默认值（POST /api/tech-packs/{id}/bom-defaults 的一项） */
export interface BomDefaultReq {
  materialId: string
  /** 页面上选了的供应商；null = 用物料的默认供应商 */
  supplierId: string | null
  /** 这一行配到的物料 SKU（去重）；还没配是 [] */
  skuIds: string[]
}

/**
 * 一行 BOM 的默认值（按请求的顺序一一对应）。单价规则（PRD §6.4.3）：
 * 先取价格档案里这家供应商的现价，没有带物料 SKU 单价，再没有带物料参考单价。都换算成元 / 库存单位
 */
export interface BomDefault {
  materialId: string
  /** 物料的库存单位、采购单位、换算系数（1 采购单位 = n 库存单位），页面上提示"按采购单位是 xx 元/公斤"用 */
  unit: string
  purchaseUnit: string
  conversionFactor: number
  /** 请求里给了就是它，没给就是物料的默认供应商（可能是 null） */
  supplierId: string | null
  supplierName: string | null
  /** 物料的默认损耗率，没设是 0 */
  lossPct: number
  /** 物料的默认物料来源 */
  source: MaterialSource
  /** 行上的默认参考单价，元 / 库存单位；都没有是 null */
  refPrice?: number | null
  priceSourceId: string | null
  priceTaxIncluded?: boolean
  priceFreightIncluded?: boolean
  /** 默认单价从哪来：ARCHIVE 价格档案现价 / SKU 物料 SKU 单价 / MATERIAL 物料参考单价 / NONE 都没有 */
  priceFrom: 'ARCHIVE' | 'SKU' | 'MATERIAL' | 'NONE'
  /** 单价和行上不一样的 SKU（价格档案里单独定了价、SKU 有自己的单价），写进配料的 refPrice；和行上一样的不在这里 */
  skuPrices?: { materialSkuId: string; refPrice: number }[]
}

/** 工艺单页签的一个文件（biz_attachment）。url 带签名，直接下载；id 是预览用的 */
export interface PackFile {
  id: string
  category: PackFileCategory
  url: string
  thumbUrl: string | null
  name: string
  size: number
}

/** 保存时一个文件：先 upload 到 /api/files 拿 FileVO，再带上组。组里的顺序 = 数组里的顺序 */
export interface PackFileForm {
  /** 已经存在资料包上的有 id（预览要它），刚传的还没有；保存时不发 */
  id?: string
  category: PackFileCategory
  url: string
  thumbUrl: string | null
  name: string
  size: number
}

/** GET /api/tech-packs/{id}：一个版本的单头、物料清单、工艺单文件。核价另取（要看成本的权限） */
export interface TechPackDetail {
  /** 颜色尺码取自哪里：LIVE 草稿取款式现在的，SNAPSHOT 生效时记下的，LEGACY 升级前生效的老版本 */
  contextSource: 'LIVE' | 'SNAPSHOT' | 'LEGACY'
  id: string
  styleId: string
  styleCode: string
  styleName: string
  /** 花名 */
  aliasName: string | null
  stage: PackStage
  version: number
  status: PackStatus
  versionNote: string | null
  /** 排版利用率 %，只记录 */
  utilPct: number | null
  publishBy: string | null
  publishByName: string | null
  publishTime: string | null
  createByName: string | null
  createTime: string
  updateTime: string
  /** 乐观锁：保存草稿、生效都带回去，对不上 1002（tp_tech_pack 的 version 是版本号，所以锁叫这个名字） */
  versionLock: number
  /** 款式尺码组的尺码，按顺序；分码用量、随尺码配料按它出列 */
  sizes: string[]
  /** 尺码组的基码：单件成本按它的用量算 */
  baseSize: string
  /** 本版次的颜色集合，按顺序；随款色配料按它出行，"按颜色"视图按它选 */
  colors: ColorOption[]
  bomItems: BomItem[]
  /** 工艺单页签的文件，按组（PACK_FILE_GROUPS 的顺序）、组里按排的顺序 */
  files: PackFile[]
  /** 单件成本（和核价页签的合计一样）。没有看成本权限时字段省略，页面不显示 */
  unitCost?: number | null
  actions: TechPackAction[]
}

// ---------------------------------------------------------------- 核价（要 techpack:cost:query）

/** 核价的一行物料（从物料清单算出来的，不存） */
export interface CostRow {
  bomItemId: string
  seq: number
  part: string
  materialId: string
  materialName: string
  /** 按这次算的颜色取到的物料 SKU；没配料是 null */
  materialSkuId: string | null
  skuCode: string | null
  skuColorName: string | null
  skuSpec: string | null
  source: MaterialSource
  /** 基码用量（统一用量的行就是统一用量），库存单位 */
  usageQty: number
  unit: string
  lossPct: number
  /** 用的单价，元 / 库存单位：配料上单独的价 > 行上的价；都没有是 null */
  price?: number | null
  priceTaxIncluded?: boolean
  priceFreightIncluded?: boolean
  /** 计不计入合计：只有自购的行计入；厂购的显示"含在加工价里"，客供的显示"客户出" */
  counted: boolean
  /** 单件成本；不计入的是 0 */
  cost?: number
}

/** 核价的"其他费用"一项（tp_cost_item）：印绣花外加工、运费、包装这类按件摊的钱 */
export interface CostItem {
  id: string
  seq: number
  name: string
  /** 元 / 件 */
  amount?: number
  remark: string | null
}

export interface CostItemForm {
  name: string
  amount?: number
  remark: string | null
}

/** 四块和合计（元 / 件，两位小数） */
export interface CostSummary {
  fabric: number
  trim: number
  labor: number
  other: number
  total: number
}

/** GET /api/tech-packs/{id}/cost?colorId=：核价页签（PRD §6.4.5、架构 §4.9）。都是打开时现算的 */
export interface TechPackCost {
  techPackId: string
  baseSize: string
  /** 这次按哪个颜色算的（随款色的行各颜色单价可能不一样）：请求没给就是款式的第一个颜色；款式没有颜色是 null */
  colorId: string | null
  costMode: CostMode
  /** 存着的成品单价，元 / 件；没填是 null */
  buyPrice?: number | null
  /**
   * 价格档案里款式默认加工厂做这个款"包工包料"的现价；款式没设默认加工厂、档案里没有是 null。
   * 按成品采购算且没填成品单价时加工费用它；按外发算时页面显示成一行"成品采购参考价"，不计入
   */
  buyPriceDefault: number | null
  defaultFactoryId: string | null
  defaultFactoryName: string | null
  fabricRows: CostRow[]
  trimRows: CostRow[]
  /** 存着的加工费，元 / 件（按外发算时用）；没填是 null */
  laborPrice?: number | null
  /**
   * 价格档案里款式默认加工厂做这个款的清加工现价，没有取半包；都没有是 null。
   * 按外发算且没填加工费时用它；laborPriceDefaultMode 说是哪种（字典 process_mode：CMT、HALF）
   */
  laborPriceDefault?: number | null
  laborPriceDefaultMode?: string | null
  otherItems: CostItem[]
  summary: CostSummary
  /** 款式的吊牌价；倍率 = 吊牌价 ÷ 单件成本，两位小数；没吊牌价或单件成本是 0 时 ratio 是 null */
  tagPrice: number | null
  ratio: number | null
  /** 参考单价的口径不一样（有的含税有的不含、有的含运有的不含）：照录入的数加总，页面上标出来（PRD §6.4.5） */
  mixedBasis: boolean
}

/** 保存时的核价部分：没有看成本权限的人发 null（见 TechPackSaveForm.cost） */
export interface TechPackCostForm {
  costMode: CostMode
  /** 成品单价，元 / 件；按外发算时也照存 */
  buyPrice?: number | null
  /** 加工费，元 / 件；按成品采购算时也照存 */
  laborPrice?: number | null
  items: CostItemForm[]
}

// ---------------------------------------------------------------- 保存、生效

/** 保存草稿（PUT /api/tech-packs/{id}）：整版覆盖，单头可改的几项 + 物料清单的全部行 + 工艺单文件 */
export interface TechPackSaveForm {
  versionLock: number
  versionNote: string | null
  utilPct: number | null
  bomItems: BomItemForm[]
  /** 工艺单页签的全部文件，按组整组替换；放错组、一组超过 20 个回 1000 */
  files: PackFileForm[]
  /** 核价的手填部分。null = 这次不动核价（没有看成本权限的人省略该字段）；有权限的人发整份 */
  cost: TechPackCostForm | null
}

/** 生效没通过（1000）时 data 里的一条：row 是物料清单第几行（从 1 起），0 说的是整个资料包（如"V2 起要写本版说明"） */
export interface PublishProblem {
  row: number
  message: string
}

// ---------------------------------------------------------------- 从 BOM 表导入（POST /api/tech-packs/{id}/bom-import）

/** 导入预览一行的状态：OK 认出来了，CHECK 能导但要看一下（notes），UNMATCHED 没认出物料，要先挑或者新建 */
export type BomImportStatus = 'OK' | 'CHECK' | 'UNMATCHED'

export const BOM_IMPORT_STATUS: Record<BomImportStatus, { label: string; tone: StatusTone }> = {
  OK: { label: '认出来了', tone: 'done' },
  CHECK: { label: '要看一下', tone: 'pending' },
  UNMATCHED: { label: '没认出来', tone: 'danger' },
}

/** 导入结果：只解析、不写库。页面上改过、点"加进物料清单"以后和手填的一样走保存 */
export interface BomImport {
  /** 文件里的每一张 sheet；bom = false 的不能选（找不到表头） */
  sheets: { index: number; name: string; title: string | null; bom: boolean }[]
  /** 用的是哪一张（自动对上的或者指定的） */
  sheet: number
  rows: BomImportRow[]
  /** 汇总块的加工费；没写、没有看成本权限是 null */
  laborPrice?: number | null
  /** 汇总块里的运费，带成核价的其他费用；没有看成本权限是 [] */
  fees: { name: string; amount: number }[]
}

export interface BomImportRow {
  /** Excel 里的行号，从 1 起；人工挑物料时 picks 用它 */
  line: number
  /** 表里原样写的字。price 没有看成本权限时是 null */
  source: {
    section: string
    name: string
    spec: string
    color: string
    price: string | null
    unit: string
    usage: string
    loss: string
    supplier: string
    remark: string
  }
  /** 认出来的物料行，和详情里的 bomItems 一个样子，只是没有 id；没认出物料是 null */
  item: Omit<BomItem, 'id' | 'seq' | 'unitCost' | 'image'> | null
  status: BomImportStatus
  notes: string[]
}

// ---------------------------------------------------------------- 工艺单 Excel 预览（GET /api/tech-packs/{id}/files/{fileId}/preview）

/** 一份 Excel 的表格数据：后端用 POI 读、前端画（RFC 011 §2.3）。格子的字一律当文本画 */
export interface ExcelBook {
  sheets: ExcelSheet[]
  /** 超出 20 个没读的 sheet 数 */
  moreSheets: number
}

export interface ExcelSheet {
  name: string
  /** 在 Excel 里是隐藏的 */
  hidden: boolean
  /** 每列宽、每行高，像素；隐藏的是 0 */
  colWidths: number[]
  rowHeights: number[]
  /** 有字、或者有底色边框的格子；r、c 从 0 起，v 是 Excel 里显示的字，s 是 styles 的下标 */
  cells: { r: number; c: number; v: string; s: number | null }[]
  styles: ExcelStyle[]
  /** 合并单元格：左上角 r、c，占 rs 行 cs 列 */
  merges: { r: number; c: number; rs: number; cs: number }[]
  /** 图片：左上角 x、y 和宽高（像素），data 是 data:image/...;base64,... */
  images: { x: number; y: number; w: number; h: number; data: string }[]
  /** 超出 500 行、60 列没画的行数、列数；没画的图片张数 */
  moreRows: number
  moreCols: number
  skippedImages: number
}

/** 格子样式：fs 字号（磅）总有，其余只出和默认不一样的项 */
export interface ExcelStyle {
  b: boolean | null
  i: boolean | null
  fs: number | null
  /** 字色、底色 #rrggbb */
  fc: string | null
  bg: string | null
  ha: 'left' | 'center' | 'right' | null
  va: 'top' | 'middle' | 'bottom' | null
  wrap: boolean | null
  /** 四边有没有线 */
  bt: boolean | null
  br: boolean | null
  bb: boolean | null
  bl: boolean | null
}

// ---------------------------------------------------------------- 按款看成本（/costing）

/** 一个款一行：取已生效的生产 BOM；没有的款不列 */
export interface StyleCostRow {
  styleId: string
  styleCode: string
  styleName: string
  aliasName: string | null
  image: Attachment | null
  techPackId: string
  stage: PackStage
  version: number
  fabric: number
  trim: number
  labor: number
  other: number
  total: number
  tagPrice: number | null
  /** 吊牌价 ÷ 单件成本，两位小数；没吊牌价或单件成本是 0 时 null */
  ratio: number | null
}

export interface StyleCostQuery {
  /** 款号、名称、花名；一次查几个（README §3.2）时款号精确查 */
  keyword?: string
  /** 品类，含下级 */
  categoryId?: string | null
  /** 字典 brand */
  brand?: string
  year?: number | null
  /** 字典 season */
  season?: string
}
