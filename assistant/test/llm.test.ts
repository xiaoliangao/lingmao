import assert from 'node:assert/strict'
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

  test('流里报错、不是 JSON：LlmError', async () => {
    await assert.rejects(readStream(streamOf(sse({ error: { message: 'quota exceeded' } })), () => undefined), (e) => e instanceof LlmError && /quota exceeded/.test(e.message))
    await assert.rejects(readStream(streamOf('data: {oops\n\n'), () => undefined), LlmError)
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

  test('接口回 4xx / 5xx：LlmError，只带对方的错误信息，不带 Key', async () => {
    const impl = (async () => new Response(JSON.stringify({ error: { message: 'model not found' } }), { status: 404 })) as unknown as typeof fetch
    await assert.rejects(
      new OpenAiCompatible(config, impl).chat([], [], () => undefined, new AbortController().signal),
      (e) => e instanceof LlmError && e.status === 404 && /model not found/.test(e.message) && !e.message.includes('sk-test'),
    )
  })

  test('连不上：LlmError（用户停止的不算，原样抛出）', async () => {
    const down = (async () => { throw new TypeError('fetch failed') }) as unknown as typeof fetch
    await assert.rejects(new OpenAiCompatible(config, down).chat([], [], () => undefined, new AbortController().signal), /连不上模型服务/)
    const stopped = new AbortController()
    stopped.abort()
    const abortAware = (async (_u: string, init: RequestInit) => { init.signal?.throwIfAborted(); throw new Error('不该到这') }) as unknown as typeof fetch
    await assert.rejects(new OpenAiCompatible(config, abortAware).chat([], [], () => undefined, stopped.signal), (e) => !(e instanceof LlmError))
  })
})
