// 首页（概况、待办、近期交期）和"上游已变更"页的返回，和后端 module/board/dto 一一对应（docs/api/06-首页.md）。
// board 域只读：处理上游提醒走各单据自己的 notices 接口（api/home.ts 的 upstreamApi.handle）
import type { Attachment, DocNotice, DocRef, StatusTone } from '@/types/biz'

// ---------------------------------------------------------------- 概况（四个格子）

/** 开发款式（要 style:style:list） */
export interface HomeDevKpi {
  /** 没停用的款 */
  styleCount: number
  /** 资料包草稿 */
  packDraftCount: number
}

/** 在手订单（要 order:order:list） */
export interface HomeOrderKpi {
  /** 已审核、生产中的需求订单：件数、张数 */
  qty: number
  count: number
  /** 待审核的订单 */
  pendingCount: number
  /** 已审核、一件都还没排的 */
  unscheduledCount: number
  /** 待审核的变更单；没有 order:change:list 是 null */
  changePendingCount: number | null
}

/** 在制生产（要 production:order:list） */
export interface HomeProductionKpi {
  /** 已下单、生产中的生产单（两种都算）：件数（件数 − 已回良品）、张数 */
  qty: number
  count: number
  lateCount: number
  nodeLateCount: number
  /** 有没关闭的异常的单 */
  issueCount: number
}

/** 在途物料（要 purchase:order:list） */
export interface HomeMaterialKpi {
  /** 还没到齐的采购行（已审核、部分到货的采购单） */
  transitLineCount: number
  arrivalLateCount: number
  /** 待审核的采购单 */
  purchasePendingCount: number
  /** 草稿算料单；没有 purchase:mrp:list 是 null */
  mrpDraftCount: number | null
}

/** GET /api/board/home/kpi：照数据范围数。没有那个菜单权限的格子是 null，页面不显示这一格 */
export interface HomeKpi {
  dev: HomeDevKpi | null
  order: HomeOrderKpi | null
  production: HomeProductionKpi | null
  material: HomeMaterialKpi | null
}

// ---------------------------------------------------------------- 待办（具体到单据）

/** 待办分组，后端按这个顺序给（PRD §6.9.2） */
export type TodoGroupKey = 'AUDIT' | 'PRODUCTION_LATE' | 'CUT_RISK' | 'ARRIVAL_LATE' | 'UPSTREAM' | 'UNSCHEDULED' | 'ISSUE' | 'CERT'

/** 分组的叫法和标签颜色（分段控件、"全部"里每条后面的小标签） */
export const TODO_GROUP: Record<TodoGroupKey, { label: string; tone: StatusTone }> = {
  AUDIT: { label: '待我审核', tone: 'pending' },
  PRODUCTION_LATE: { label: '生产延期', tone: 'danger' },
  CUT_RISK: { label: '晚于开裁', tone: 'danger' },
  ARRIVAL_LATE: { label: '到料逾期', tone: 'danger' },
  UPSTREAM: { label: '上游已变更', tone: 'pending' },
  UNSCHEDULED: { label: '待排单', tone: 'pending' },
  ISSUE: { label: '异常待处理', tone: 'pending' },
  CERT: { label: '证照将到期', tone: 'pending' },
}

/** 待办的一条：一张单据，或者一个供应商（证照） */
export interface TodoItem {
  /**
   * 点了去哪：docRoute(ref)。异常的 type 是 PRODUCTION_ISSUE、parentId 是生产单；证照是 SUPPLIER；
   * 到料逾期、晚于开裁（采购员看到的）是 PURCHASE_ORDER
   */
  ref: DocRef
  /** 第一行，后端拼好：如"需求订单 DD2611150001""JG2611150003 女式风衣""卡其布 还差 120 米" */
  title: string
  /** 第二行，后端拼好：如"某制衣 · 2,400 件 · 第 2 级""交期 03-10 已过""CG2611100002 · 应到 11-12" */
  sub: string
  /** 款式图或物料图的缩略图；没有是 null */
  image: Attachment | null
}

export interface TodoGroup {
  key: TodoGroupKey
  /** 这一组一共几条；items 最多给 20 条，多出来的页面写"还有 n 条"，点了去 TODO_GROUP_MORE 的地址 */
  total: number
  items: TodoItem[]
}

/** 一组超过 20 条时"还有 n 条"去哪；没有对应列表的组是 null（不出这句） */
export const TODO_GROUP_MORE: Record<TodoGroupKey, string | null> = {
  AUDIT: null,
  PRODUCTION_LATE: '/board?card=LATE',
  CUT_RISK: '/stock-in?tab=pending&risk=CUT',
  ARRIVAL_LATE: '/stock-in?tab=pending&risk=LATE',
  UPSTREAM: '/upstream',
  UNSCHEDULED: '/orders?unscheduled=1',
  ISSUE: '/board?card=ISSUE',
  CERT: '/suppliers?certExpiring=1',
}

/** "其他提醒"的一个数：各模块要处理的条数，点了打开已经筛好的列表 */
export type TodoCountKey =
  | 'ORDER_PENDING'
  | 'CHANGE_PENDING'
  | 'PURCHASE_PENDING'
  | 'OUTSOURCE_PENDING'
  | 'BUY_PENDING'
  | 'STOCK_IN_DRAFT'
  | 'STOCK_OUT_DRAFT'
  | 'COUNT_PENDING'
  | 'FG_PENDING'
  | 'CONSUME_PENDING'
  | 'SUPPLIER_APPROVE'
  | 'UNSCHEDULED'
  | 'PRODUCTION_LATE'
  | 'PRODUCTION_NEAR'
  | 'PRODUCTION_NODE_LATE'
  | 'PRODUCTION_ISSUE'
  | 'ARRIVAL_LATE'
  | 'ARRIVAL_CUT'
  | 'UPSTREAM'
  | 'CERT_EXPIRING'
  | 'REORDER'
  | 'STALE_MATERIAL'

/** 叫法、点了去哪、数字什么颜色（danger 红、pending 琥珀、null 不上色）。地址参数各模块文档里定好了；待审核的几块带 auditForMe=1，和首页的数一样（不含自己建的） */
export const TODO_COUNT: Record<TodoCountKey, { label: string; to: string; tone: StatusTone | null }> = {
  ORDER_PENDING: { label: '待审核的需求订单', to: '/orders?status=PENDING&auditForMe=1', tone: null },
  CHANGE_PENDING: { label: '待审核的订单变更', to: '/order-changes?status=PENDING&auditForMe=1', tone: null },
  PURCHASE_PENDING: { label: '待审核的采购单', to: '/purchases?status=PENDING&auditForMe=1', tone: null },
  OUTSOURCE_PENDING: { label: '待审核的外发加工单', to: '/production?status=PENDING&auditForMe=1', tone: null },
  BUY_PENDING: { label: '待审核的成品采购单', to: '/fp-purchases?status=PENDING&auditForMe=1', tone: null },
  STOCK_IN_DRAFT: { label: '物料入库单待审核', to: '/stock-in?tab=docs&status=DRAFT&auditForMe=1', tone: null },
  STOCK_OUT_DRAFT: { label: '物料出库单待审核', to: '/stock-out?status=DRAFT&auditForMe=1', tone: null },
  COUNT_PENDING: { label: '物料盘点单待审核', to: '/stock-takes?status=PENDING&auditForMe=1', tone: null },
  FG_PENDING: { label: '回货待入成品仓', to: '/fg-in?tab=pending', tone: 'pending' },
  CONSUME_PENDING: { label: '待核销耗料', to: '/factory-ledger?status=TO_SETTLE', tone: 'pending' },
  SUPPLIER_APPROVE: { label: '供应商待审批', to: '/suppliers?toApprove=1', tone: null },
  UNSCHEDULED: { label: '已审核还没排单的需求订单', to: '/orders?unscheduled=1', tone: 'pending' },
  PRODUCTION_LATE: { label: '生产单已逾期', to: '/board?card=LATE', tone: 'danger' },
  PRODUCTION_NEAR: { label: '生产单临期', to: '/board?card=NEAR', tone: 'pending' },
  PRODUCTION_NODE_LATE: { label: '生产单节点延期', to: '/board?card=NODE_LATE', tone: 'danger' },
  PRODUCTION_ISSUE: { label: '生产单有没关闭的异常', to: '/board?card=ISSUE', tone: 'pending' },
  ARRIVAL_LATE: { label: '到料逾期', to: '/stock-in?tab=pending&risk=LATE', tone: 'danger' },
  ARRIVAL_CUT: { label: '到料晚于开裁', to: '/stock-in?tab=pending&risk=CUT', tone: 'danger' },
  UPSTREAM: { label: '上游订单已变更，待处理', to: '/upstream', tone: 'pending' },
  CERT_EXPIRING: { label: '供应商证照 30 天内到期', to: '/suppliers?certExpiring=1', tone: null },
  REORDER: { label: '物料低于最小库存', to: '/reorder', tone: null },
  STALE_MATERIAL: { label: '呆滞料', to: '/stale-materials', tone: 'pending' },
}

export interface TodoCount {
  key: TodoCountKey
  count: number
}

/**
 * 待办的筛选（用户 11-15 定：管理层默认看全部，可以筛）。只对 canSeeAll 的人有用，别人发了也只看自己的。
 * "待我审核"（AUDIT）一组和审核类的数不受它影响：轮到谁审就是谁的
 */
export interface HomeTodoQuery {
  /** true：只看负责人是我的 */
  mine?: boolean
  /** 只看负责人是这个人的（生产跟单、采购员……），和 mine 二选一 */
  ownerId?: string | null
}

/** GET /api/board/home/todos */
export interface HomeTodos {
  /** 有条目的组才在里面 */
  groups: TodoGroup[]
  /** 数是 0 的、没权限看的不在里面 */
  counts: TodoCount[]
  /** 能不能看全部人的（数据范围是"全部"的人，不看审核权限）。true 时待办面板顶上出"全部 / 我的"和负责人筛选 */
  canSeeAll: boolean
}

// ---------------------------------------------------------------- 近期交期

/** 一张 7 天内要交、或者已经逾期还没交的需求订单、生产单 */
export interface DeliveryRow {
  /** ORDER 或 PRODUCTION_ORDER；ref.typeLabel 是"需求订单""外发加工单""成品采购单"，页面放在单号前面的小标签里 */
  ref: DocRef
  /** 需求订单：款名（几个款用顿号连）；生产单：款名 · 加工厂 */
  sub: string
  image: Attachment | null
  /** 需求订单 = 承诺交期 ?? 要求交期；生产单 = 预计交期 ?? 交期 */
  dueDate: string
  /** 今天到交期还有几天，逾期是负数 */
  daysLeft: number
  /** 进度：已回货（只算良品）/ 件数 */
  doneQty: number
  totalQty: number
}

// ---------------------------------------------------------------- 上游已变更（/upstream）

/** 能收到上游提醒的单据：外发加工单和成品采购单（PRODUCTION_ORDER）、采购单、算料单 */
export type UpstreamDocType = 'PRODUCTION_ORDER' | 'PURCHASE_ORDER' | 'MRP_CALC'

export interface UpstreamQuery {
  type?: UpstreamDocType
  /** 只看负责人是我的（有"全部"范围的人才有这个切换） */
  mine?: boolean
  /** 只看负责人是这个人的，和 mine 二选一（同 HomeTodoQuery） */
  ownerId?: string | null
}

/** GET /api/board/upstream：按最新一条提醒的时间倒序，不分页 */
export interface UpstreamList {
  /** 能不能看全部人的，和待办的 canSeeAll 同一个规则（06 §5）。true 时出"全部 / 我的"和负责人筛选 */
  canSeeAll: boolean
  rows: UpstreamRow[]
}

/** 一行 = 一张下游单据和它还没处理的提醒 */
export interface UpstreamRow {
  /** 下游单据；负责人（生产跟单、采购员、算料单制单人）在 ownerName */
  doc: DocRef
  /** 没处理的提醒，新的在前。source 是引起它的变更单，content 里写了上游订单号和改了什么 */
  notices: DocNotice[]
}
