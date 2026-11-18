// techpack 域（资料包、核价、工艺标准）的返回，和后端 module/techpack/dto 对应（docs/api/01-资料包.md）。
// 字段名照数据库设计 §9 的列名转 camelCase；可能为 null 的地方都写了。用量、单价、金额是后端的 BigDecimal，到前端是 JSON 数字
import type { Attachment, StatusTone } from '@/types/biz'
import type { ColorOption, EnableStatus, MaterialSource } from '@/types/basic'
import type { FileVO } from '@/types/system'

// ---------------------------------------------------------------- 枚举和叫法

/** 阶段（tp_tech_pack.stage）：研发 BOM 只估价；生产 BOM 发工厂、算料，需求订单只认它（PRD §6.4.1） */
export type PackStage = 'DEV' | 'PROD'

/** 原型里研发是琥珀色小标签、生产是蓝色小标签；阶段不是状态，页面上用普通的 el-tag 就行 */
export const PACK_STAGE: Record<PackStage, { label: string; short: string }> = {
  DEV: { label: '研发 BOM', short: '研发' },
  PROD: { label: '生产 BOM', short: '生产' },
}

/** 版本的叫法：研发的带"研发"两个字（"研发 V1"），生产的只写版本号（"V2"），和原型 tpName 一样 */
export function packName(p: { stage: PackStage; version: number }): string {
  return (p.stage === 'DEV' ? '研发 ' : '') + 'V' + p.version
}

/** 版本状态：草稿能改；生效后只读，要改点"修订"；同款同阶段新版本生效时，旧的自动变已作废（还能看、能导出） */
export type PackStatus = 'DRAFT' | 'EFFECTIVE' | 'VOIDED'

export const PACK_STATUS: Record<PackStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  EFFECTIVE: { label: '已生效', tone: 'done' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * 资料包详情的按钮（后端按"状态 ∩ 权限 ∩ 业务条件"算好）：
 * edit 改草稿、remove 删草稿、publish 生效（草稿）；revise 修订、toProd 转生产 BOM（已生效，且同阶段 / 生产阶段还没有草稿）。
 * 导出 PDF 不在这里：能看资料包就能导，"含价格"看 techpack:cost:query
 */
export type TechPackAction = 'edit' | 'remove' | 'publish' | 'revise' | 'toProd'

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

/** 核价按什么算（tp_tech_pack.cost_mode）：按外发 = 加工费取工序工价合计；按成品采购 = 加工费取成品单价（含料） */
export type CostMode = 'OUTSOURCE' | 'BUY'

export const COST_MODE: Record<CostMode, string> = {
  OUTSOURCE: '按外发算',
  BUY: '按成品采购算',
}

/** 工艺指示的段（tp_craft_section.section）。段代码固定九个，PDF 的版式按它排，所以是枚举不是字典 */
export type CraftSection = 'CUTTING' | 'SEWING' | 'PART' | 'QUALITY' | 'BUTTON' | 'IRONING' | 'LABEL' | 'PACKING' | 'NOTE'

export const CRAFT_SECTION_LABEL: Record<CraftSection, string> = {
  CUTTING: '裁剪要求',
  SEWING: '缝制要求',
  PART: '部位工艺细则',
  QUALITY: '做工品质',
  BUTTON: '锁钉要求',
  IRONING: '整烫说明',
  LABEL: '洗唛 / 烫标',
  PACKING: '包装要求',
  NOTE: '注意事项',
}

/** 页面上分三块（照甲方线下工艺单，原型 CRAFT_SECS）；包装要求那一段上面多一个"包装方式"单选（字典 packing_mode） */
export const CRAFT_GROUPS: { title: string; sections: CraftSection[] }[] = [
  { title: '车间缝制', sections: ['CUTTING', 'SEWING', 'PART', 'QUALITY'] },
  { title: '后道', sections: ['BUTTON', 'IRONING', 'LABEL', 'PACKING'] },
  { title: '注意事项', sections: ['NOTE'] },
]

/** 资料包操作记录里的动作叫法（DocLogList 的 actions）；状态叫法用 PACK_STATUS 的 label */
export const TECH_PACK_LOG_ACTIONS: Record<string, string> = {
  create: '新建',
  update: '保存',
  revise: '修订',
  'to-prod': '转生产 BOM',
  publish: '生效',
  void: '被新版本替代',
  'export-pdf': '导出 PDF',
}

// ---------------------------------------------------------------- 版本列表（款式详情的资料包页签顶上）

/** 一个版本的要紧几项 */
export interface TechPackBrief {
  id: number
  stage: PackStage
  version: number
  status: PackStatus
  /** 本版说明，V2 起生效前必填 */
  versionNote: string | null
  /** 生产 BOM 的研发来源（转生产 BOM 时记下，修订时照抄），页面显示"来源：研发 V1"；研发 BOM、直接建的生产 BOM 是 null */
  sourcePackId: number | null
  sourceVersion: number | null
  publishByName: string | null
  /** 2026-11-15 10:30:00；没生效过是 null */
  publishTime: string | null
  createByName: string | null
  createTime: string
  updateTime: string
}

/** GET /api/tech-packs?styleId=：一个款的资料包概况。资料包页签、款式页顶上的"新颜色未配料"提示、基本信息里的"资料包核价"都用它 */
export interface TechPackOverview {
  styleId: number
  /** 全部版本：研发在前、生产在后，各自新版本在前 */
  packs: TechPackBrief[]
  /** 打开页签默认选哪一版：生产草稿 > 生产已生效 > 研发草稿 > 研发已生效；一个版本都没有是 null */
  defaultPackId: number | null
  /** 能新建哪个阶段：有 techpack:pack:add、款式没停用、这个阶段还没有草稿。空数组 = 不出"建生产 BOM""建研发 BOM"按钮 */
  creatableStages: PackStage[]
  /**
   * 已生效的生产 BOM 里"随款色""随款色和尺码"的行，还没给这些款色配料（款式后来加的颜色）。
   * 款式页顶上提示"新颜色 酒红 还没配料，订单不能下这个颜色"（PRD §6.2 规则 4）；没有生效的生产 BOM 时是空数组
   */
  unmatchedColors: ColorOption[]
  /** 资料包核价：生产已生效版的单件成本，没有就取研发已生效版。没有看成本权限、两个阶段都没生效是 null */
  unitCost: number | null
  unitCostPackId: number | null
  unitCostStage: PackStage | null
}

// ---------------------------------------------------------------- 详情的四个页签

/** 尺寸指示的一行（tp_spec_point + tp_spec_value） */
export interface SpecPoint {
  id: number
  seq: number
  /** 部位：从字典 size_point 选或手填，存的是文字不是字典 value */
  point: string
  /** 测量方法：选部位时默认带工艺标准里这个部位的测量方法，可改 */
  method: string | null
  /** 公差 ±cm */
  toleranceCm: number | null
  /** 档差 cm */
  gradeCm: number | null
  remark: string | null
  /** 尺码 → 尺寸 cm；没填的格子没有这个键 */
  values: Record<string, number>
}

/** 保存时一行尺寸指示。顺序 = 数组顺序（拖动排序就是改数组） */
export type SpecPointForm = Omit<SpecPoint, 'id' | 'seq'>

/**
 * BOM 行实际用的一个物料 SKU（tp_bom_item_sku）。四种配料方式都在这里：
 * 固定 1 条（colorId、sizeCode 都是 null）；随款色每个款色 1 条；随尺码每个尺码 1 条；随款色和尺码每个款色 × 尺码 1 条
 */
export interface BomSku {
  /** 款色（款式的颜色）；固定、随尺码的是 null */
  colorId: number | null
  colorCode: string | null
  colorName: string | null
  colorHex: string | null
  /** 尺码；固定、随款色的是 null */
  sizeCode: string | null
  materialSkuId: number
  skuCode: string
  /** 物料 SKU 自己的颜色、规格，和物料名拼成"220g 斜纹棉-卡其" */
  skuColorName: string | null
  skuSpec: string | null
  /** 物料 SKU 停用了：页面标红，生效时会被拦 */
  skuStatus: EnableStatus
  /** 这个 SKU 单独的参考单价，元 / 库存单位；null = 用行上的（一种拉链三个长度三个价就落在这里） */
  refPrice: number | null
  /** SKU 图，没有用物料主图，都没有是 null */
  image: Attachment | null
}

export interface BomSkuForm {
  colorId: number | null
  sizeCode: string | null
  materialSkuId: number
  refPrice: number | null
}

/** 物料清单的一行（tp_bom_item） */
export interface BomItem {
  id: number
  seq: number
  /** 部位，字典 bom_part */
  part: string
  materialId: number
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
  /** 供应商；工厂自备的行一般是 null */
  supplierId: number | null
  /** 简称 */
  supplierName: string | null
  /** 参考单价，元 / 库存单位（价格档案、物料单价是按采购单位的，带过来时已经换算好）；没有是 null */
  refPrice: number | null
  /** 默认单价取自哪条价格档案；手改了也留着 */
  priceSourceId: number | null
  priceTaxIncluded: boolean
  priceFreightIncluded: boolean
  remark: string | null
  skus: BomSku[]
  /**
   * 单件成本 = 基码用量 ×（1 + 损耗率）× 单价；随款色的行取第一个款色的 SKU 单价，随尺码的行取基码那个 SKU 的。
   * 工厂自备、客供的行，或者算不出来（没单价、没用量）是 null
   */
  unitCost: number | null
  /** 缩略图：第一个配料 SKU 的图，没有用物料主图 */
  image: Attachment | null
}

/** 保存时一行 BOM。单位不用传，后端取物料的库存单位存一份 */
export interface BomItemForm {
  part: string
  materialId: number
  skuMode: BomSkuMode
  usageMode: BomUsageMode
  usageQty: number | null
  sizeUsages: Record<string, number>
  lossPct: number
  source: MaterialSource
  supplierId: number | null
  refPrice: number | null
  priceSourceId: number | null
  priceTaxIncluded: boolean
  priceFreightIncluded: boolean
  remark: string | null
  skus: BomSkuForm[]
}

/** 加一行、换物料、换供应商、换配料时，问后端这一行该带什么默认值（POST /api/tech-packs/{id}/bom-defaults 的一项） */
export interface BomDefaultReq {
  materialId: number
  /** 页面上选了的供应商；null = 用物料的默认供应商 */
  supplierId: number | null
  /** 这一行配到的物料 SKU（去重）；还没配是 [] */
  skuIds: number[]
}

/**
 * 一行 BOM 的默认值（按请求的顺序一一对应）。单价规则（PRD §6.4.3）：
 * 生产 BOM 先取价格档案里这家供应商的现价，没有带物料 SKU 单价，再没有带物料参考单价；研发 BOM 不看价格档案。都换算成元 / 库存单位
 */
export interface BomDefault {
  materialId: number
  /** 物料的库存单位、采购单位、换算系数（1 采购单位 = n 库存单位），页面上提示"按采购单位是 xx 元/公斤"用 */
  unit: string
  purchaseUnit: string
  conversionFactor: number
  /** 请求里给了就是它，没给就是物料的默认供应商（可能是 null） */
  supplierId: number | null
  supplierName: string | null
  /** 物料的默认损耗率，没设是 0 */
  lossPct: number
  /** 物料的默认物料来源 */
  source: MaterialSource
  /** 行上的默认参考单价，元 / 库存单位；都没有是 null */
  refPrice: number | null
  priceSourceId: number | null
  priceTaxIncluded: boolean
  priceFreightIncluded: boolean
  /** 默认单价从哪来：ARCHIVE 价格档案现价 / SKU 物料 SKU 单价 / MATERIAL 物料参考单价 / NONE 都没有 */
  priceFrom: 'ARCHIVE' | 'SKU' | 'MATERIAL' | 'NONE'
  /** 单价和行上不一样的 SKU（价格档案里单独定了价、SKU 有自己的单价），写进配料的 refPrice；和行上一样的不在这里 */
  skuPrices: { materialSkuId: number; refPrice: number }[]
}

/** 工艺指示的一段。九段都会返回，没写的 content 是 '' */
export interface CraftSectionView {
  section: CraftSection
  content: string
  /** 插图（biz_attachment，category = 段代码），按顺序 */
  images: Attachment[]
}

export interface CraftSectionForm {
  section: CraftSection
  content: string | null
  /** 整组替换这一段的插图 */
  images: FileVO[]
}

/**
 * 工序工价的一行（tp_process），在工艺指示页签最下面，标"内部"。
 * 图示存在 tp_process_image，只有地址：返回时 name 是文件名（从地址里取的）、size 是 0，方便直接给 ImageUpload 用
 */
export interface ProcessRow {
  id: number
  seq: number
  /** 工序类别，字典 process_category */
  category: string | null
  name: string
  requirement: string | null
  /** 标准工时，秒 */
  stdSeconds: number | null
  /** 工价，元 / 件 */
  price: number | null
  remark: string | null
  images: FileVO[]
}

export type ProcessForm = Omit<ProcessRow, 'id' | 'seq'>

/** GET /api/tech-packs/{id}：一个版本的单头和尺寸指示、物料清单、工艺指示三个页签。核价另取（要看成本的权限） */
export interface TechPackDetail {
  /** 颜色尺码取自哪里：LIVE 草稿取款式现在的，SNAPSHOT 生效时记下的，LEGACY 升级前生效的老版本 */
  contextSource: 'LIVE' | 'SNAPSHOT' | 'LEGACY'
  id: number
  styleId: number
  styleCode: string
  styleName: string
  /** 花名 */
  aliasName: string | null
  stage: PackStage
  version: number
  status: PackStatus
  versionNote: string | null
  sourcePackId: number | null
  sourceVersion: number | null
  /** 排版利用率 %，只记录 */
  utilPct: number | null
  /** 包装方式，字典 packing_mode */
  packingMode: string | null
  publishBy: number | null
  publishByName: string | null
  publishTime: string | null
  createByName: string | null
  createTime: string
  updateTime: string
  /** 乐观锁：保存草稿、生效都带回去，对不上 1002（tp_tech_pack 的 version 是版本号，所以锁叫这个名字） */
  versionLock: number
  /** 款式尺码组的尺码，按顺序；尺寸指示、分码用量、随尺码配料按它出列 */
  sizes: string[]
  /** 尺码组的基码：尺寸指示里标出来，单件成本按它的用量算 */
  baseSize: string
  /** 本版次的颜色集合，按顺序；随款色配料按它出行，"按颜色"视图、导出 PDF 按它选 */
  colors: ColorOption[]
  /** 尺寸指示选部位的下拉：字典 size_point 启用的项和它的默认测量方法（工艺标准里填的）。只读的版本也给，数量不多 */
  pointOptions: { label: string; method: string | null }[]
  specPoints: SpecPoint[]
  bomItems: BomItem[]
  craftSections: CraftSectionView[]
  processes: ProcessRow[]
  /** 工序工价合计：总工时（秒）、参考加工价（元 / 件） */
  stdSecondsTotal: number
  laborTotal: number
  /** 单件成本（和核价页签的合计一样）。没有看成本权限是 null，页面不显示 */
  unitCost: number | null
  actions: TechPackAction[]
}

// ---------------------------------------------------------------- 核价（要 techpack:cost:query）

/** 核价的一行物料（从物料清单算出来的，不存） */
export interface CostRow {
  bomItemId: number
  seq: number
  part: string
  materialId: number
  materialName: string
  /** 按这次算的颜色取到的物料 SKU；没配料是 null */
  materialSkuId: number | null
  skuCode: string | null
  skuColorName: string | null
  skuSpec: string | null
  source: MaterialSource
  /** 基码用量（统一用量的行就是统一用量），库存单位 */
  usageQty: number
  unit: string
  lossPct: number
  /** 用的单价，元 / 库存单位：配料上单独的价 > 行上的价；都没有是 null */
  price: number | null
  priceTaxIncluded: boolean
  priceFreightIncluded: boolean
  /** 计不计入合计：只有我方采购的行计入；工厂自备的显示"含在加工价里"，客供的显示"客户出" */
  counted: boolean
  /** 单件成本；不计入的是 0 */
  cost: number
  /** 研发估：研发版里同部位、同物料那一行的单件成本；研发版没有这一行是 null。没有研发版（dev 为 null）时不用看 */
  devCost: number | null
}

/** 核价的"其他费用"一项（tp_cost_item）：印绣花外加工、运费、包装这类按件摊的钱 */
export interface CostItem {
  id: number
  seq: number
  name: string
  /** 元 / 件 */
  amount: number
  remark: string | null
  /** 研发版里同名那一项的金额；没有是 null */
  devAmount: number | null
}

export interface CostItemForm {
  name: string
  amount: number
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
  techPackId: number
  baseSize: string
  /** 这次按哪个颜色算的（随款色的行各颜色单价可能不一样）：请求没给就是款式的第一个颜色；款式没有颜色是 null */
  colorId: number | null
  costMode: CostMode
  /** 存着的成品单价，元 / 件；没填是 null */
  buyPrice: number | null
  /**
   * 价格档案里款式默认加工厂做这个款"包工包料"的现价；款式没设默认加工厂、档案里没有是 null。
   * 按成品采购算且没填成品单价时加工费用它；按外发算时页面显示成一行"成品采购参考价"，不计入
   */
  buyPriceDefault: number | null
  defaultFactoryId: number | null
  defaultFactoryName: string | null
  fabricRows: CostRow[]
  trimRows: CostRow[]
  /** 工序工价合计（不管按什么算都给，加工费那块的说明里显示"n 道工序"） */
  laborTotal: number
  processCount: number
  otherItems: CostItem[]
  summary: CostSummary
  /** 款式的吊牌价；倍率 = 吊牌价 ÷ 单件成本，两位小数；没吊牌价或单件成本是 0 时 ratio 是 null */
  tagPrice: number | null
  ratio: number | null
  /** 参考单价的口径不一样（有的含税有的不含、有的含运有的不含）：照录入的数加总，页面上标出来（PRD §6.4.5） */
  mixedBasis: boolean
  /** 生产版有研发来源时的"研发估"一列；研发版本身、没有来源的生产版是 null */
  dev: { packId: number; version: number; summary: CostSummary } | null
}

/** 保存时的核价部分：没有看成本权限的人发 null（见 TechPackSaveForm.cost） */
export interface TechPackCostForm {
  costMode: CostMode
  /** 成品单价，元 / 件；按外发算时也照存 */
  buyPrice: number | null
  items: CostItemForm[]
}

// ---------------------------------------------------------------- 保存、生效、导出

/** 保存草稿（PUT /api/tech-packs/{id}）：整版覆盖，单头可改的几项 + 四个页签的全部行 */
export interface TechPackSaveForm {
  versionLock: number
  versionNote: string | null
  utilPct: number | null
  packingMode: string | null
  specPoints: SpecPointForm[]
  bomItems: BomItemForm[]
  /** 九段都发，没写的 content 发 null、images 发 [] */
  craftSections: CraftSectionForm[]
  processes: ProcessForm[]
  /** 核价的手填部分。null = 这次不动核价（没有看成本权限的人一律发 null）；有权限的人发整份 */
  cost: TechPackCostForm | null
}

/** 生效没通过（1000）时 data 里的一条：row 是物料清单第几行（从 1 起），0 说的是整个资料包（如"V2 起要写本版说明"） */
export interface PublishProblem {
  row: number
  message: string
}

/** 导出 PDF 的选项（GET /api/tech-packs/{id}/pdf） */
export interface PdfOptions {
  /** 只出这个颜色的物料清单；不给 = 全部颜色 */
  colorId?: number
  /** 含价格：出参考单价、单件成本、核价和工序工价。要 techpack:cost:query，没有发 true 回 403 */
  price?: boolean
  /** 附工艺标准文件，默认 true */
  appendix?: boolean
}

// ---------------------------------------------------------------- 按款看成本（/costing）

/** 一个款一行：取生产已生效版，没有取研发已生效版；两个都没有的款不列 */
export interface StyleCostRow {
  styleId: number
  styleCode: string
  styleName: string
  aliasName: string | null
  image: Attachment | null
  techPackId: number
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
  categoryId?: number | null
  /** 字典 brand */
  brand?: string
  year?: number | null
  /** 字典 season */
  season?: string
  /** 只看取到的是研发版还是生产版的 */
  stage?: PackStage
}

// ---------------------------------------------------------------- 工艺标准（/api/craft-standards，资料包页签上的抽屉 CraftStandardDrawer）

/** 字典 size_point 的一项和它的默认测量方法（sys_dict_data.text_value） */
export interface SizePointStd {
  /** 字典 value；页面新加的部位 value 就是名称 */
  value: string
  label: string
  method: string | null
  /** 字典项停用了的也列出来（灰掉），尺寸指示里选不到 */
  status: EnableStatus
}

/** GET /api/craft-standards：工艺标准的全部内容 */
export interface CraftStandard {
  /** 工艺标准文件（PDF 或图片），按顺序；导出 PDF 勾"附工艺标准"时按这个顺序接在最后，name 就是附页的标题 */
  files: Attachment[]
  /** 工艺指示各段的默认文字，九段都有，没写是 '' */
  texts: Record<CraftSection, string>
  /** 新建资料包时默认的包装方式，字典 packing_mode；没设是 null */
  packingMode: string | null
  points: SizePointStd[]
  /** 能不能改（有 techpack:standard:edit） */
  editable: boolean
}

export interface CraftTextsForm {
  /** 九段都发，没写的发 null */
  texts: Record<CraftSection, string | null>
  packingMode: string | null
}

export interface SizePointMethodForm {
  value: string
  method: string | null
}
