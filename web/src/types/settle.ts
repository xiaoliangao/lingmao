import type { StatusTone } from '@/types/biz'

/**
 * 对账结算（docs/api/09-对账.md，RFC 019 §5，甲方 11-27 答 Q68）：供应商对账单、扣款登记、和对方 Excel 比对。
 * 金额都是元，两位小数；退货的数量、金额是负的
 */

export type StatementStatus = 'DRAFT' | 'SENT' | 'CONFIRMED' | 'PAID' | 'VOIDED'

export const STATEMENT_STATUS: Record<StatementStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  SENT: { label: '已发送', tone: 'pending' },
  CONFIRMED: { label: '已确认', tone: 'done' },
  PAID: { label: '已付款', tone: 'done' },
  VOIDED: { label: '已作废', tone: 'void' },
}

export type StatementSourceType = 'PURCHASE_IN' | 'PURCHASE_RETURN' | 'PRODUCTION_RECEIPT' | 'DEDUCTION'

export const STATEMENT_SOURCE: Record<StatementSourceType, { label: string; tone: StatusTone }> = {
  PURCHASE_IN: { label: '入库', tone: 'active' },
  PURCHASE_RETURN: { label: '退货', tone: 'danger' },
  PRODUCTION_RECEIPT: { label: '回货', tone: 'progress' },
  DEDUCTION: { label: '扣款', tone: 'pending' },
}

/**
 * 能做的动作（详情的 actions，后端按状态和权限算好）：refresh 刷新、removeItems 移出、addDeduction 加扣款、
 * compare 上传对方对账单、notes 写差异说明、send 发送、withdraw 撤回、confirm 确认、void 作废
 */
export type StatementAction = 'refresh' | 'removeItems' | 'addDeduction' | 'compare' | 'notes' | 'send' | 'withdraw' | 'confirm' | 'void'

/** 操作记录里的动作 */
export const STATEMENT_LOG_ACTION: Record<string, string> = {
  create: '生成',
  refresh: '刷新',
  removeItems: '移出',
  addDeduction: '加扣款',
  compare: '比对',
  notes: '写说明',
  send: '发送',
  withdraw: '撤回',
  confirm: '确认',
  void: '作废',
}

export interface StatementQuery {
  /** 状态，几个一起查；不传是全部（含已作废） */
  status?: StatementStatus[]
  companyId?: string
  partnerId?: string | null
  /** 期间和这一段有重叠的 */
  periodFrom?: string
  periodTo?: string
  /** 对账单号、供应商的编码、名称、简称 */
  keyword?: string
  mine?: boolean
}

export interface StatementRow {
  id: string
  no: string
  companyId: string
  companyName: string
  partnerId: string
  partnerCode: string
  partnerName: string
  periodStart: string
  periodEnd: string
  statementDate: string
  status: StatementStatus
  /** 货款合计（退货是负的） */
  totalAmount: number
  deductAmount: number
  /** 实付 = 货款 − 扣款 */
  payableAmount: number
  /** 对方对账单上的合计；没比对过是 null */
  theirAmount: number | null
  /** 比对出几处不一致；没比对过是 null */
  diffCount: number | null
  itemCount: number
  remark: string | null
  createBy: string
  creatorName: string | null
  createTime: string
}

export interface StatementItem {
  id: string
  seq: number
  sourceType: StatementSourceType
  /** 入库单、退货单、回货单、扣款的 id */
  sourceId: string
  sourceItemId: string | null
  /** 回货单所在的生产单 id（链接用） */
  parentId: string | null
  /** 单号；扣款是登记时写的相关单号 */
  sourceNo: string | null
  /** 采购单号、生产单号 */
  orderNo: string | null
  /** 供应商送货单号 */
  deliveryNo: string | null
  bizDate: string
  description: string | null
  /** 采购单位（字典 unit 的值）；回货是件，空着 */
  unit: string | null
  qty: number | null
  /** 没有单价是 null（金额是 0，标"缺单价"） */
  price: number | null
  amount: number
  deductAmount: number
}

export type CompareOutcome = 'MATCH' | 'DIFF' | 'THEIRS_ONLY' | 'OURS_ONLY'

export const COMPARE_OUTCOME: Record<CompareOutcome, { label: string; tone: StatusTone }> = {
  MATCH: { label: '一致', tone: 'done' },
  DIFF: { label: '不一致', tone: 'danger' },
  THEIRS_ONLY: { label: '对方有我方没有', tone: 'pending' },
  OURS_ONLY: { label: '我方有对方没有', tone: 'pending' },
}

/** 对方表上的一处（几行合成一张单据） */
export interface CompareTheirs {
  /** Excel 里的第几行（从 1 数） */
  rowNos: number[]
  no: string | null
  date: string | null
  description: string | null
  qty: number | null
  amount: number
  /** 是不是扣款行 */
  deduct: boolean
}

export interface CompareOurs {
  sourceType: StatementSourceType
  sourceId: string
  no: string | null
  deliveryNo: string | null
  date: string | null
  description: string | null
  qty: number | null
  amount: number
}

export interface CompareRow {
  /** 写说明时用 */
  key: string
  outcome: CompareOutcome
  their: CompareTheirs | null
  ours: CompareOurs | null
  /** 对方 − 我方 */
  diffQty: number | null
  diffAmount: number
  /** 和对方谈的结果 */
  note: string | null
}

export interface CompareResult {
  fileName: string
  comparedAt: string
  comparedBy: string
  comparedByName: string | null
  sheetName: string
  theirRowCount: number
  theirGoods: number
  theirDeduct: number
  /** 对方的实付合计 */
  theirTotal: number
  ourTotal: number
  /** 对方 − 我方 */
  diff: number
  rows: CompareRow[]
}

export interface StatementDetail extends StatementRow {
  version: number
  /** 字典 settle_method 的值 */
  partnerSettleMethod: string | null
  partnerCreditDays: number | null
  items: StatementItem[]
  compare: CompareResult | null
  sentBy: string | null
  sentByName: string | null
  sentTime: string | null
  auditBy: string | null
  auditByName: string | null
  auditTime: string | null
  confirmNote: string | null
  voidReason: string | null
  actions: StatementAction[]
}

/** 待对账：某主体、某家、某个月还没进对账单的单据 */
export interface PendingStatementRow {
  companyId: string
  companyName: string
  partnerId: string
  partnerCode: string
  partnerName: string
  /** yyyy-MM */
  month: string
  periodStart: string
  periodEnd: string
  docCount: number
  /** 实付（货款 − 扣款） */
  amount: number
}

export interface StatementCreateForm {
  companyId: string
  partnerId: string
  periodStart: string
  periodEnd: string
  remark?: string | null
}

export interface StatementCreated {
  id: string
  no: string
  version: number
  /** 拉进来几行 */
  added: number
}

export interface StatementDeductionForm {
  version: number
  /** 不传是期间止 */
  bizDate?: string | null
  amount: number
  reason: string
  refNo?: string | null
}

export interface DeductionQuery {
  companyId?: string
  partnerId?: string | null
  dateFrom?: string
  dateTo?: string
  /** true = 已进了没作废的对账单，false = 还没对 */
  settled?: boolean
  /** 原因、相关单号、供应商的编码、名称、简称 */
  keyword?: string
}

export interface DeductionRow {
  id: string
  companyId: string
  companyName: string
  partnerId: string
  partnerCode: string
  partnerName: string
  bizDate: string
  amount: number
  reason: string
  refNo: string | null
  /** 进了哪张没作废的对账单；还没对是 null */
  statementId: string | null
  statementNo: string | null
  statementStatus: StatementStatus | null
  version: number
  createBy: string
  creatorName: string | null
  createTime: string
}

export interface DeductionForm {
  /** 改的时候带 */
  version?: number | null
  companyId: string
  partnerId: string
  bizDate: string
  amount: number
  reason: string
  refNo?: string | null
}
