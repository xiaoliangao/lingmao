// supplier 域的返回，和后端 module/supplier/dto 一一对应（RFC 004）。字段名、可能为 null 的地方都照后端写

export type SupplierStatus = 'PENDING_ADMIT' | 'ADMITTING' | 'ACTIVE' | 'DISABLED' | 'BLACKLISTED'

/** 状态的叫法和标签颜色（StatusTag 的 tone） */
export const SUPPLIER_STATUS: Record<SupplierStatus, { label: string; tone: 'active' | 'progress' | 'pending' | 'off' | 'danger' }> = {
  PENDING_ADMIT: { label: '待准入', tone: 'off' },
  ADMITTING: { label: '准入审批中', tone: 'pending' },
  ACTIVE: { label: '合作中', tone: 'active' },
  DISABLED: { label: '停用', tone: 'off' },
  BLACKLISTED: { label: '黑名单', tone: 'danger' },
}

/** 详情页按钮、操作记录里的动作名（后端 SupplierVO.actions、DocLogVO.action） */
export type SupplierAction =
  | 'submit' | 'withdraw' | 'audit' | 'reject' | 'disable' | 'enable' | 'blacklist' | 'unblacklist' | 'edit' | 'remove'

/** 列表的一行。不出账号 */
export interface SupplierRow {
  id: string
  code: string
  name: string
  shortName: string
  creditCode: string | null
  level: string | null
  status: SupplierStatus
  /** 字典 supplier_category 的 value，按字典顺序 */
  categories: string[]
  contact: string | null
  phone: string | null
  settleMethod: string | null
  creditDays: number | null
  taxPct: number | null
  buyerId: string | null
  buyerName: string | null
  /** 有一份关键字段改动等审批 */
  changePending: boolean
  certCount: number
  /** 证照最早哪天到期；没有证照、全是长期有效的为 null */
  certEarliestUntil: string | null
  updateTime: string
}

/** 关键字段改动的一项。before、after 是给人看的文字（字典翻成中文，税率带 %，空的是"空"） */
export interface ChangeItem {
  field: string
  label: string
  before: string
  after: string
}

export type ChangeStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'

export const CHANGE_STATUS: Record<ChangeStatus, { label: string; tone: 'pending' | 'done' | 'danger' | 'closed' }> = {
  PENDING: { label: '待批', tone: 'pending' },
  APPROVED: { label: '已批', tone: 'done' },
  REJECTED: { label: '驳回', tone: 'danger' },
  WITHDRAWN: { label: '撤回', tone: 'closed' },
}

/** 一份关键字段改动。actions：当前用户能做的 approve / reject / withdraw */
export interface SupplierChange {
  id: string
  status: ChangeStatus
  items: ChangeItem[]
  applyBy: string | null
  applyByName: string | null
  applyTime: string
  auditByName: string | null
  auditTime: string | null
  rejectReason: string | null
  actions: ('approve' | 'reject' | 'withdraw')[]
}

/** 详情。账号是遮过的（前 4 后 4）；全号走 /bank */
export interface SupplierDetail {
  id: string
  code: string
  name: string
  shortName: string
  creditCode: string | null
  level: string | null
  status: SupplierStatus
  categories: string[]
  goodAt: { id: string; name: string }[]
  contact: string | null
  phone: string | null
  wechat: string | null
  address: string | null
  settleMethod: string | null
  creditDays: number | null
  invoiceType: string | null
  taxPct: number | null
  bankName: string | null
  bankAccount: string | null
  bankAccountName: string | null
  buyerId: string | null
  buyerName: string | null
  admitApplyBy: string | null
  admitApplyByName: string | null
  admitApplyTime: string | null
  admitByName: string | null
  admitTime: string | null
  blackReason: string | null
  blackByName: string | null
  blackTime: string | null
  remark: string | null
  /** 领猫导进来的附加属性，只读 */
  legacyAttrs: Record<string, string> | null
  pendingChange: SupplierChange | null
  actions: SupplierAction[]
  createByName: string | null
  createTime: string
  updateTime: string
}

/** 新建、保存的请求 */
export interface SupplierForm {
  name: string
  shortName: string
  creditCode: string | null
  level: string | null
  categories: string[]
  goodAtIds: string[]
  contact: string | null
  phone: string | null
  wechat: string | null
  address: string | null
  settleMethod: string | null
  creditDays: number | null
  invoiceType: string | null
  taxPct: number | null
  bankName: string | null
  /** 没有 bank 权限的人改不了，后端也不收 */
  bankAccount: string | null
  bankAccountName: string | null
  buyerId: string | null
  remark: string | null
}

/** 保存结果：applied 直接生效的关键字段，pending 挂起等审批的 */
export interface SupplierSaveResult {
  id: string
  applied: ChangeItem[]
  pending: ChangeItem[]
}

export interface SupplierBank {
  bankName: string | null
  bankAccount: string | null
  bankAccountName: string | null
}

export interface SupplierCert {
  id: string
  /** 字典 cert_type */
  certType: string
  /** 签过名的地址 */
  fileUrl: string
  fileName: string
  /** null = 长期有效 */
  validUntil: string | null
  remark: string | null
  createByName: string | null
  createTime: string
}

export interface CertForm {
  certType: string
  fileUrl: string
  fileName: string
  validUntil: string | null
  remark: string | null
}

/** 供应商下拉的一项（SupplierSelect） */
export interface SupplierOption {
  id: string
  code: string
  shortName: string
  status: SupplierStatus
}

/** 下拉的用途：打样、录价格（SAMPLE，待准入、审批中、合作中都能选）；大货单（BULK，只有合作中） */
export type SupplierUsage = 'SAMPLE' | 'BULK'

/** 列表的查询条件 */
export interface SupplierQuery {
  keyword?: string
  category?: string
  status?: SupplierStatus[]
  level?: string
  buyerId?: string | null
  toApprove?: boolean
  missingCredit?: boolean
  certExpiring?: boolean
  ids?: string[]
}

/** 擅长品类的树（GET /api/categories/tree，basic 域） */
export interface CategoryNode {
  id: string
  name: string
  children?: CategoryNode[]
}
