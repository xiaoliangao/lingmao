import { computed, effectScope, reactive, watch } from 'vue'
import { AssistantError, assistantApi } from '@/api/assistant'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { AssistantConversation, AssistantEvent, AssistantInfo, AssistantMessage } from '@/types/assistant'
import { ApiError } from '@/utils/request'
import { onSessionChanged, sessionChanged } from '@/utils/token'

/**
 * 智能助手的状态和动作（docs/api/08-智能助手.md）。模块级单例：右下角的图标、⌘K、对话卡片共用一份；
 * 切页签、去管理后台再回来，对话都还在。退出登录、换了人（别的页签登录了别人）时全部清掉。
 *
 *   const assistant = useAssistant()
 *   assistant.enabled        出不出入口（AI 开关开着、有 assistant:chat:use；只管显示，拦截在后端）
 *   assistant.state.open     对话卡片开着没有（关着显示右下角的图标）；toggle() / show() / hide()
 *   assistant.state.unread   收起来的时候答完了一句：图标上亮个点，打开就灭
 *   assistant.ask('PO… 审了没有')   打开卡片、发出这句话（在答的时候不发、放进输入框，返回 false）
 *
 * 令牌：用登录身份换的 30 分钟助手令牌，只放内存；剩不到 5 分钟先换新的，助手服务回 401 再换一次重发。
 * 回答：答的过程中 turn.draft 是模型的原话（只能当纯文本显示），答完 turn.answer 是处理过的全文（交给 AssistantAnswer 渲染）
 */
export const ASSISTANT_PERM = 'assistant:chat:use'
/** 一句话最多几个字（助手服务也卡这个数） */
export const MAX_QUESTION = 2000

export interface ToolStep {
  name: string
  /** 查的是什么（"物料库存""采购单详情"） */
  label: string
  /** stopped：这句停了、断了，它没查完 */
  status: 'running' | 'ok' | 'failed' | 'stopped'
  /** 查到几行；不是列表的是 null */
  rows: number | null
}

export interface ChatTurn {
  id: number
  question: string
  /** 答的过程中模型的原话：没处理过，只能当纯文本 */
  draft: string
  /** 答完处理过的全文：只有它能交给 AssistantAnswer 渲染 */
  answer: string | null
  tools: ToolStep[]
  error: string | null
  state: 'asking' | 'done' | 'failed' | 'stopped'
}

/** unknown：还没查过；down：没启动、连不上；noModel：起了但没配模型 */
export type AssistantStatus = 'unknown' | 'up' | 'noModel' | 'down'

const state = reactive({
  open: false,
  /** 收起来的时候答完了（或没答成）一句，还没看 */
  unread: false,
  status: 'unknown' as AssistantStatus,
  /** 用不了的原因，卡片里显示 */
  statusMessage: '',
  checking: false,
  checkedAt: 0,
  /** 模型是外部的还是内网的：空白页上说一句数据去哪 */
  mode: null as AssistantInfo['model']['mode'] | null,
  /** 对话留几天（最近的对话下面写一句）；还没问到是 null */
  retentionDays: null as number | null,
  turns: [] as ChatTurn[],
  conversationId: null as string | null,
  /** 在答：一次只答一句 */
  busy: false,
  /** 输入框里还没发的话：收起来再打开还在 */
  draft: '',
  history: [] as AssistantConversation[],
  historyLoading: false,
  /** 最近的对话列表没加载出来（显示在下拉里） */
  historyError: '',
  /** 点开的对话没打开（显示在卡片里） */
  openError: '',
})

let token: { value: string; expiresAt: number } | null = null
let controller: AbortController | null = null
/** 每次清空（退出、换人）加一：清空以前发出去的请求回来了，结果不要，令牌也不要 */
let generation = 0
/** 卡片里显示的是哪一段对话：问新的、点"新对话"、打开历史都加一，慢回来的旧请求不覆盖（审查 T-c5c68e #4） */
let viewSeq = 0
/** 最近的对话列表：每次加载加一，只认最后一次的 */
let historySeq = 0
/** 正在打开（请求还在路上）的对话 id：删它时要作废那次打开 */
let openingId: string | null = null
let seq = 0
let wired = false

/** 清空、换了人以后回来的请求：不写回、不提示 */
class Stale extends Error {}

function reset() {
  generation++
  viewSeq++
  historySeq++
  openingId = null
  controller?.abort()
  controller = null
  token = null
  Object.assign(state, {
    open: false, unread: false, status: 'unknown', statusMessage: '', checking: false, checkedAt: 0, mode: null, retentionDays: null,
    turns: [], conversationId: null, busy: false, draft: '', history: [], historyLoading: false, historyError: '', openError: '',
  })
}

/** 退出登录、换了人时清空；打开卡片就算看过了。挂在独立的作用域里：卡片、图标卸载了（去管理后台）也一直管着 */
function wire() {
  if (wired) return
  wired = true
  const user = useUserStore()
  effectScope(true).run(() => {
    watch(
      () => user.info?.id ?? null,
      (now, before) => {
        if (now !== before) reset()
      },
    )
    watch(
      () => state.open,
      (open) => {
        if (open) state.unread = false
      },
    )
  })
  onSessionChanged(reset)
}

/**
 * 拿助手令牌。gen 是发起时的那一代：换令牌的请求在路上时退出了、别的页签换了人，回来的令牌是上一个人的，
 * 不写进缓存、抛 Stale（审查 T-c5c68e #1）。signal：点"停止"连换令牌一起停
 */
async function freshToken(force: boolean, gen: number, signal?: AbortSignal): Promise<string> {
  if (gen !== generation || sessionChanged()) throw new Stale()
  if (!force && token && token.expiresAt - Date.now() > 5 * 60_000) return token.value
  token = null
  const got = await authApi.assistantToken({ silent: true, signal })
  if (gen !== generation || sessionChanged()) throw new Stale()
  const at = Date.parse(got.expiresAt)
  token = { value: got.token, expiresAt: Number.isFinite(at) ? at : Date.now() + 25 * 60_000 }
  return token.value
}

/** 带着令牌调一次，助手服务回 401（令牌过期、换它的登录退出了）就换一张再调一次；已经清空了就不再换 */
async function withToken<T>(gen: number, call: (token: string) => Promise<T>): Promise<T> {
  try {
    return await call(await freshToken(false, gen))
  } catch (e) {
    if (!(e instanceof AssistantError) || e.status !== 401) throw e
    return call(await freshToken(true, gen))
  }
}

function messageOf(e: unknown, fallback: string): string {
  return e instanceof AssistantError || e instanceof ApiError ? e.message : fallback
}

async function checkHealth(force = false) {
  if (state.checking) return
  if (!force && state.status !== 'unknown' && Date.now() - state.checkedAt < 30_000) return
  const gen = generation
  state.checking = true
  try {
    const [health, info] = await Promise.all([assistantApi.health(), assistantApi.info().catch(() => null)])
    if (gen !== generation) return
    state.mode = info?.model.mode ?? null
    state.retentionDays = info?.retentionDays ?? null
    state.status = health.model === 'configured' ? 'up' : 'noModel'
    state.statusMessage = health.model === 'configured' ? '' : '还没有配置模型，请联系管理员'
  } catch (e) {
    if (gen !== generation) return
    state.status = 'down'
    state.statusMessage = messageOf(e, '连不上智能助手')
  } finally {
    if (gen === generation) {
      state.checking = false
      state.checkedAt = Date.now()
    }
  }
}

function onEvent(turn: ChatTurn, ev: AssistantEvent) {
  switch (ev.event) {
    case 'meta':
      state.conversationId = ev.data.conversationId
      break
    case 'tool':
      if (ev.data.status === 'running') {
        turn.tools.push({ name: ev.data.name, label: ev.data.label || ev.data.name, status: 'running', rows: null })
      } else {
        // 工具一个接一个查：查完的是最近一条同名、还在查的
        for (let i = turn.tools.length - 1; i >= 0; i--) {
          const step = turn.tools[i]!
          if (step.name === ev.data.name && step.status === 'running') {
            step.status = ev.data.status
            step.rows = ev.data.rows ?? null
            break
          }
        }
      }
      break
    case 'delta':
      turn.draft += ev.data.text
      break
    case 'done':
      turn.answer = ev.data.text
      turn.state = 'done'
      break
    case 'error':
      turn.error = ev.data.message
      turn.state = 'failed'
      break
  }
}

function fail(turn: ChatTurn, message: string) {
  turn.error = message
  turn.state = 'failed'
}

async function run(turn: ChatTurn, question: string, signal: AbortSignal, gen: number) {
  for (let attempt = 0; attempt < 2; attempt++) {
    let tok: string
    try {
      tok = await freshToken(attempt > 0, gen, signal)
    } catch (e) {
      if (e instanceof Stale || gen !== generation) return
      if (signal.aborted) turn.state = 'stopped'
      else fail(turn, messageOf(e, '换不到助手的令牌，稍后再试'))
      return
    }
    if (gen !== generation) return
    if (signal.aborted) {
      turn.state = 'stopped'
      return
    }
    let ended = false
    try {
      await assistantApi.chat(tok, { message: question, conversationId: state.conversationId }, (ev) => {
        // 清空了、点了"新对话"：这一问已经不在页面上，后面到的事件（比如 meta 带的对话 id）不要
        if (gen !== generation || !state.turns.includes(turn)) return
        onEvent(turn, ev)
        if (ev.event === 'done' || ev.event === 'error') ended = true
      }, signal)
      if (gen === generation && !ended) {
        if (signal.aborted) turn.state = 'stopped'
        else fail(turn, '连接断了，这句没答完，再问一次')
      }
      return
    } catch (e) {
      if (gen !== generation) return
      if (signal.aborted) {
        turn.state = turn.state === 'asking' ? 'stopped' : turn.state
        return
      }
      if (e instanceof AssistantError) {
        if (e.status === 401 && attempt === 0) continue
        if (e.status === 404 && state.conversationId) {
          state.conversationId = null
          fail(turn, '这个对话找不到了，已经换成新对话，再问一次')
          return
        }
        // 没启动、没配模型、在重启：重新查一下服务，入口跟着标"暂时用不了"
        if (e.status === 502 || e.status === 503) void checkHealth(true)
        fail(turn, e.message)
        return
      }
      fail(turn, '连接断了，这句没答完，再问一次')
      return
    }
  }
}

/**
 * 打开卡片、发出这句话。空的不发；上一句还在答的（⌘K 里问的时候卡片可能收着），卡片照样打开、
 * 这句放进输入框等着（输入框里已经有字的不覆盖），返回 false（审查 T-c5c68e #8）
 */
async function ask(text: string): Promise<boolean> {
  const question = text.trim()
  if (!question) return false
  state.open = true
  void checkHealth()
  if (state.busy) {
    if (!state.draft.trim()) state.draft = question
    return false
  }
  viewSeq++
  state.openError = ''
  state.turns.push({ id: ++seq, question, draft: '', answer: null, tools: [], error: null, state: 'asking' })
  const turn = state.turns[state.turns.length - 1]! // 拿响应式的那份，改了页面才跟着变
  const gen = generation
  const ctrl = new AbortController()
  controller = ctrl
  state.busy = true
  try {
    await run(turn, question, ctrl.signal, gen)
  } finally {
    if (gen === generation) {
      state.busy = false
      if (controller === ctrl) controller = null
      // 停了、断了：还在转圈的工具收个尾（服务端中止时不再发它的 ok / failed，审查 T-c5c68e #5）
      for (const step of turn.tools) {
        if (step.status === 'running') step.status = turn.state === 'failed' ? 'failed' : 'stopped'
      }
      if (turn.state === 'asking') turn.state = 'stopped'
      if (!state.open && turn.state !== 'stopped') state.unread = true
    }
  }
  return true
}

/** 停掉正在答的（助手服务那边跟着取消模型调用，这句记成"用户中途停止"） */
function stop() {
  controller?.abort()
}

function newChat() {
  stop()
  viewSeq++
  state.turns = []
  state.conversationId = null
  state.openError = ''
}

async function loadHistory() {
  const gen = generation
  const mine = ++historySeq
  const latest = () => gen === generation && mine === historySeq
  state.historyLoading = true
  state.historyError = ''
  try {
    const list = await withToken(gen, (tok) => assistantApi.conversations(tok))
    if (latest()) state.history = list
  } catch (e) {
    if (latest() && !(e instanceof Stale)) state.historyError = messageOf(e, '最近的对话没加载出来')
  } finally {
    if (latest()) state.historyLoading = false
  }
}

/** 问答一条条配成对：没答出来的那一问也留着，显示原因 */
function toTurns(rows: AssistantMessage[]): ChatTurn[] {
  const turns: ChatTurn[] = []
  for (const row of rows) {
    if (row.role === 'user') {
      turns.push({ id: ++seq, question: row.content, draft: '', answer: null, tools: [], error: '这句没答完', state: 'failed' })
      continue
    }
    const turn = turns.at(-1)
    if (!turn || turn.state !== 'failed' || turn.answer !== null) continue
    if (row.error === null && row.content) {
      turn.answer = row.content
      turn.error = null
      turn.state = 'done'
    } else if (row.error === '用户中途停止') {
      turn.error = null
      turn.state = 'stopped'
    } else {
      turn.error = row.error ?? '这句没答完'
    }
  }
  return turns
}

async function openConversation(id: string) {
  if (state.busy) return
  const gen = generation
  const view = ++viewSeq
  const latest = () => gen === generation && view === viewSeq && !state.busy
  state.openError = ''
  openingId = id
  try {
    const rows = await withToken(gen, (tok) => assistantApi.messages(tok, id))
    if (!latest()) return
    state.turns = toTurns(rows)
    state.conversationId = id
  } catch (e) {
    if (latest() && !(e instanceof Stale)) state.openError = messageOf(e, '这个对话没打开')
  } finally {
    if (view === viewSeq) openingId = null
  }
}

/**
 * 删掉自己的一个对话（找不回来，调用方先确认）。删的是卡片里正开着的，卡片清空成新对话。
 * 成功返回 true；没删成抛 AssistantError（消息能直接给人看）
 */
async function deleteConversation(id: string): Promise<boolean> {
  const gen = generation
  try {
    await withToken(gen, (tok) => assistantApi.deleteConversation(tok, id))
  } catch (e) {
    // 删的途中退出、换了人：不报给现在这个人（报出来是上一个人的事，或者是请求被中止的英文 canceled）
    if (gen !== generation || e instanceof Stale) return false
    throw e
  }
  if (gen !== generation || sessionChanged()) return false
  state.history = state.history.filter((c) => c.id !== id)
  // 正在打开的就是它：作废那次打开，迟到的结果不再把删掉的对话显示出来
  if (openingId === id) {
    viewSeq++
    openingId = null
  }
  if (state.conversationId === id && !state.busy) {
    viewSeq++
    state.turns = []
    state.conversationId = null
    state.openError = ''
  }
  return true
}

export function useAssistant() {
  wire()
  const user = useUserStore()
  const enabled = computed(() => user.info?.features?.ai === true && user.hasPerm(ASSISTANT_PERM))
  /** 查过、确实用不了（没启动、没配模型）：图标变灰、提示"暂时用不了"，卡片里不让发 */
  const unavailable = computed(() => state.status === 'down' || state.status === 'noModel')

  function show() {
    state.open = true
    void checkHealth()
  }
  function hide() {
    state.open = false
  }
  function toggle() {
    if (state.open) hide()
    else show()
  }

  return { state, enabled, unavailable, show, hide, toggle, ask, stop, newChat, checkHealth, loadHistory, openConversation, deleteConversation }
}
