import { http, upload, type RequestOptions } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type { DocLog } from '@/types/biz'
import type {
  BomDefault, BomDefaultReq, BomImport, ExcelBook, PackFile, StyleCostQuery, StyleCostRow,
  TechPackCost, TechPackDetail, TechPackOverview, TechPackSaveForm,
} from '@/types/techpack'
import type { PageParams } from './system'

/**
 * 资料包（docs/api/01-资料包.md）。没有自己的列表页：在款式详情里当页签（/styles/:id?tab=techpack&techPackId=&sub=）。
 * 权限码：看 techpack:pack:list，新建 / 修订 techpack:pack:add，改草稿 :edit，删草稿 :remove，生效 :publish；
 * 核价、导入时带单价和加工费要 techpack:cost:query
 */
const base = '/api/tech-packs'

export const techPackApi = {
  /** 一个款的全部版本、默认选哪版、能不能新建、新颜色未配料、资料包核价 */
  overview: (styleId: string) => http.get<TechPackOverview>(base, { styleId }),
  /** 一个版本：单头 + 物料清单、工艺单文件（核价另取） */
  get: (id: string) => http.get<TechPackDetail>(`${base}/${id}`),
  /** 新建下一版草稿（生产 BOM），返回 id。已经有草稿、款式停用了回 1000 */
  create: (styleId: string) => http.post<string>(base, { styleId }),
  /** 保存草稿：整版覆盖，返回新的 versionLock（"有修改时点生效"接着用它生效）。不是草稿了、versionLock 对不上回 1002 */
  save: (id: string, form: TechPackSaveForm) =>
    http.put<number>(`${base}/${id}`, {
      ...form,
      files: form.files.map(({ category, url, thumbUrl, name, size }) => ({ category, url, thumbUrl, name, size })),
    }),
  /** 删草稿（已生效、已作废的只能看） */
  remove: (id: string) => http.delete<void>(`${base}/${id}`),
  /**
   * 生效。检查没通过回 1000，data 是 PublishProblem[]（一次列全）：调的时候传 { silent: true }，
   * 出错自己用 showBizError 弹清单（照供应商"申请准入"的写法）
   */
  publish: (id: string, versionLock: number, options?: RequestOptions) => http.post<void>(`${base}/${id}/publish`, { versionLock }, options),
  /** 修订：复制出下一版草稿，返回新 id */
  revise: (id: string) => http.post<string>(`${base}/${id}/revise`),
  /** 几行 BOM 的默认值（供应商、损耗、来源、参考单价……），按请求的顺序返回 */
  bomDefaults: (id: string, items: BomDefaultReq[], options?: RequestOptions) => http.post<BomDefault[]>(`${base}/${id}/bom-defaults`, { items }, { ...options, readOnly: true }),
  /** 核价页签，colorId 不给 = 款式的第一个颜色。没有 techpack:cost:query 回 403 */
  cost: (id: string, colorId?: string) => http.get<TechPackCost>(`${base}/${id}/cost`, { colorId }),
  /** 这个版本的操作记录，新的在前 */
  logs: (id: string) => http.get<DocLog[]>(`${base}/${id}/logs`),
  /**
   * 从甲方的 BOM 表认出物料清单（只解析、不写库；只有草稿能调）。sheet 用第几张（从 0 起），不给 = 按款号、款名自动对；
   * picks 是人工挑的物料：Excel 行号 → 物料 id。文件打不开、认不出表头回 1000
   */
  bomImport: (id: string, file: File, sheet?: number | null, picks?: Record<number, string>) => {
    const query = new URLSearchParams()
    if (sheet != null) query.set('sheet', String(sheet))
    const pairs = Object.entries(picks ?? {}).map(([line, materialId]) => `${line}:${materialId}`)
    if (pairs.length) query.set('picks', pairs.join(','))
    const qs = query.toString()
    return upload<BomImport>(`${base}/${id}/bom-import${qs ? '?' + qs : ''}`, file)
  },
  /** 一个版本的工艺单文件（生产单详情上发工厂用） */
  files: (id: string) => http.get<PackFile[]>(`${base}/${id}/files`),
  /** 工艺单页签上一个 Excel 的表格数据。不是 Excel 回 1000 */
  preview: (id: string, fileId: string) => http.get<ExcelBook>(`${base}/${id}/files/${fileId}/preview`),
}

/** 按款看成本（/costing，要 techpack:cost:query） */
export const styleCostApi = {
  page: (params: PageParams & StyleCostQuery) => http.get<PageResult<StyleCostRow>>('/api/style-costs', params),
}
