import { download, http, type RequestOptions } from '@/utils/request'
import type { FileVO, PageResult } from '@/types/system'
import type { Attachment, DocLog } from '@/types/biz'
import type {
  BomDefault, BomDefaultReq, CraftStandard, CraftTextsForm, PackStage, PdfOptions, SizePointMethodForm, StyleCostQuery, StyleCostRow,
  TechPackCost, TechPackDetail, TechPackOverview, TechPackSaveForm, TechPackOwner,
} from '@/types/techpack'
import { attachmentBody } from './basic'
import type { PageParams } from './system'

/**
 * 资料包（docs/api/01-资料包.md）。没有自己的列表页：在款式详情里当页签（/styles/:id?tab=techpack&techPackId=&sub=）。
 * 权限码：看 techpack:pack:list，新建 / 修订 / 转生产 techpack:pack:add，改草稿 :edit，删草稿 :remove，生效 :publish；
 * 核价、PDF 含价格要 techpack:cost:query
 */
const base = '/api/tech-packs'

export const techPackApi = {
  /** 一个款的全部版本、默认选哪版、能建哪个阶段、新颜色未配料、资料包核价 */
  overview: (styleId: string, options?: RequestOptions) => http.get<TechPackOverview>(base, { styleId }, options),
  sampleOverview: (sampleId: string, companyId: string, params: { beforeVersion?: number; size?: number }, options?: RequestOptions) =>
    http.get<TechPackOverview>(base, { sampleId, companyId, ...params }, { ...options, companyId }),
  /** 一个版本：单头 + 尺寸指示、物料清单、工艺指示（核价另取） */
  get: (id: string, options?: RequestOptions) => http.get<TechPackDetail>(`${base}/${id}`, undefined, options),
  /** 新建一个阶段的下一版草稿，返回 id。这个阶段已经有草稿、款式停用了回 1000 */
  create: (styleId: string, stage: PackStage, options?: RequestOptions) => http.post<string>(base, { styleId, stage }, options),
  createForOwner: (owner: TechPackOwner, stage: PackStage, options?: RequestOptions) => owner.kind === 'STYLE'
    ? http.post<string>(base, { styleId: owner.styleId, stage }, options)
    : http.post<string>(base, { sampleId: owner.sampleId, companyId: owner.companyId, stage }, { ...options, companyId: owner.companyId }),
  /** 保存草稿：整版覆盖，返回新的 versionLock（"有修改时点生效"接着用它生效）。不是草稿了、versionLock 对不上回 1002 */
  save: (id: string, form: TechPackSaveForm, options?: RequestOptions) =>
    http.put<number>(`${base}/${id}`, {
      ...form,
      craftSections: form.craftSections.map((s) => ({ ...s, images: attachmentBody(s.images) })),
      processes: form.processes.map((p) => ({ ...p, images: attachmentBody(p.images) })),
    }, options),
  /** 删草稿（已生效、已作废的只能看） */
  remove: (id: string, options?: RequestOptions) => http.delete<void>(`${base}/${id}`, options),
  /**
   * 生效。检查没通过回 1000，data 是 PublishProblem[]（一次列全）：调的时候传 { silent: true }，
   * 出错自己用 showBizError 弹清单（照供应商"申请准入"的写法）
   */
  publish: (id: string, versionLock: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/publish`, { versionLock }, options),
  /** 修订：复制出同阶段的下一版草稿，返回新 id */
  revise: (id: string, options?: RequestOptions) => http.post<string>(`${base}/${id}/revise`, undefined, options),
  /** 转生产 BOM：生效的研发版复制成生产版草稿，单价按价格档案重新取，返回新 id */
  toProd: (id: string, options?: RequestOptions) => http.post<string>(`${base}/${id}/to-prod`, undefined, options),
  /** 几行 BOM 的默认值（供应商、损耗、来源、参考单价……），按请求的顺序返回 */
  bomDefaults: (id: string, items: BomDefaultReq[], options?: RequestOptions) => http.post<BomDefault[]>(`${base}/${id}/bom-defaults`, { items }, { ...options, readOnly: true }),
  /** 核价页签，colorId 不给 = 款式的第一个颜色。没有 techpack:cost:query 回 403 */
  cost: (id: string, colorId?: string, options?: RequestOptions) => http.get<TechPackCost>(`${base}/${id}/cost`, { colorId }, options),
  /** 这个版本的操作记录，新的在前 */
  logs: (id: string, options?: RequestOptions) => http.get<DocLog[]>(`${base}/${id}/logs`, undefined, options),
  /** 导出工艺单 PDF。文件名后端给（款号-款名-工艺单-V版本.pdf），取不到用 fallbackName */
  pdf: (id: string, options: PdfOptions, fallbackName: string, requestOptions?: RequestOptions) => download(`${base}/${id}/pdf`, options, fallbackName, requestOptions),
}

/** 按款看成本（/costing，要 techpack:cost:query） */
export const styleCostApi = {
  page: (params: PageParams & StyleCostQuery) => http.get<PageResult<StyleCostRow>>('/api/style-costs', params),
}

/** 工艺标准（资料包页签上拉出的抽屉 CraftStandardDrawer，不单开一页）。看要 techpack:standard:list，改要 techpack:standard:edit */
export const craftStandardApi = {
  get: () => http.get<CraftStandard>('/api/craft-standards'),
  /** 工艺标准文件整组替换：数组顺序就是附页顺序，改 name 就是改附页标题 */
  replaceFiles: (files: FileVO[]) => http.put<Attachment[]>('/api/craft-standards/files', attachmentBody(files)),
  /** 工艺指示各段的默认文字、默认包装方式（整份覆盖） */
  saveTexts: (form: CraftTextsForm) => http.put<void>('/api/craft-standards/texts', form),
  /** 改几个部位的默认测量方法（只改传了的） */
  saveMethods: (points: SizePointMethodForm[]) => http.put<void>('/api/craft-standards/points', { points }),
  /** 加一个部位：进字典 size_point（value 就用名称）。同名的已经有了回 1000 */
  addPoint: (label: string, method: string | null) => http.post<void>('/api/craft-standards/points', { label, method }),
}
