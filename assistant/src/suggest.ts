import { LOGIN_ONLY, toolsFor, type ToolDef } from './tools.ts'

/**
 * 推荐给这个人的问题（卡片空着时显示、输入时 Tab 补全；docs/api/08 §2.7）。
 * 不用模型生成：都是 tools.yaml 里写好的例句（ask），按这个人的权限筛——推荐的问题一定是他问得了的。
 * 他手上有待办的，先推那几类，带件数
 */
export interface Suggestion {
  text: string
  /** 小标签：哪一类（"我的待办"、工具的 label） */
  label: string
  /** 待办的件数；别的是 null */
  count: number | null
}

/** 首页待办的分组（docs/api/06 §2.3）→ 一句能直接问的话。都靠 my_todos（登录就能用）答 */
const TODO_ASK: Record<string, string> = {
  AUDIT: '有哪些单据等我审核',
  UNSCHEDULED: '哪些订单审核过了还没排单',
  PRODUCTION_LATE: '哪些生产单延期了',
  CUT_RISK: '哪些物料赶不上开裁',
  ARRIVAL_LATE: '哪些物料到料逾期了',
  UPSTREAM: '哪些单据的上游变了，要我处理',
  ISSUE: '有哪些生产异常还没处理',
  CERT: '哪些供应商的证照快到期了',
}

export interface TodoGroup {
  key: string
  total: number
}

/** 从 /api/board/home/todos 的返回里取分组；形状不对返回空 */
export function todoGroups(data: unknown): TodoGroup[] {
  const groups = (data as { groups?: unknown } | null)?.groups
  if (!Array.isArray(groups)) return []
  return groups.flatMap((g) => {
    const { key, total } = (g ?? {}) as { key?: unknown; total?: unknown }
    return typeof key === 'string' && typeof total === 'number' && total > 0 ? [{ key, total }] : []
  })
}

/**
 * 先是手上的待办（按首页的顺序），再是各模块轮流出一句：每个模块先出第一句、再第二句……头几句就照顾到他有的各个模块。
 * 模块的先后看他在这个模块有几个权限码（仓管库存的权限多，库存排前面；只要登录的首页那几句总在最前），一样多的照 tools.yaml 的顺序。
 * 同一句只出一次
 */
export function suggestionsFor(perms: ReadonlySet<string>, defs: ToolDef[], todos: TodoGroup[], max = 30): Suggestion[] {
  const out: Suggestion[] = []
  const seen = new Set<string>()
  const push = (s: Suggestion) => {
    if (seen.has(s.text) || out.length >= max) return
    seen.add(s.text)
    out.push(s)
  }
  for (const g of todos) {
    const text = TODO_ASK[g.key]
    if (text) push({ text, label: '我的待办', count: g.total })
  }
  const byModule = new Map<string, ToolDef[]>()
  for (const tool of toolsFor(perms, defs)) {
    if (!tool.ask) continue
    const module = moduleOf(tool)
    byModule.set(module, [...(byModule.get(module) ?? []), tool])
  }
  const weight = (module: string) => (module === '' ? Infinity : [...perms].filter((p) => p.startsWith(`${module}:`)).length)
  const queues = [...byModule.entries()].sort((a, b) => weight(b[0]) - weight(a[0])).map(([, tools]) => tools)
  for (let round = 0; queues.some((q) => q.length > round); round++) {
    for (const q of queues) {
      const tool = q[round]
      if (tool) push({ text: tool.ask!, label: tool.label, count: null })
    }
  }
  return out
}

/** 按权限码的第一段分模块（order、purchase……）；只要登录的（首页那几个）是 '' */
function moduleOf(tool: ToolDef): string {
  return tool.perm === LOGIN_ONLY ? '' : (tool.perm.split(':')[0] ?? tool.perm)
}
