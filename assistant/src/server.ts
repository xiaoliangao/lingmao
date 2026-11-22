import { randomUUID } from 'node:crypto'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { tokenScope, type AppClient, type Me } from './appClient.ts'
import { answer, ChatRejected, cleanMessage, conversationIdOf, prepare, SHUTDOWN, type ChatDeps } from './chat.ts'
import type { LlmConfig } from './config.ts'
import type { Store } from './store.ts'

/**
 * 助手服务的接口，Nginx 把 /assistant/ 原样转过来（RFC 009 §3.1）。返回体和 app 一样是 { code, msg, data }，问话是 SSE。
 *
 *   GET  /assistant/health                 不用登录：活着没有、库连得上没有、模型配了没有
 *   GET  /assistant/info                   不用登录：模型是外部的还是自部署的（打开 AI 开关前的弹窗要显示，RFC 017 §3.7）
 *   POST /assistant/chat                   问一句：{ conversationId?, message }，回 SSE（meta / tool / delta / done / error）
 *   GET  /assistant/conversations          我最近的 20 个对话
 *   GET  /assistant/conversations/{id}     一个对话的全部问答（只有本人能看）
 *
 * 认人：请求头 Authorization: Bearer <助手令牌>，原样拿去调 app 的 /api/auth/me（app 验签、查库，令牌过期回 401）。
 * app 只对助手令牌查 AI 开关和使用权限，普通登录令牌它照样认；所以这里自己再查三样，缺一样就 403：
 * 令牌的用途是 ASSISTANT（不收普通登录令牌，助手手里不能有能写的令牌）、AI 功能开着、有 assistant:chat:use 权限
 */

export interface ServerDeps {
  chat: Omit<ChatDeps, 'llm'> & { llm: ChatDeps['llm'] | null }
  app: AppClient
  store: Store
  llmConfig: LlmConfig | null
}

export interface AssistantServer {
  server: Server
  /** 停服务：不接新请求；正在答的最多等 graceMs，到点还没答完的中止（记成"服务在重启"并告诉浏览器），然后断开所有连接 */
  shutdown(graceMs: number): Promise<void>
}

const MAX_BODY = 16 * 1024
export const CHAT_PERM = 'assistant:chat:use'

export function buildServer(deps: ServerDeps): AssistantServer {
  /** 正在答的问话：用户 id → 中止用的控制器 */
  const active = new Map<string, AbortController>()
  let stopping = false

  const server = createServer((req, res) => {
    const requestId = randomUUID()
    if (stopping) res.setHeader('Connection', 'close')
    route(deps, req, res, requestId, { active, stopping: () => stopping }).catch((e: unknown) => {
      console.error(JSON.stringify({ level: 'ERROR', msg: 'request failed', requestId, path: req.url, error: String(e) }))
      if (!res.headersSent) json(res, 500, 500, '助手出错了，错误编号 ' + requestId)
      else res.end()
    })
  })

  const waitIdle = async (ms: number) => {
    const until = Date.now() + ms
    while (active.size > 0 && Date.now() < until) {
      server.closeIdleConnections()
      await new Promise((r) => setTimeout(r, 50))
    }
  }

  return {
    server,
    async shutdown(graceMs: number) {
      stopping = true
      server.close()
      server.closeIdleConnections()
      await waitIdle(graceMs)
      for (const c of active.values()) c.abort(SHUTDOWN)
      await waitIdle(3_000)
      server.closeAllConnections()
    },
  }
}

interface Runtime {
  active: Map<string, AbortController>
  stopping: () => boolean
}

async function route(deps: ServerDeps, req: IncomingMessage, res: ServerResponse, requestId: string, rt: Runtime): Promise<void> {
  const url = new URL(req.url ?? '/', 'http://assistant')
  const path = url.pathname
  res.setHeader('X-Request-Id', requestId)
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Cache-Control', 'no-store')

  if (req.method === 'GET' && path === '/assistant/health') {
    try {
      await deps.store.ping()
    } catch {
      return json(res, 503, 503, '连不上助手的数据库', { status: 'DOWN', db: 'down', model: deps.llmConfig ? 'configured' : 'none' })
    }
    return json(res, 200, 0, 'ok', { status: 'UP', db: 'up', model: deps.llmConfig ? 'configured' : 'none' })
  }
  if (req.method === 'GET' && path === '/assistant/info') {
    // 不给地址和主机名：这个接口不用登录，自部署模型的内网地址不往外说
    return json(res, 200, 0, 'ok', { model: { mode: deps.llmConfig?.mode ?? 'none' } })
  }

  const conv = /^\/assistant\/conversations(?:\/([^/]+))?$/.exec(path)
  const isChat = path === '/assistant/chat'
  if (!conv && !isChat) return json(res, 404, 404, '没有这个地址')
  if (isChat && req.method !== 'POST') return json(res, 405, 405, '只能 POST')
  if (conv && req.method !== 'GET') return json(res, 405, 405, '只能 GET')

  const token = bearer(req)
  if (!token) return json(res, 401, 401, '未登录或登录已过期')
  const disconnect = new AbortController()
  res.on('close', () => disconnect.abort())
  let who: { reply: { status: number; code: number | null; msg: string }; me: Me | null }
  try {
    who = await deps.app.me(token, requestId, disconnect.signal)
  } catch {
    if (disconnect.signal.aborted) return
    return json(res, 502, 502, '连不上系统，稍后再试')
  }
  if (!who.me) {
    const code = who.reply.code ?? who.reply.status
    const status = who.reply.status >= 400 ? who.reply.status : code === 401 || code === 403 ? code : 502
    return json(res, status, code, who.reply.msg || '认不出是谁')
  }
  const me = who.me
  // app 认过这个令牌（验签、查库），它的载荷可信；用途不是 ASSISTANT 的（普通登录令牌）不收
  if (tokenScope(token) !== 'ASSISTANT') return json(res, 403, 403, '要用助手令牌（POST /api/auth/assistant-token 换），不收登录令牌')
  if (me.features?.ai !== true) return json(res, 403, 403, 'AI 功能没有打开')
  if (!me.perms?.includes(CHAT_PERM)) return json(res, 403, 403, '没有使用智能助手的权限')

  if (conv) {
    const raw = conv[1]
    if (raw === undefined) return json(res, 200, 0, 'ok', await deps.store.listConversations(me.id, 20))
    const id = conversationIdOf(raw)
    if (!id || !(await deps.store.ownsConversation(id, me.id))) return json(res, 404, 404, '这个对话不存在')
    return json(res, 200, 0, 'ok', await deps.store.messages(id))
  }

  // 问话
  const llm = deps.chat.llm
  if (!llm) return json(res, 503, 503, '还没有配置模型，请联系管理员')
  if (rt.stopping()) return json(res, 503, 503, '助手正在重启，过一会儿再问')
  const body = await readJson(req)
  if (body === null || disconnect.signal.aborted) return // 浏览器发到一半走了
  // 请求体发得慢、发完时已经在停机：不再开始（停机时只中止已经在答的，这一句没人中止，审查 R-02）
  if (rt.stopping()) return json(res, 503, 503, '助手正在重启，过一会儿再问')
  if (!body.ok) return json(res, 400, 400, body.message)
  const value = body.value
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return json(res, 400, 400, '请求体要是 { message, conversationId? }')
  const { message, conversationId } = value as { message?: unknown; conversationId?: unknown }
  if (typeof message !== 'string') return json(res, 400, 400, '要有 message')
  if (conversationId !== undefined && conversationId !== null && typeof conversationId !== 'string') return json(res, 400, 400, 'conversationId 不对')
  let convId: string | null = null
  if (typeof conversationId === 'string') {
    convId = conversationIdOf(conversationId)
    if (!convId) return json(res, 404, 404, '这个对话不存在')
  }

  // 同时在答的：每人一句，全系统 maxConcurrent 句。占不到位置不扣今天的次数
  if (rt.active.has(me.id)) return json(res, 429, 429, '上一句还在答，等它答完（或点停止）再问')
  if (rt.active.size >= deps.chat.limits.maxConcurrent) return json(res, 429, 429, '现在问的人多，稍等几秒再问')
  rt.active.set(me.id, disconnect)
  try {
    const chatReq = { token, me, requestId, conversationId: convId, message: cleanMessage(message) }
    const chatDeps: ChatDeps = { ...deps.chat, llm }
    let prepared
    try {
      prepared = await prepare(chatDeps, chatReq)
    } catch (e) {
      if (e instanceof ChatRejected) return json(res, e.status, e.status, e.message)
      throw e
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Accel-Buffering': 'no', // Nginx 不攒着，一段段往外推
    })
    const emit = (event: string, data: Record<string, unknown>) => {
      if (!res.writableEnded) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    }
    emit('meta', { conversationId: prepared.conversationId, created: prepared.created })
    console.log(JSON.stringify({ level: 'INFO', msg: 'chat', requestId, userId: me.id, conversationId: prepared.conversationId }))
    await answer(chatDeps, chatReq, prepared.conversationId, emit, disconnect.signal)
    res.end()
  } finally {
    if (rt.active.get(me.id) === disconnect) rt.active.delete(me.id)
  }
}

function bearer(req: IncomingMessage): string | null {
  const h = req.headers.authorization
  if (!h || !h.startsWith('Bearer ')) return null
  const t = h.slice(7).trim()
  return t.length > 0 && t.length < 4096 ? t : null
}

/** 读 JSON 请求体，最多 16KB。浏览器发到一半断开返回 null（不算出错，不记日志）；慢慢发的由 server.requestTimeout 掐断 */
async function readJson(req: IncomingMessage): Promise<{ ok: true; value: unknown } | { ok: false; message: string } | null> {
  if (!(req.headers['content-type'] ?? '').includes('application/json')) return { ok: false, message: '要用 JSON' }
  const chunks: Buffer[] = []
  let size = 0
  try {
    for await (const chunk of req) {
      size += (chunk as Buffer).length
      if (size > MAX_BODY) return { ok: false, message: '内容太长' }
      chunks.push(chunk as Buffer)
    }
  } catch {
    return null
  }
  try {
    return { ok: true, value: JSON.parse(Buffer.concat(chunks).toString('utf8')) }
  } catch {
    return { ok: false, message: '不是合法的 JSON' }
  }
}

function json(res: ServerResponse, status: number, code: number, msg: string, data: unknown = null): void {
  if (res.headersSent || res.destroyed) return
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify({ code, msg, data }))
}
