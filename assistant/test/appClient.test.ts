import assert from 'node:assert/strict'
import { describe, test } from 'node:test'
import { AppClient, tokenScope } from '../src/appClient.ts'
import { jwt } from './fakes.ts'

/** 记下发出去的请求，按给的函数回 */
function client(reply: () => Response) {
  const sent: { url: string; init: RequestInit }[] = []
  const impl = (async (url: string, init: RequestInit) => {
    sent.push({ url, init })
    return reply()
  }) as unknown as typeof fetch
  return { app: new AppClient('http://app.test', impl), sent }
}

const okJson = () => new Response(JSON.stringify({ code: 0, msg: 'ok', data: { id: '1' } }), { status: 200 })

describe('替用户调 app', () => {
  test('只发 GET、带助手令牌和请求编号、不跟随重定向', async () => {
    const { app, sent } = client(okJson)
    const r = await app.get('tok', '/api/orders', new URLSearchParams({ keyword: 'A' }), 'req-1')
    assert.deepEqual(r, { status: 200, code: 0, msg: 'ok', data: { id: '1' } })
    assert.equal(sent[0]!.url, 'http://app.test/api/orders?keyword=A')
    assert.equal(sent[0]!.init.method, 'GET')
    assert.equal(sent[0]!.init.redirect, 'error')
    const h = sent[0]!.init.headers as Record<string, string>
    assert.equal(h.Authorization, 'Bearer tok')
    assert.equal(h['X-Request-Id'], 'req-1')
  })

  test('只能是 /api/ 下的地址：别的、带 .. 和 // 的不发', async () => {
    const { app, sent } = client(okJson)
    for (const bad of ['/files/a.png', '/api/../admin', '/api//orders', 'http://evil/api/x', '/api/orders?x=1', '/api/orders%2F..']) {
      await assert.rejects(app.get('tok', bad, new URLSearchParams(), 'r'), /不是能查的地址/, bad)
    }
    assert.equal(sent.length, 0)
  })

  test('返回超过 2MB：不管有没有 Content-Length 都不整个读进来', async () => {
    const declared = client(() => new Response('x', { status: 200, headers: { 'content-length': String(3 * 1024 * 1024) } }))
    assert.equal((await declared.app.get('t', '/api/orders', new URLSearchParams(), 'r')).msg, '返回的内容太大')

    let pulled = 0
    const chunked = client(() => new Response(new ReadableStream<Uint8Array>({
      pull(c) {
        pulled++
        c.enqueue(new Uint8Array(256 * 1024).fill(0x20))
        if (pulled > 100) c.close()
      },
    }), { status: 200 }))
    assert.equal((await chunked.app.get('t', '/api/orders', new URLSearchParams(), 'r')).msg, '返回的内容太大')
    assert.ok(pulled <= 10, `读了 ${pulled} 块（每块 256KB）`)
  })

  test('不是 JSON（网关的错误页）：说清楚，不抛', async () => {
    const { app } = client(() => new Response('<html>502</html>', { status: 502 }))
    assert.deepEqual(await app.get('t', '/api/orders', new URLSearchParams(), 'r'), { status: 502, code: null, msg: 'app 返回的不是 JSON（HTTP 502）', data: null })
  })
})

describe('令牌的用途', () => {
  test('助手令牌是 ASSISTANT；普通登录令牌、乱写的是 null', () => {
    assert.equal(tokenScope(jwt({ scope: 'ASSISTANT' })), 'ASSISTANT')
    assert.equal(tokenScope(jwt({})), null)
    assert.equal(tokenScope(jwt({ scope: 1 })), null)
    assert.equal(tokenScope('abc'), null)
    assert.equal(tokenScope('a.%%%.c'), null)
  })
})
