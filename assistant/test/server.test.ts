import assert from 'node:assert/strict'
import type { AddressInfo } from 'node:net'
import { after, before, describe, test } from 'node:test'
import type { LlmConfig } from '../src/config.ts'
import { buildServer } from '../src/server.ts'
import { BUYER, fakeApp, LIMITS, MemoryStore, ok, ScriptedLlm, text, TOOLS } from './fakes.ts'

/** 返回体 { code, msg, data }；测试里 data 按需要取字段 */
type Body = { code: number; msg: string; data: any }
const body = async (res: Response | Promise<Response>): Promise<Body> => (await (await res).json()) as Body

const LLM_CONFIG: LlmConfig = { baseUrl: 'https://llm.internal.example/v1', apiKey: 'sk-secret', model: 'm', mode: 'self-hosted', streamUsage: true, timeoutMs: 5000 }

/** app 认令牌：good 是采购小王，expired 过期，ai-off 是 AI 开关关了（app 回 HTTP 403） */
const { app } = fakeApp({
  '/api/auth/me': (_url, auth) =>
    auth === 'Bearer good' ? ok(BUYER)
      : auth === 'Bearer ai-off' ? { status: 403, body: { code: 403, msg: 'AI 功能没有打开', data: null } }
        : { status: 401, body: { code: 401, msg: '未登录或登录已过期', data: null } },
})

function start(llm: ScriptedLlm | null) {
  const store = new MemoryStore()
  const server = buildServer({ chat: { llm, app, store: store.asStore(), tools: TOOLS, limits: LIMITS, systemName: '服装 SCM' }, app, store: store.asStore(), llmConfig: llm ? LLM_CONFIG : null })
  return { server, store }
}

describe('助手服务的接口', () => {
  const llm = new ScriptedLlm([text('你好，有什么要查的？'), text('第二句')])
  const { server, store } = start(llm)
  let base = ''
  before(async () => {
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  })
  after(() => server.close())

  const chat = (token: string | null, body: unknown, contentType = 'application/json') =>
    fetch(`${base}/assistant/chat`, { method: 'POST', headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), 'Content-Type': contentType }, body: typeof body === 'string' ? body : JSON.stringify(body) })

  test('health、info 不用登录；info 只给模型的方式和主机名，不给 Key 和完整地址', async () => {
    assert.deepEqual(await body(fetch(`${base}/assistant/health`)), { code: 0, msg: 'ok', data: { status: 'UP', model: 'configured' } })
    const info = await (await fetch(`${base}/assistant/info`)).text()
    assert.deepEqual(JSON.parse(info).data, { model: { mode: 'self-hosted', host: 'llm.internal.example' } })
    assert.ok(!info.includes('sk-secret') && !info.includes('/v1'))
  })

  test('没带令牌 401；app 说令牌过期、AI 关了，原样回 401 / 403', async () => {
    const none = await chat(null, { message: '你好' })
    assert.equal(none.status, 401)
    const expired = await chat('expired', { message: '你好' })
    assert.equal(expired.status, 401)
    const off = await chat('ai-off', { message: '你好' })
    assert.equal(off.status, 403)
    assert.equal((await body(off)).msg, 'AI 功能没有打开')
    assert.equal(llm.seen.length, 0, '认不出人就不调模型')
  })

  test('问一句：SSE 依次推 meta、delta、done；不让 Nginx 攒着', async () => {
    const res = await chat('good', { message: '你好' })
    assert.equal(res.status, 200)
    assert.match(res.headers.get('content-type') ?? '', /text\/event-stream/)
    assert.equal(res.headers.get('x-accel-buffering'), 'no')
    const events = [...(await res.text()).matchAll(/event: (\w+)\ndata: (.*)\n\n/g)].map((m) => [m[1], JSON.parse(m[2]!)] as const)
    assert.deepEqual(events.map(([e]) => e), ['meta', 'delta', 'done'])
    assert.equal(events[0]![1].created, true)
    assert.equal(events[2]![1].text, '你好，有什么要查的？')

    const id = events[0]![1].conversationId as string
    const list = await body(fetch(`${base}/assistant/conversations`, { headers: { Authorization: 'Bearer good' } }))
    assert.deepEqual(list.data.map((c: { id: string }) => c.id), [id])
    const one = await body(fetch(`${base}/assistant/conversations/${id}`, { headers: { Authorization: 'Bearer good' } }))
    assert.deepEqual(one.data.map((m: { role: string }) => m.role), ['user', 'assistant'])
  })

  test('别人的对话：看不到、接着问也不行（404）', async () => {
    await store.createConversation('22222222-2222-2222-2222-222222222222', '99', '别人的')
    const look = await fetch(`${base}/assistant/conversations/22222222-2222-2222-2222-222222222222`, { headers: { Authorization: 'Bearer good' } })
    assert.equal(look.status, 404)
    const cont = await chat('good', { message: '接着问', conversationId: '22222222-2222-2222-2222-222222222222' })
    assert.equal(cont.status, 404)
  })

  test('请求体：不是 JSON、太长、缺 message 都是 400', async () => {
    assert.equal((await chat('good', 'message=hi', 'application/x-www-form-urlencoded')).status, 400)
    assert.equal((await chat('good', '{oops')).status, 400)
    assert.equal((await chat('good', { message: 'x'.repeat(17 * 1024) })).status, 400)
    assert.equal((await chat('good', { text: '你好' })).status, 400)
  })

  test('方法、地址不对：405、404', async () => {
    assert.equal((await fetch(`${base}/assistant/chat`, { headers: { Authorization: 'Bearer good' } })).status, 405)
    assert.equal((await fetch(`${base}/assistant/nothing`)).status, 404)
    assert.equal((await fetch(`${base}/api/orders`)).status, 404, '助手服务不转发 app 的接口')
  })
})

describe('没配模型', () => {
  test('服务照样起，问话回 503 说清楚', async () => {
    const { server } = start(null)
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
    try {
      const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
      assert.equal((await body(fetch(`${base}/assistant/health`))).data.model, 'none')
      const res = await fetch(`${base}/assistant/chat`, { method: 'POST', headers: { Authorization: 'Bearer good', 'Content-Type': 'application/json' }, body: '{"message":"你好"}' })
      assert.equal(res.status, 503)
      assert.match((await body(res)).msg, /还没有配置模型/)
    } finally {
      server.close()
    }
  })
})
