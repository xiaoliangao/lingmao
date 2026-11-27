import type { Attachment, StatusTone } from './biz'
import type { FileVO } from './system'

/**
 * 质检（docs/api/10-质量.md，RFC 019 §6）。一次检验一条记录，不审核。
 * 回货的状态由记录推出来：没验 → 待检；不合格 → 安排返修 → 返修中 → 返修完成 → 待检（再验一次，第 2 次……）
 */

/** INCOMING 来料检验（采购入库单）/ PRE_PRODUCTION 产前样确认、MIDDLE 中检（生产单）/ FINAL 尾期验货（回货单） */
export type QcKind = 'INCOMING' | 'PRE_PRODUCTION' | 'MIDDLE' | 'FINAL'
export type QcResult = 'PASS' | 'CONCESSION' | 'FAIL'

export const QC_KIND: Record<QcKind, string> = {
  INCOMING: '来料检验',
  PRE_PRODUCTION: '产前样确认',
  MIDDLE: '中检',
  FINAL: '尾期验货',
}

export const QC_RESULT: Record<QcResult, { label: string; tone: StatusTone }> = {
  PASS: { label: '合格', tone: 'done' },
  CONCESSION: { label: '让步接收', tone: 'pending' },
  FAIL: { label: '不合格', tone: 'danger' },
}

/** 产前样叫"通过 / 不通过" */
export function qcResultLabel(kind: QcKind, result: QcResult): string {
  if (kind === 'PRE_PRODUCTION') return result === 'PASS' ? '通过' : '不通过'
  return QC_RESULT[result].label
}

/** 一条质检记录（列表、返修列表、详情、验货历史共用） */
export interface QcRow {
  id: string
  no: string
  companyId: string
  companyName: string
  kind: QcKind
  invDocId: string | null
  prdReceiptId: string | null
  prdOrderId: string | null
  /** OUTSOURCE 外发加工单 / BUY 成品采购单 */
  prdOrderKind: string | null
  prdOrderNo: string | null
  /** 采购入库单号、回货单号；产前样、中检是生产单号 */
  sourceNo: string
  attemptNo: number
  partnerId: string
  partnerCode: string
  partnerName: string
  productLine: string | null
  targetDesc: string | null
  lotQty: number | null
  /** 字典 unit */
  unit: string | null
  inspectDate: string
  sampleQty: number | null
  defectQty: number | null
  acceptQty: number | null
  result: QcResult
  /** 字典 qc_defect_type */
  defectTypes: string[]
  note: string | null
  reworkNote: string | null
  reworkBy: string | null
  reworkByName: string | null
  reworkTime: string | null
  reworkDoneBy: string | null
  reworkDoneByName: string | null
  reworkDoneTime: string | null
  deductionId: string | null
  /** 没有扣款登记查看权限的不返回这个字段 */
  deductAmount?: number | null
  /** 扣款进了哪张没作废的对账单：进了的金额不能改、记录不能删 */
  deductStatementNo: string | null
  photos: Attachment[]
  version: number
  createBy: string
  inspectorName: string | null
  createTime: string
}

export interface QcQuery {
  kind?: QcKind
  result?: QcResult
  companyId?: string
  partnerId?: string | null
  prdOrderId?: string
  dateFrom?: string
  dateTo?: string
  /** 质检单号、来源单号、生产单号、供应商、货品 */
  keyword?: string
}

/** 待检的一行：一张采购入库单或回货单 */
export interface QcPendingRow {
  kind: 'INCOMING' | 'FINAL'
  sourceId: string
  sourceNo: string
  sourceDate: string
  companyId: string
  companyName: string
  partnerId: string
  partnerCode: string
  partnerName: string
  prdOrderId: string | null
  prdOrderKind: string | null
  prdOrderNo: string | null
  productLine: string | null
  targetDesc: string | null
  /** 几种单位混在一起时 null */
  lotQty: number | null
  unit: string | null
  /** 返修回来再验的大于 1 */
  attemptNo: number
}

/** 记质检前看的来源单 */
export interface QcSource {
  kind: QcKind
  sourceId: string
  sourceNo: string
  sourceStatus: string
  companyId: string
  companyName: string
  partnerId: string
  partnerName: string
  prdOrderId: string | null
  prdOrderKind: string | null
  prdOrderNo: string | null
  productLine: string | null
  targetDesc: string | null
  lotQty: number | null
  unit: string | null
  attemptNo: number
  /** AQL 2.5 建议抽检数；批量不知道时 null */
  aqlSample: number | null
  /** 允收数：不合格数超过它建议判不合格 */
  aqlAccept: number | null
  /** 前几次的检验，早的在前 */
  history: QcRow[]
}

export interface QcForm {
  /** 改的时候带 */
  version?: number
  /** 新建时带 */
  kind?: QcKind
  sourceId?: string
  inspectDate: string
  sampleQty: number | null
  defectQty: number | null
  result: QcResult
  defectTypes: string[]
  note: string | null
  photos: FileVO[]
  /** 质量扣款：要扣款登记权限；不是合格的才能扣 */
  deductAmount: number | null
}

export interface QcCreated {
  id: string
  no: string
}

/** 记产前样、中检时选的生产单 */
export interface QcOrderOption {
  id: string
  no: string
  kind: string
  status: string
  companyId: string
  styleCode: string
  styleName: string
  partnerId: string
  partnerName: string
  totalQty: number | null
}

export interface QcSupplierStat {
  partnerId: string
  partnerCode: string
  partnerName: string
  inspectCount: number
  passCount: number
  concessionCount: number
  failCount: number
  sampleQty: number | null
  defectQty: number | null
  deductAmount?: number | null
  /** 最常见的问题类型（字典值），最多 3 个 */
  topDefects: string[]
}
