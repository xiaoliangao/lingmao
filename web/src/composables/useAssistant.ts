import { computed, effectScope, reactive, watch } from 'vue'
import { AssistantError, assistantApi } from '@/api/assistant'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { AssistantConversation, AssistantEvent, AssistantInfo, AssistantMessage } from '@/types/assistant'
import { ApiError } from '@/utils/request'
import { onSessionChanged } from '@/utils/token'

/**
 * 智能助手的状态和动作（docs/api/08-智能助手.md）。模块级单例：侧边栏入口、⌘K、抽屉共用一份；
 * 切页签、去管理后台再回来，对话都还在。退出登录、换了人（别的页签登录了别人）时全部清掉。
 *
 *   const assistant = useAssistant()
 *   assistant.enabled        出不出入口（AI 开关开着、有 assistant:chat:use；只管显示，拦截在后端）
 *   assistant.state.open     抽屉开着没有；toggle() / show() / hide()
 *   assistant.ask('PO… 审了没有')   打开抽屉、发出这句话（在答的时候不发，返回 false）
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
  status: 'running' | 'ok' | 'failed'
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
  status: 'unknown' as AssistantStatus,
  /** 用不了的原因，抽屉里显示 */
  statusMessage: '',
  checking: false,
  checkedAt: 0,
  /** 模型是外部的还是内网的：空白页上说一句数据去哪 */
  mode: null as AssistantInfo['model']['mode'] | null,
  turns: [] as ChatTurn[],
  conversationId: null as string | null,
  /** 在答：一次只答一句 */
  busy: false,
  /** 输入框里还没发的话：关了抽屉再开还在 */
  draft: '',
  history: [] as AssistantConversation[],
  historyLoading: false,
  historyError: '',
})

let token: { value: string; expiresAt: number } | null = null
let controller: AbortController | null = null
/** 每次清空加一：清空以前发出去的请求回来了，结果不要 */
let generation = 0
let seq = 0
let wired = false

function reset() {
  generation++
  controller?.abort()
  controller = null
  token = null
  Object.assign(state, {
    open: false, status: 'unknown', statusMessage: '', checking: false, checkedAt: 0, mode: null,
    turns: [], conversationId: null, busy: false, draft: '', history: [], historyLoading: false, historyError: '',
  })
}

/** 退出登录、换了人时清空。挂在独立的作用域里：抽屉、侧边栏卸载了（去管理后台）也一直管着 */
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
  })
  onSessionChanged(reset)
}

async function freshToken(force: boolean): Promise<string> {
  if (!force && token && token.expiresAt - Date.now() > 5 * 60_000) return token.value
  token = null
  const got = await authApi.assistantToken({ silent: true })
  const at = Date.parse(got.expiresAt)
  token = { value: got.token, expiresAt: Number.isFinite(at) ? at : Date.now() + 25 * 60_000 }
  return token.value
}

/** 带着令牌调一次，助手服务回 401（令牌过期、换它的登录退出了）就换一张再调一次 */
async function withToken<T>(call: (token: string) => Promise<T>): Promise<T> {
  try {
    return await call(await freshToken(false))
  } catch (e) {
    if (!(e instanceof AssistantError) || e.status !== 401) throw e
    return call(await freshToken(true))
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
      tok = await freshToken(attempt > 0)
    } catch (e) {
      if (gen === generation) fail(turn, messageOf(e, '换不到助手的令牌，稍后再试'))
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

/** 打开抽屉、发出这句话。在答的时候、空的不发，返回 false */
async function ask(text: string): Promise<boolean> {
  const question = text.trim()
  if (!question || state.busy) return false
  state.open = true
  void checkHealth()
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
  state.turns = []
  state.conversationId = null
}

async function loadHistory() {
  const gen = generation
  state.historyLoading = true
  state.historyError = ''
  try {
    const list = await withToken((tok) => assistantApi.conversations(tok))
    if (gen === generation) state.history = list
  } catch (e) {
    if (gen === generation) state.historyError = messageOf(e, '最近的对话没加载出来')
  } finally {
    if (gen === generation) state.historyLoading = false
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
  state.historyError = ''
  try {
    const rows = await withToken((tok) => assistantApi.messages(tok, id))
    if (gen !== generation || state.busy) return
    state.turns = toTurns(rows)
    state.conversationId = id
  } catch (e) {
    if (gen === generation) state.historyError = messageOf(e, '这个对话没打开')
  }
}

export function useAssistant() {
  wire()
  const user = useUserStore()
  const enabled = computed(() => user.info?.features?.ai === true && user.hasPerm(ASSISTANT_PERM))
  /** 查过、确实用不了（没启动、没配模型）：入口标"暂时用不了"，抽屉里不让发 */
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

  return { state, enabled, unavailable, show, hide, toggle, ask, stop, newChat, checkHealth, loadHistory, openConversation }
}
