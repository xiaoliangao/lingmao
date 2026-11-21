// biz 域（单据公共能力）的返回，和后端 module/biz/dto 一一对应。字段名、可能为 null 的地方都照后端写

/**
 * 操作记录的一行（DocLogVO）。action、fromStatus、toStatus 是英文名，页面按单据类型翻成中文（DocLogList）；
 * 状态没变的动作（改资料、换图）两个状态都是 null
 */
export interface DocLog {
  id: string
  action: string
  fromStatus: string | null
  toStatus: string | null
  remark: string | null
  operatorId: string | null
  /** 操作人的昵称，人删了也照样有；系统做的（没有操作人）是 null */
  operatorName: string | null
  /** 2026-11-15 10:30:00，东八区 */
  createTime: string
  /** 改了哪些字段（二期，RFC 016 §3）：物料、款式、供应商的"修改"和供应商的"批准改动"有，别的是空数组 */
  diff: FieldChange[]
}

/** 操作记录里的一处改动（FieldChangeVO） */
export interface FieldChange {
  /** 字段名，如 refPrice */
  field: string
  /** 叫法，照表单，如"参考单价" */
  label: string
  /** 改前、改后显示的文字（字典已翻成中文、id 已换成名字、账号是遮过的）；空是 null。hidden 时都是 null */
  before: string | null
  after: string | null
  /** 看的人没有这个字段的权限（成本价、联系方式、银行信息）：只显示"改了"，不给值 */
  hidden: boolean
}

/**
 * 挂在单据、主数据上的一个附件（AttachmentVO）：地址都带签名。和上传接口返回的 FileVO 一个样子，多一个 id；
 * 整组替换时把 url、thumbUrl、name、size 原样传回去（PUT …/images）
 */
export interface Attachment {
  id: string
  url: string
  thumbUrl: string | null
  name: string
  size: number
}

// ---------------------------------------------------------------- 单据公共（docs/api/README.md §4）

/** StatusTag 的颜色（components/StatusTag.vue） */
export type StatusTone = 'active' | 'progress' | 'pending' | 'done' | 'off' | 'closed' | 'void' | 'danger'

/**
 * 全系统单据和主数据的类型（后端 biz.entity.BizType，库里存这个名字）。DocRef、操作记录、附件、审批都按它认。
 * 一张表两种单据的（PRODUCTION_ORDER 分外发加工单 / 成品采购单，INV_DOC、FG_DOC 分入库单 / 出库单）再看 kind
 */
export type BizType =
  | 'SUPPLIER'
  | 'MATERIAL'
  | 'MATERIAL_SKU'
  | 'STYLE'
  | 'TECH_PACK'
  | 'ORDER'
  | 'ORDER_CHANGE'
  | 'MRP_CALC'
  | 'PURCHASE_ORDER'
  | 'INV_DOC'
  | 'INV_COUNT'
  | 'PRODUCTION_ORDER'
  | 'PRODUCTION_RECEIPT'
  | 'PRODUCTION_ISSUE'
  | 'PRODUCTION_NODE_REPORT'
  | 'FG_DOC'

/**
 * 单据的通用状态（后端 common.doc.DocStatus，架构 §4.2）。各单据只用其中一部分；中文按单据类型叫
 * （采购单的 RUNNING 叫"部分到货"，需求订单的 VOIDED 叫"已取消"），各模块的 types 里有自己的 XXX_STATUS。
 * 资料包、算料单、异常的状态不一样，各用自己的类型
 */
export type DocStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'RUNNING' | 'DONE' | 'CLOSED' | 'VOIDED'

/** 默认的叫法和颜色。颜色照原型的 St：草稿灰、待审核琥珀、已审核实线、执行中铜褐底、完成绿、结案灰字、作废删除线 */
export const DOC_STATUS: Record<DocStatus, { label: string; tone: StatusTone }> = {
  DRAFT: { label: '草稿', tone: 'off' },
  PENDING: { label: '待审核', tone: 'pending' },
  APPROVED: { label: '已审核', tone: 'active' },
  RUNNING: { label: '执行中', tone: 'progress' },
  DONE: { label: '已完成', tone: 'done' },
  CLOSED: { label: '已结案', tone: 'closed' },
  VOIDED: { label: '已作废', tone: 'void' },
}

/**
 * 状态动作：POST /api/<单据>/{id}/<动作>，请求体 DocActionReq（审核要多填东西的单据，各模块另有 XxxAuditReq）。
 * 详情的 actions 里还有 edit、remove 和各单据自己的动作（如 schedule 排单），各模块的 XxxAction 把它们并起来
 */
export type DocAction = 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit' | 'close' | 'reopen' | 'void'

/** 中文叫法，按钮和操作记录都用 */
export const DOC_ACTION_LABEL: Record<DocAction, string> = {
  submit: '提交',
  withdraw: '撤回',
  reject: '驳回',
  audit: '审核',
  unaudit: '反审核',
  close: '结案',
  reopen: '取消结案',
  void: '作废',
}

/** 只有驳回要写原因（不写 400，制单人在操作记录里看）；作废、结案、反审核、取消结案先说后果再确认（原型 3-ui.js 的 docAct） */
export const REASON_REQUIRED: readonly DocAction[] = ['reject']

/** 点了先确认的动作：确认框里说的后果；danger 的确认按钮是红的 */
export const DOC_ACTION_CONFIRM: Partial<Record<DocAction, { text: string; danger: boolean }>> = {
  void: { text: '作废后这张单只读，单号不再使用。', danger: true },
  close: { text: '结案后不能再入库、回货、生成下游单据，只能看。要继续可以取消结案。', danger: false },
  unaudit: { text: '回到草稿，改完要重新提交审核。', danger: false },
  reopen: { text: '回到结案前的状态，可以继续执行。', danger: false },
}

/**
 * 新建单据的返回（每种单据的 POST 都返回它，有的再多几个字段）。"存草稿并提交"接着调 act(id, 'submit', { version })，不用再取一次详情；
 * 保存草稿（PUT）返回新的 version（一个数字）
 */
export interface DocSaved {
  id: string
  no: string
  version: number
}

/**
 * 状态动作的请求体。version：打开详情时拿到的版本号，对不上（别人改过、审过）后端回 1002，页面提示刷新。
 * reason：驳回的原因（必填），别的动作选填，写进操作记录
 */
export interface DocActionReq {
  version: number
  reason?: string | null
}

/**
 * 指向一张单据（后端 biz.dto.DocRef）。1005 的 data、"下游单据"页签、首页待办都是它的列表。
 * 点了去哪：utils/docRoute.ts 的 docRoute(ref)
 */
export interface DocRef {
  type: BizType
  /** 一张表两种单据的：PRODUCTION_ORDER 是 OUTSOURCE / BUY，INV_DOC、FG_DOC 是 IN / OUT；别的 null */
  kind: string | null
  /** 没有自己详情页、挂在上一级页面里的：回货单、异常 → 所属生产单的 id，资料包 → 款式 id；别的 null */
  parentId: string | null
  /** 外发加工单、物料入库单……（按 kind 取好的） */
  typeLabel: string
  id: string
  no: string
  status: string
  /** 按这种单据的叫法翻好的中文 */
  statusLabel: string
  statusTone: StatusTone
  /** 负责人（跟单、采购员、制单人，看单据），没有是 null */
  ownerId: string | null
  ownerName: string | null
}

/** 多级审批的一级（架构 §4.8）。第 1 级是"有审核权限的人"，后面的是规则按金额加的 */
export interface ApprovalLevel {
  levelNo: number
  /** 谁审：第 1 级"有审核权限的人"，规则加的是角色名或人名 */
  approverLabel: string
  /** 规则加的级：金额超过多少才有这一级；第 1 级 null */
  overAmount: number | null
  status: 'WAITING' | 'CURRENT' | 'PASSED' | 'CANCELLED'
  passByName: string | null
  /** 2026-11-15 10:30:00 */
  passTime: string | null
}

/**
 * 详情的"审批"块（需求订单、采购单、外发加工单、成品采购单才有，别的单据 approval 是 null）。
 * 待审核时一直显示，好知道在等谁；审完了只有多于一级的留着
 */
export interface ApprovalView {
  /** 第几次提交（驳回、撤回后重新提交加 1） */
  round: number
  levels: ApprovalLevel[]
  /** 现在等谁审：如"第 2 级 · 总经理"；没在审是 null */
  waitingFor: string | null
  /** 我能不能审这一级（actions 里有没有 audit、reject 也是按它算的） */
  canPass: boolean
}

/** 上游变了，给这张单据的提醒（biz_doc_notice）。详情顶上的黄条、首页"上游已变更"都读它 */
export interface DocNotice {
  id: string
  /** 哪张上游单据变的（订单变更单） */
  source: DocRef
  /** 说了什么，如"订单 DD2611150001 的 FS2611080701 黑色 M 码从 100 改成 80" */
  content: string
  /** 2026-11-15 10:30:00 */
  createTime: string
  /** 处理了的：谁、什么时候、怎么处理的；没处理是 null */
  handledByName: string | null
  handledTime: string | null
  handleRemark: string | null
}

/** 设置 › 审批的一条规则（/api/approval-rules，只给超级管理员） */
export interface ApprovalRule {
  id: string
  bizType: 'ORDER' | 'PURCHASE_ORDER' | 'PRODUCTION_ORDER'
  /** 金额超过多少（元），加一级 */
  overAmount: number
  /** 审批人：角色、指定的人二选一 */
  roleId: string | null
  roleName: string | null
  userId: string | null
  userName: string | null
}

export interface ApprovalRuleForm {
  bizType: ApprovalRule['bizType']
  overAmount: number | null
  roleId: string | null
  userId: string | null
}

/**
 * 单据详情的公共部分。各单据的 XxxDetail 都 extends 它（单头的业务字段、明细另外加）
 */
export interface DocHeader<S extends string = DocStatus, A extends string = DocAction> {
  id: string
  no: string
  status: S
  /** 保存草稿、状态动作都要带回去 */
  version: number
  companyId: string
  companyName: string
  remark: string | null
  createBy: string | null
  createByName: string | null
  /** 2026-11-15 10:30:00 */
  createTime: string
  updateTime: string
  auditBy: string | null
  auditByName: string | null
  auditTime: string | null
  /** 能点的按钮，后端按"状态允许 ∩ 有权限 ∩ 业务条件"算好的（架构 §4.2），前端照着显示，不自己判断 */
  actions: A[]
  /** 多级审批的单据才有，别的 null */
  approval: ApprovalView | null
  /** 没处理的上游变更提醒条数，> 0 时详情顶上出黄条 */
  pendingNotices: number
}
