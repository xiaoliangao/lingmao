import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { describe, test } from 'node:test'
import type { LlmConfig } from '../src/config.ts'
import { LlmError, OpenAiCompatible, readStream } from '../src/llm.ts'

/** 把几段文字当成网络上一块块到的流（故意从中间切开，验证跨块拼行） */
function streamOf(...pieces: string[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const p of pieces) controller.enqueue(enc.encode(p))
      controller.close()
    },
  })
}

const sse = (obj: unknown) => `data: ${JSON.stringify(obj)}\n\n`

describe('读流', () => {
  test('文字一段段推出去；最后一块的用量记下', async () => {
    const deltas: string[] = []
    const body = sse({ choices: [{ delta: { content: '采购单' } }] }) + sse({ choices: [{ delta: { content: ' PO1 已审核' } }] })
      + sse({ choices: [], usage: { prompt_tokens: 321, completion_tokens: 12 } }) + 'data: [DONE]\n\n'
    const turn = await readStream(streamOf(body.slice(0, 17), body.slice(17, 60), body.slice(60)), (d) => deltas.push(d))
    assert.deepEqual(deltas, ['采购单', ' PO1 已审核'])
    assert.equal(turn.content, '采购单 PO1 已审核')
    assert.deepEqual(turn.usage, { promptTokens: 321, completionTokens: 12 })
    assert.deepEqual(turn.toolCalls, [])
  })

  test('工具调用分几块到：按 index 拼名字和参数，两个调用分开', async () => {
    const body = [
      sse({ choices: [{ delta: { tool_calls: [{ index: 0, id: 'a', function: { name: 'get_', arguments: '' } }] } }] }),
      sse({ choices: [{ delta: { tool_calls: [{ index: 0, function: { name: 'order', arguments: '{"id"' } }] } }] }),
      sse({ choices: [{ delta: { tool_calls: [{ index: 1, id: 'b', function: { name: 'list_stocks', arguments: '{}' } }] } }] }),
      sse({ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: ':12}' } }] } }] }),
      'data: [DONE]\n\n',
    ].join('')
    const turn = await readStream(streamOf(body), () => undefined)
    assert.deepEqual(turn.toolCalls, [
      { id: 'a', type: 'function', function: { name: 'get_order', arguments: '{"id":12}' } },
      { id: 'b', type: 'function', function: { name: 'list_stocks', arguments: '{}' } },
    ])
    assert.equal(turn.usage, null, '接口没给用量时是 null')
  })

  test('\\r\\n 换行、没有 [DONE] 就断开，照样读完', async () => {
    const turn = await readStream(streamOf('data: {"choices":[{"delta":{"content":"好"}}]}\r\n\r\ndata: {"choices":[{"delta":{"content":"的"}}]}'), () => undefined)
    assert.equal(turn.content, '好的')
  })

  test('流里报错、不是 JSON：LlmError；对方的原话只在 detail（进日志），给用户的话不转述', async () => {
    await assert.rejects(readStream(streamOf(sse({ error: { message: 'quota exceeded for sk-abcdef123456' } })), () => undefined),
      (e) => e instanceof LlmError && !/quota/.test(e.message) && /quota exceeded for sk-\*\*\*/.test(e.detail))
    await assert.rejects(readStream(streamOf('data: {oops\n\n'), () => undefined), LlmError)
  })

  test('HTTP 200 却回了一个 JSON 错误（没有 data: 行）：报错，不当成空回答', async () => {
    await assert.rejects(readStream(streamOf('{"error":{"message":"insufficient_quota"}}'), () => undefined),
      (e) => e instanceof LlmError && /没有按流式返回/.test(e.message) && /insufficient_quota/.test(e.detail))
  })

  test('汉字的字节被切在两块之间：照样拼对', async () => {
    const bytes = new TextEncoder().encode(sse({ choices: [{ delta: { content: '采购单已审核' } }] }) + 'data: [DONE]\n\n')
    const cut = bytes.indexOf(0xe9) + 1 // 某个汉字的第一个字节之后
    const stream = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(bytes.slice(0, cut)); c.enqueue(bytes.slice(cut)); c.close() } })
    assert.equal((await readStream(stream, () => undefined)).content, '采购单已审核')
  })

  test('工具调用不带 index：按 id 分开，不串在一起；也没有 id 的补一个，不重复', async () => {
    const body = [
      sse({ choices: [{ delta: { tool_calls: [{ id: 'c1', function: { name: 'list_orders', arguments: '{}' } }, { id: 'c2', function: { name: 'list_stocks', arguments: '{}' } }] } }] }),
      'data: [DONE]\n\n',
    ].join('')
    const turn = await readStream(streamOf(body), () => undefined)
    assert.deepEqual(turn.toolCalls.map((c) => [c.id, c.function.name]), [['c1', 'list_orders'], ['c2', 'list_stocks']])

    const noId = sse({ choices: [{ delta: { tool_calls: [{ index: 0, function: { name: 'list_orders', arguments: '{' } }] } }] })
      + sse({ choices: [{ delta: { tool_calls: [{ function: { arguments: '}' } }] } }] }) + 'data: [DONE]\n\n'
    const a = await readStream(streamOf(noId), () => undefined)
    const b = await readStream(streamOf(noId), () => undefined)
    assert.equal(a.toolCalls[0]!.function.arguments, '{}', '没 index 没 id 的片段接在上一个后面')
    assert.match(a.toolCalls[0]!.id, /^call_/)
    assert.notEqual(a.toolCalls[0]!.id, b.toolCalls[0]!.id)

    // 同一块里两个调用，index、id 都没有（审查 R-05）：上一个已经在拼参数、这一片又带名字，是下一个调用；名字分两片来的照样拼上
    const bare = sse({ choices: [{ delta: { tool_calls: [{ function: { name: 'list_', arguments: '' } }, { function: { name: 'orders', arguments: '{"a"' } }] } }] })
      + sse({ choices: [{ delta: { tool_calls: [{ function: { arguments: ':1}' } }, { function: { name: 'list_stocks', arguments: '{}' } }] } }] }) + 'data: [DONE]\n\n'
    const c = await readStream(streamOf(bare), () => undefined)
    assert.deepEqual(c.toolCalls.map((x) => [x.function.name, x.function.arguments]), [['list_orders', '{"a":1}'], ['list_stocks', '{}']])
  })
})

describe('调模型', () => {
  const config: LlmConfig = { baseUrl: 'https://llm.test/v1', apiKey: 'sk-test', model: 'm1', mode: 'external', streamUsage: true, timeoutMs: 5000 }

  test('发出去的只有模型名、消息、工具、流式这几项；Key 在请求头', async () => {
    let sent: { url: string; init: RequestInit } | null = null
    const impl = (async (url: string, init: RequestInit) => {
      sent = { url, init }
      return new Response(sse({ choices: [{ delta: { content: 'ok' } }] }) + 'data: [DONE]\n\n', { status: 200 })
    }) as unknown as typeof fetch
    const turn = await new OpenAiCompatible(config, impl).chat([{ role: 'user', content: '你好' }], [], () => undefined, new AbortController().signal)
    assert.equal(turn.content, 'ok')
    assert.equal(sent!.url, 'https://llm.test/v1/chat/completions')
    assert.deepEqual(JSON.parse(String(sent!.init.body)), { model: 'm1', messages: [{ role: 'user', content: '你好' }], stream: true, stream_options: { include_usage: true } })
    assert.equal((sent!.init.headers as Record<string, string>).Authorization, 'Bearer sk-test')
  })

  test('最后一轮：tool_choice 是 none；没有工具时不带 tools 和 tool_choice', async () => {
    const bodies: Record<string, unknown>[] = []
    const impl = (async (_url: string, init: RequestInit) => {
      bodies.push(JSON.parse(String(init.body)))
      return new Response('data: [DONE]\n\n', { status: 200 })
    }) as unknown as typeof fetch
    const llm = new OpenAiCompatible(config, impl)
    const tool = { type: 'function' as const, function: { name: 'x', description: 'x', parameters: {} } }
    await llm.chat([], [tool], () => undefined, new AbortController().signal, { toolChoice: 'none' })
    await llm.chat([], [], () => undefined, new AbortController().signal, { toolChoice: 'none' })
    assert.equal(bodies[0]!.tool_choice, 'none')
    assert.equal('tool_choice' in bodies[1]!, false)
  })

  test('接口回 4xx / 5xx：给用户按类别说一句，不转述对方原话（可能带 Key 片段）；原话打码进 detail', async () => {
    const reply = (status: number, message: string) => (async () => new Response(JSON.stringify({ error: { message } }), { status })) as unknown as typeof fetch
    const ask = (impl: typeof fetch) => new OpenAiCompatible(config, impl).chat([], [], () => undefined, new AbortController().signal)
    await assert.rejects(ask(reply(401, 'Incorrect API key provided: sk-abcd1234efgh')),
      (e) => e instanceof LlmError && e.status === 401 && /Key/.test(e.message) && !/sk-/.test(e.message) && /sk-\*\*\*/.test(e.detail) && !e.detail.includes('abcd1234'))
    await assert.rejects(ask(reply(404, 'model not found')), (e) => e instanceof LlmError && /找不到配置的模型/.test(e.message) && /model not found/.test(e.detail))
    await assert.rejects(ask(reply(429, 'rate limited')), /太忙/)
    await assert.rejects(ask(reply(502, 'upstream 10.0.0.5 down')), (e) => e instanceof LlmError && !e.message.includes('10.0.0.5'))
  })

  test('出错时对方回一个特别大的页面：只读前 4KB', async () => {
    let pulled = 0
    const huge = new ReadableStream<Uint8Array>({
      pull(c) {
        pulled++
        c.enqueue(new Uint8Array(64 * 1024).fill(0x61))
        if (pulled > 1000) c.close()
      },
    })
    const impl = (async () => new Response(huge, { status: 500 })) as unknown as typeof fetch
    await assert.rejects(new OpenAiCompatible(config, impl).chat([], [], () => undefined, new AbortController().signal), LlmError)
    assert.ok(pulled < 5, `读了 ${pulled} 块`)
  })

  test('流读到一半卡住超时、连接断开：都是 LlmError（真的 HTTP 服务）', async () => {
    const server = createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      res.write(sse({ choices: [{ delta: { content: '先说一半' } }] }))
      if (req.url?.includes('drop')) setTimeout(() => res.socket?.destroy(), 30)
      // 否则一直不说完
    })
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    const port = (server.address() as AddressInfo).port
    try {
      const slow = new OpenAiCompatible({ ...config, baseUrl: `http://127.0.0.1:${port}/slow`, timeoutMs: 150 })
      const deltas: string[] = []
      await assert.rejects(slow.chat([], [], (d) => deltas.push(d), new AbortController().signal), (e) => e instanceof LlmError && /太久没回应/.test(e.message))
      assert.deepEqual(deltas, ['先说一半'])
      const dropped = new OpenAiCompatible({ ...config, baseUrl: `http://127.0.0.1:${port}/drop` })
      await assert.rejects(dropped.chat([], [], () => undefined, new AbortController().signal), (e) => e instanceof LlmError && /连接断了/.test(e.message))
    } finally {
      server.closeAllConnections()
      server.close()
    }
  })

  test('超时按"没有动静"算：一直在吐字的长回答不掐；一个字一个字慢慢滴，到总上限（3 倍）也掐（审查 R-04）', async () => {
    const server = createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' })
      let n = 0
      const timer = setInterval(() => {
        n++
        if (req.url?.includes('steady') && n > 6) {
          clearInterval(timer)
          res.end('data: [DONE]\n\n')
          return
        }
        res.write(sse({ choices: [{ delta: { content: String(n) } }] }))
      }, 60)
      res.on('close', () => clearInterval(timer))
    })
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    const port = (server.address() as AddressInfo).port
    try {
      // 每 60ms 一段、一共 420ms：超过 150ms，但从没停过 150ms
      const steady = new OpenAiCompatible({ ...config, baseUrl: `http://127.0.0.1:${port}/steady`, timeoutMs: 150 })
      assert.equal((await steady.chat([], [], () => undefined, new AbortController().signal)).content, '123456')
      const drip = new OpenAiCompatible({ ...config, baseUrl: `http://127.0.0.1:${port}/drip`, timeoutMs: 150 })
      const started = Date.now()
      await assert.rejects(drip.chat([], [], () => undefined, new AbortController().signal), (e) => e instanceof LlmError && /太久没回应/.test(e.message))
      const took = Date.now() - started
      assert.ok(took >= 400 && took < 1500, `用了 ${took}ms`)
    } finally {
      server.closeAllConnections()
      server.close()
    }
  })

  test('连不上：LlmError（用户停止的不算，原样抛出）', async () => {
    const down = (async () => { throw new TypeError('fetch failed') }) as unknown as typeof fetch
    await assert.rejects(new OpenAiCompatible(config, down).chat([], [], () => undefined, new AbortController().signal), /连接断了/)
    const stopped = new AbortController()
    stopped.abort()
    const abortAware = (async (_u: string, init: RequestInit) => { init.signal?.throwIfAborted(); throw new Error('不该到这') }) as unknown as typeof fetch
    await assert.rejects(new OpenAiCompatible(config, abortAware).chat([], [], () => undefined, stopped.signal), (e) => !(e instanceof LlmError))
  })
})
