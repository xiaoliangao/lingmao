import assert from 'node:assert/strict'
import { request } from 'node:http'
import type { AddressInfo } from 'node:net'
import { after, before, describe, test } from 'node:test'
import type { LlmConfig } from '../src/config.ts'
import type { ChatMessage, LlmProvider, LlmTurn, ToolSpec } from '../src/llm.ts'
import { buildServer, type AssistantServer } from '../src/server.ts'
import { BUYER, fakeApp, jwt, LIMITS, MemoryStore, ok, ScriptedLlm, text, TOOLS } from './fakes.ts'

/** 返回体 { code, msg, data }；测试里 data 按需要取字段 */
type Body = { code: number; msg: string; data: any }
const body = async (res: Response | Promise<Response>): Promise<Body> => (await (await res).json()) as Body

const LLM_CONFIG: LlmConfig = { baseUrl: 'https://llm.internal.example/v1', apiKey: 'sk-secret', model: 'm', mode: 'self-hosted', streamUsage: true, timeoutMs: 5000 }

/** 几种令牌：助手令牌、普通登录令牌（app 照样认）、AI 开关关了、没有使用权限；另一个人；过期的 */
const T = {
  good: jwt({ scope: 'ASSISTANT' }),
  login: jwt({}),
  aiOff: jwt({ scope: 'ASSISTANT', k: 'ai-off' }),
  noPerm: jwt({ scope: 'ASSISTANT', k: 'no-perm' }),
  other: jwt({ scope: 'ASSISTANT', k: 'other' }),
  third: jwt({ scope: 'ASSISTANT', k: 'third' }),
  appSays403: jwt({ scope: 'ASSISTANT', k: '403' }),
}

const { app } = fakeApp({
  '/api/auth/me': (_url, auth) => {
    const t = auth?.slice(7)
    if (t === T.good || t === T.login) return ok(BUYER)
    if (t === T.aiOff) return ok({ ...BUYER, features: { ai: false } })
    if (t === T.noPerm) return ok({ ...BUYER, perms: ['purchase:order:list'] })
    if (t === T.other) return ok({ ...BUYER, id: '8', username: 'buyer2' })
    if (t === T.third) return ok({ ...BUYER, id: '9', username: 'buyer3' })
    if (t === T.appSays403) return { status: 403, body: { code: 403, msg: 'AI 功能没有打开', data: null } }
    return { status: 401, body: { code: 401, msg: '未登录或登录已过期', data: null } }
  },
})

/** 一直想、直到放行或被中止的模型：测同时在答、中途断开、停服务 */
class GateLlm implements LlmProvider {
  calls = 0
  aborted: unknown[] = []
  private waiters: (() => void)[] = []
  async chat(_m: ChatMessage[], _t: ToolSpec[], onDelta: (text: string) => void, signal: AbortSignal): Promise<LlmTurn> {
    this.calls++
    onDelta('想一想')
    await new Promise<void>((resolve, reject) => {
      this.waiters.push(resolve)
      signal.addEventListener('abort', () => {
        this.aborted.push(signal.reason)
        reject(new Error('aborted'))
      }, { once: true })
    })
    return text('好了')
  }
  releaseAll() {
    for (const w of this.waiters.splice(0)) w()
  }
}

async function until(cond: () => boolean, ms = 2000) {
  const end = Date.now() + ms
  while (!cond()) {
    if (Date.now() > end) throw new Error('等不到')
    await new Promise((r) => setTimeout(r, 10))
  }
}

async function start(llm: LlmProvider | null) {
  const store = new MemoryStore()
  const s: AssistantServer = buildServer({ chat: { llm, app, store: store.asStore(), tools: TOOLS, limits: LIMITS, systemName: '服装 SCM' }, app, store: store.asStore(), llmConfig: llm ? LLM_CONFIG : null })
  await new Promise<void>((resolve) => s.server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${(s.server.address() as AddressInfo).port}`
  const chat = (token: string | null, payload: unknown, init: RequestInit = {}) =>
    fetch(`${base}/assistant/chat`, {
      method: 'POST',
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': 'application/json' },
      body: typeof payload === 'string' ? payload : JSON.stringify(payload),
      ...init,
    })
  const get = (path: string, token: string | null = T.good) => fetch(`${base}${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  return { ...s, store, base, chat, get, close: () => s.server.closeAllConnections() }
}

const eventsOf = async (res: Response) => [...(await res.text()).matchAll(/event: (\w+)\ndata: (.*)\n\n/g)].map((m) => [m[1], JSON.parse(m[2]!)] as const)

describe('助手服务的接口', () => {
  const llm = new ScriptedLlm([text('你好，有什么要查的？'), text('第二句'), text('第三句')])
  let s: Awaited<ReturnType<typeof start>>
  before(async () => {
    s = await start(llm)
  })
  after(() => {
    s.server.close()
    s.close()
  })

  test('health 查库；info 只说模型是哪种，不给 Key、地址、主机名', async () => {
    assert.deepEqual(await body(s.get('/assistant/health', null)), { code: 0, msg: 'ok', data: { status: 'UP', db: 'up', model: 'configured' } })
    const info = await (await s.get('/assistant/info', null)).text()
    assert.deepEqual(JSON.parse(info).data, { model: { mode: 'self-hosted' } })
    assert.ok(!info.includes('sk-secret') && !info.includes('llm.internal'))
    s.store.dbDown = true
    try {
      const down = await s.get('/assistant/health', null)
      assert.equal(down.status, 503)
      assert.equal((await body(down)).data.db, 'down')
    } finally {
      s.store.dbDown = false
    }
  })

  test('没带令牌 401；app 说令牌过期、AI 关了，原样回 401 / 403', async () => {
    assert.equal((await s.chat(null, { message: '你好' })).status, 401)
    assert.equal((await s.chat('expired', { message: '你好' })).status, 401)
    const off = await s.chat(T.appSays403, { message: '你好' })
    assert.equal(off.status, 403)
    assert.equal((await body(off)).msg, 'AI 功能没有打开')
    assert.equal(llm.seen.length, 0, '认不出人就不调模型')
  })

  test('普通登录令牌、AI 开关关着、没有使用权限：一律 403，不调模型、看不到对话（app 对这几种不拦，助手自己查）', async () => {
    for (const [token, msg] of [[T.login, /助手令牌/], [T.aiOff, /AI 功能没有打开/], [T.noPerm, /没有使用智能助手的权限/]] as const) {
      const res = await s.chat(token, { message: '你好' })
      assert.equal(res.status, 403)
      assert.match((await body(res)).msg, msg)
      assert.equal((await s.get('/assistant/conversations', token)).status, 403)
    }
    assert.equal(llm.seen.length, 0)
    assert.equal(s.store.requests.size, 0, '不扣次数')
  })

  test('问一句：SSE 依次推 meta、delta、done；不让 Nginx 攒着；大写的对话 id 也认', async () => {
    const res = await s.chat(T.good, { message: '你好' })
    assert.equal(res.status, 200)
    assert.match(res.headers.get('content-type') ?? '', /text\/event-stream/)
    assert.equal(res.headers.get('x-accel-buffering'), 'no')
    const events = await eventsOf(res)
    assert.deepEqual(events.map(([e]) => e), ['meta', 'delta', 'done'])
    assert.equal(events[0]![1].created, true)
    assert.equal(events[2]![1].text, '你好，有什么要查的？')

    const id = events[0]![1].conversationId as string
    const list = await body(s.get('/assistant/conversations'))
    assert.deepEqual(list.data.map((c: { id: string }) => c.id), [id])
    const one = await body(s.get(`/assistant/conversations/${id.toUpperCase()}`))
    assert.deepEqual(one.data.map((m: { role: string }) => m.role), ['user', 'assistant'])
    const again = await s.chat(T.good, { message: '接着问', conversationId: id.toUpperCase() })
    assert.equal(again.status, 200)
    assert.equal((await eventsOf(again))[0]![1].conversationId, id)
  })

  test('别人的对话、不合规的 id：看不到、接着问也不行，一律 404（不拿去查库报 500）', async () => {
    await s.store.createConversation('22222222-2222-2222-2222-222222222222', '99', '别人的')
    assert.equal((await s.get('/assistant/conversations/22222222-2222-2222-2222-222222222222')).status, 404)
    assert.equal((await s.chat(T.good, { message: '接着问', conversationId: '22222222-2222-2222-2222-222222222222' })).status, 404)
    for (const bad of ['-'.repeat(36), 'not-a-uuid', 'g'.repeat(36)]) {
      assert.equal((await s.get(`/assistant/conversations/${bad}`)).status, 404)
      assert.equal((await s.chat(T.good, { message: '接着问', conversationId: bad })).status, 404)
    }
  })

  test('请求体：不是 JSON、太长、缺 message、null、数组都是 400；问题里的 \\u0000 去掉照常答', async () => {
    const form = await fetch(`${s.base}/assistant/chat`, { method: 'POST', headers: { Authorization: `Bearer ${T.good}`, 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'message=hi' })
    assert.equal(form.status, 400)
    for (const bad of ['{oops', JSON.stringify({ message: 'x'.repeat(17 * 1024) }), JSON.stringify({ text: '你好' }), 'null', '[]']) {
      assert.equal((await s.chat(T.good, bad)).status, 400, bad.slice(0, 20))
    }
    const nul = await s.chat(T.other, { message: '你\u0000好' })
    assert.equal(nul.status, 200)
    await nul.text()
    assert.ok(s.store.messagesById.some((m) => m.content === '你好'))
  })

  test('方法、地址不对：405、404', async () => {
    assert.equal((await s.get('/assistant/chat')).status, 405)
    assert.equal((await s.get('/assistant/nothing', null)).status, 404)
    assert.equal((await s.get('/api/orders', null)).status, 404, '助手服务不转发 app 的接口')
  })
})

describe('同时在答、中途断开、停服务', () => {
  test('每人同时只能一句，全系统有上限；占不到位置不扣次数', async () => {
    const llm = new GateLlm()
    const s = await start(llm)
    try {
      const first = s.chat(T.good, { message: '第一句' })
      await until(() => llm.calls === 1)
      const second = await s.chat(T.good, { message: '第二句' })
      assert.equal(second.status, 429)
      assert.match((await body(second)).msg, /上一句还在答/)
      assert.equal(s.store.requests.get('7'), 1)

      const other = s.chat(T.other, { message: '另一个人' })
      await until(() => llm.calls === 2)
      assert.equal(llm.calls, 2, '别人不受这个人的影响')
      llm.releaseAll()
      assert.equal((await first).status, 200)
      await (await first).text().catch(() => '')
      await (await other).text().catch(() => '')
      const after = await s.chat(T.good, { message: '答完了再问' })
      assert.equal(after.status, 200, '答完让出位置')
      llm.releaseAll()
      await after.text()
    } finally {
      llm.releaseAll()
      s.server.close()
      s.close()
    }
  })

  test('全系统同时在答到上限（这里是 2）：第三个人 429', async () => {
    const llm = new GateLlm()
    const s = await start(llm)
    try {
      const a = s.chat(T.good, { message: 'a' })
      const b = s.chat(T.other, { message: 'b' })
      await until(() => llm.calls === 2)
      const full = await s.chat(T.third, { message: '满了' })
      assert.equal(full.status, 429)
      assert.match((await body(full)).msg, /问的人多/)
      llm.releaseAll()
      await (await a).text()
      await (await b).text()
    } finally {
      llm.releaseAll()
      s.server.close()
      s.close()
    }
  })

  test('浏览器中途断开：模型调用被取消，记成"用户中途停止"，位置让出来', async () => {
    const llm = new GateLlm()
    const s = await start(llm)
    try {
      const stop = new AbortController()
      const res = await s.chat(T.good, { message: '查个大的' }, { signal: stop.signal })
      await until(() => llm.calls === 1)
      stop.abort()
      await res.text().catch(() => '')
      await until(() => s.store.messagesById.some((m) => m.role === 'assistant'))
      assert.equal(s.store.messagesById.find((m) => m.role === 'assistant')!.error, '用户中途停止')
      assert.equal(llm.aborted.length, 1)
      const next = s.chat(T.good, { message: '再问' })
      await until(() => llm.calls === 2)
      llm.releaseAll()
      assert.equal((await next).status, 200)
      await (await next).text()
    } finally {
      llm.releaseAll()
      s.server.close()
      s.close()
    }
  })

  test('停服务：不接新问话；到点没答完的中止，浏览器收到 error、库里记"服务在重启"，连接都断开', async () => {
    const llm = new GateLlm()
    const s = await start(llm)
    const res = await s.chat(T.good, { message: '慢慢答' })
    await until(() => llm.calls === 1)
    const reading = res.text()
    const started = Date.now()
    await s.shutdown(100)
    assert.ok(Date.now() - started < 3000, '不等满 keep-alive 才关')
    const events = [...(await reading).matchAll(/event: (\w+)\ndata: (.*)\n\n/g)].map((m) => [m[1], JSON.parse(m[2]!)] as const)
    assert.deepEqual(events.map(([e]) => e), ['meta', 'delta', 'error'])
    assert.match(String(events[2]![1].message), /服务在重启/)
    assert.equal(s.store.messagesById.find((m) => m.role === 'assistant')!.error, '服务在重启，这句没答完，过一会儿再问')
    await assert.rejects(s.chat(T.good, { message: '还在吗' }), '端口已经关了')
  })

  test('停服务时请求体才发完（审查 R-02）：不开始答，回 503；只中止停服务那一刻在答的', async () => {
    const llm = new GateLlm()
    const s = await start(llm)
    const a = await s.chat(T.good, { message: '慢慢答' })
    await until(() => llm.calls === 1)
    const payload = JSON.stringify({ message: '发得慢的一句' })
    const req = request(`${s.base}/assistant/chat`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${T.other}`, 'Content-Type': 'application/json', 'Content-Length': String(Buffer.byteLength(payload)) },
    })
    const replied = new Promise<{ status: number; body: string }>((resolve, reject) => {
      req.on('response', (r) => {
        let d = ''
        r.on('data', (c: Buffer) => (d += c))
        r.on('end', () => resolve({ status: r.statusCode!, body: d }))
      })
      req.on('error', reject)
    })
    req.write(payload.slice(0, 5))
    await new Promise((r) => setTimeout(r, 50)) // 认完人、在等请求体
    const stopping = s.shutdown(100)
    req.end(payload.slice(5))
    const b = await replied
    assert.equal(b.status, 503)
    assert.match(b.body, /正在重启/)
    await stopping
    await a.text().catch(() => '')
    assert.equal(llm.calls, 1)
  })
})

describe('没配模型', () => {
  test('服务照样起，问话回 503 说清楚', async () => {
    const s = await start(null)
    try {
      assert.equal((await body(s.get('/assistant/health', null))).data.model, 'none')
      assert.deepEqual((await body(s.get('/assistant/info', null))).data, { model: { mode: 'none' } })
      const res = await s.chat(T.good, { message: '你好' })
      assert.equal(res.status, 503)
      assert.match((await body(res)).msg, /还没有配置模型/)
    } finally {
      s.server.close()
      s.close()
    }
  })
})
