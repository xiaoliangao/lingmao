import { randomUUID } from 'node:crypto'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AppClient, Me } from './appClient.ts'
import { answer, ChatRejected, prepare, type ChatDeps } from './chat.ts'
import type { LlmConfig } from './config.ts'
import type { Store } from './store.ts'

/**
 * 助手服务的接口，Nginx 把 /assistant/ 原样转过来（RFC 009 §3.1）。返回体和 app 一样是 { code, msg, data }，问话是 SSE。
 *
 *   GET  /assistant/health                 不用登录：活着没有、模型配了没有
 *   GET  /assistant/info                   不用登录：模型用哪种方式、地址的主机名（打开 AI 开关前的弹窗要显示，RFC 017 §3.7）
 *   POST /assistant/chat                   问一句：{ conversationId?, message }，回 SSE（meta / tool / delta / done / error）
 *   GET  /assistant/conversations          我最近的 20 个对话
 *   GET  /assistant/conversations/{id}     一个对话的全部问答（只有本人能看）
 *
 * 认人：请求头 Authorization: Bearer <助手令牌>，原样拿去调 app 的 /api/auth/me。
 * 令牌过期、AI 功能关了、没有使用助手的权限，app 回 401 / 403，这里原样回给浏览器
 */

export interface ServerDeps {
  chat: Omit<ChatDeps, 'llm'> & { llm: ChatDeps['llm'] | null }
  app: AppClient
  store: Store
  llmConfig: LlmConfig | null
}

const MAX_BODY = 16 * 1024

export function buildServer(deps: ServerDeps): Server {
  return createServer((req, res) => {
    const requestId = randomUUID()
    route(deps, req, res, requestId).catch((e: unknown) => {
      console.error(JSON.stringify({ level: 'ERROR', msg: 'request failed', requestId, path: req.url, error: String(e) }))
      if (!res.headersSent) json(res, 500, 500, '助手出错了，错误编号 ' + requestId)
      else res.end()
    })
  })
}

async function route(deps: ServerDeps, req: IncomingMessage, res: ServerResponse, requestId: string): Promise<void> {
  const url = new URL(req.url ?? '/', 'http://assistant')
  const path = url.pathname
  res.setHeader('X-Request-Id', requestId)
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Cache-Control', 'no-store')

  if (req.method === 'GET' && path === '/assistant/health') {
    return json(res, 200, 0, 'ok', { status: 'UP', model: deps.llmConfig ? 'configured' : 'none' })
  }
  if (req.method === 'GET' && path === '/assistant/info') {
    const c = deps.llmConfig
    return json(res, 200, 0, 'ok', { model: c ? { mode: c.mode, host: new URL(c.baseUrl).hostname } : { mode: 'none', host: null } })
  }

  const conv = /^\/assistant\/conversations(?:\/([0-9a-f-]{36}))?$/.exec(path)
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
    return json(res, 502, 502, '连不上系统，稍后再试')
  }
  if (!who.me) {
    const code = who.reply.code ?? who.reply.status
    const status = who.reply.status >= 400 ? who.reply.status : code === 401 || code === 403 ? code : 502
    return json(res, status, code, who.reply.msg || '认不出是谁')
  }
  const me = who.me

  if (conv) {
    const id = conv[1]
    if (!id) return json(res, 200, 0, 'ok', await deps.store.listConversations(me.id, 20))
    if (!(await deps.store.ownsConversation(id, me.id))) return json(res, 404, 404, '这个对话不存在')
    return json(res, 200, 0, 'ok', await deps.store.messages(id))
  }

  // 问话
  const llm = deps.chat.llm
  if (!llm) return json(res, 503, 503, '还没有配置模型，请联系管理员')
  const body = await readJson(req)
  if (!body.ok) return json(res, 400, 400, body.message)
  const { message, conversationId } = body.value as { message?: unknown; conversationId?: unknown }
  if (typeof message !== 'string') return json(res, 400, 400, '要有 message')
  if (conversationId !== undefined && conversationId !== null && typeof conversationId !== 'string') return json(res, 400, 400, 'conversationId 不对')
  const chatReq = { token, me, requestId, conversationId: (conversationId as string | null | undefined) ?? null, message }
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
    Connection: 'keep-alive',
  })
  const emit = (event: string, data: Record<string, unknown>) => {
    if (!res.writableEnded) res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
  }
  emit('meta', { conversationId: prepared.conversationId, created: prepared.created })
  console.log(JSON.stringify({ level: 'INFO', msg: 'chat', requestId, userId: me.id, conversationId: prepared.conversationId }))
  await answer(chatDeps, chatReq, prepared.conversationId, emit, disconnect.signal)
  res.end()
}

function bearer(req: IncomingMessage): string | null {
  const h = req.headers.authorization
  if (!h || !h.startsWith('Bearer ')) return null
  const t = h.slice(7).trim()
  return t.length > 0 && t.length < 4096 ? t : null
}

async function readJson(req: IncomingMessage): Promise<{ ok: true; value: unknown } | { ok: false; message: string }> {
  if (!(req.headers['content-type'] ?? '').includes('application/json')) return { ok: false, message: '要用 JSON' }
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    size += (chunk as Buffer).length
    if (size > MAX_BODY) return { ok: false, message: '内容太长' }
    chunks.push(chunk as Buffer)
  }
  try {
    return { ok: true, value: JSON.parse(Buffer.concat(chunks).toString('utf8')) }
  } catch {
    return { ok: false, message: '不是合法的 JSON' }
  }
}

function json(res: ServerResponse, status: number, code: number, msg: string, data: unknown = null): void {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify({ code, msg, data }))
}
