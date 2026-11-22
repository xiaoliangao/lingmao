import assert from 'node:assert/strict'
import test from 'node:test'
import { ReadableStream } from 'node:stream/web'
import { TextEncoder } from 'node:util'
import { readSse } from '../src/utils/sse.ts'
import { PAGE_LINK, parseAnswer, parseInline, stripHtml } from '../src/utils/assistantText.ts'

/** 按给的块一块块吐字节的流 */
function streamOf(chunks) {
  const encoder = new TextEncoder()
  const parts = chunks.map((c) => (typeof c === 'string' ? encoder.encode(c) : c))
  return new ReadableStream({
    pull(controller) {
      const next = parts.shift()
      if (next) controller.enqueue(next)
      else controller.close()
    },
  })
}

async function events(chunks) {
  const got = []
  await readSse(streamOf(chunks), (event, data) => got.push([event, data]))
  return got
}

test('SSE：按空行分事件，event 和多行 data；注释、id 不管；末尾没空行的丢掉', async () => {
  assert.deepEqual(
    await events([': ping\n', 'event: meta\ndata: {"a":1}\n\n', 'data: 第一行\ndata: 第二行\nid: 7\n\n', 'event: delta\ndata: 没说完']),
    [['meta', '{"a":1}'], ['message', '第一行\n第二行']],
  )
})

test('SSE：块从任何地方切开都接得上（字段中间、汉字的字节中间、\\r\\n 中间）', async () => {
  const whole = new TextEncoder().encode('event: delta\r\ndata: {"text":"查到了"}\r\n\r\nevent: done\rdata: {}\r\r')
  for (let cut = 1; cut < whole.length; cut++) {
    assert.deepEqual(
      await events([whole.slice(0, cut), whole.slice(cut)]),
      [['delta', '{"text":"查到了"}'], ['done', '{}']],
      `从第 ${cut} 个字节切开`,
    )
  }
})

test('SSE：回调抛错就停下、错往外抛', async () => {
  await assert.rejects(readSse(streamOf(['data: 1\n\ndata: 2\n\n']), () => { throw new Error('坏了') }), /坏了/)
})

test('链接地址：只收 /小写页面 和 /小写页面/数字', () => {
  for (const ok of ['/orders', '/purchases/12', '/order-changes/3', '/fg-stock']) assert.ok(PAGE_LINK.test(ok), ok)
  for (const bad of ['/orders?status=A', '/purchases/12/edit', '//evil.example', '/api/orders/export', 'https://x', '/Orders', '/orders/', 'javascript:alert(1)', '/orders#x']) {
    assert.ok(!PAGE_LINK.test(bad), bad)
  }
})

test('行内：加粗、代码、站内链接；不是站内地址的链接只留文字；全角括号是普通字', () => {
  assert.deepEqual(parseInline('**PO1** 已审核，[打开](/purchases/12)，见 `ML0001`'), [
    { kind: 'strong', text: 'PO1' },
    { kind: 'text', text: ' 已审核，' },
    { kind: 'link', text: '打开', href: '/purchases/12' },
    { kind: 'text', text: '，见 ' },
    { kind: 'code', text: 'ML0001' },
  ])
  assert.deepEqual(parseInline('[导出](/api/orders/export) 和 ［外链］(https://x)'), [{ kind: 'text', text: '导出 和 ［外链］(https://x)' }])
})

test('块：段落保留单个换行、标题、列表（缩进的子项摊平）、分隔线、代码块原样', () => {
  const blocks = parseAnswer('结论：两张没审。\n依据如下\n\n### 明细\n1. PO1\n   - 供应商 A\n2. PO2\n---\n```\n[x](/orders) **不解析**\n```\n- 新列表')
  assert.deepEqual(blocks.map((b) => b.kind), ['p', 'heading', 'list', 'hr', 'pre', 'list'])
  assert.equal(blocks[0].lines.length, 2)
  assert.deepEqual(blocks[2].items.map((i) => i.map((x) => x.text).join('')), ['PO1', '供应商 A', 'PO2'])
  assert.equal(blocks[2].ordered, true)
  assert.equal(blocks[4].text, '[x](/orders) **不解析**')
  assert.equal(blocks[5].ordered, false)
})

test('块：表格（表头、对齐行、单元格里的链接）；没有对齐行的竖线是普通段落', () => {
  const [table, p] = parseAnswer('| 单号 | 状态 |\n| :-- | --: |\n| [PO1](/purchases/1) | 待审核 |\n| PO2 | 已审核 |\n\n| 不是 | 表格 |')
  assert.equal(table.kind, 'table')
  assert.deepEqual(table.head.map((c) => c[0].text), ['单号', '状态'])
  assert.equal(table.rows.length, 2)
  assert.deepEqual(table.rows[0][0], [{ kind: 'link', text: 'PO1', href: '/purchases/1' }])
  assert.equal(p.kind, 'p')
})

test('打字时的原话去掉常见 HTML 标签，不是标签的尖括号留着', () => {
  assert.equal(stripHtml('参考 <b>粗</b>，第一行<br>第二行<script>x</script>'), '参考 粗，第一行 第二行')
  assert.equal(stripHtml('数量<10，a<b 且 c>d'), '数量<10，a<b 且 c>d')
})

test('助手服务处理过的文字（方括号换了全角、外链去掉）照样能显示，不出链接', () => {
  const blocks = parseAnswer('见 ［官网］(共3张)（外部链接已去掉）\n＜img src=x>')
  assert.deepEqual(blocks, [{ kind: 'p', lines: [[{ kind: 'text', text: '见 ［官网］(共3张)（外部链接已去掉）' }], [{ kind: 'text', text: '＜img src=x>' }]] }])
})
