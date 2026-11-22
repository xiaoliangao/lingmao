import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, test } from 'node:test'
import { buildTools, parseWhitelist, runTool, shrink, toolsFor, type OpenApiDoc } from '../src/tools.ts'
import { fakeApp, ok, OPENAPI, TOOLS, WHITELIST } from './fakes.ts'

describe('白名单校验（启动时，不合规起不来）', () => {
  test('从 openapi 取说明和参数：header 参数、勾选导出用的 ids 不给模型；路径参数必填', () => {
    const list = TOOLS.find((t) => t.name === 'list_purchase_orders')!
    assert.equal(list.spec.function.description, '列表：下单日期倒序。按单号找采购单')
    assert.deepEqual(Object.keys(list.spec.function.parameters.properties as object), ['keyword', 'status', 'page', 'size'])
    assert.deepEqual((list.spec.function.parameters.properties as Record<string, unknown>).status, {
      type: 'array', items: { type: 'string', enum: ['DRAFT', 'APPROVED'] }, description: '状态',
    })
    const detail = TOOLS.find((t) => t.name === 'get_purchase_order')!
    assert.deepEqual(detail.spec.function.parameters.required, ['id'])
    assert.equal(JSON.stringify(detail.spec).includes('int64'), false, 'format 之类去掉，有的模型服务不认')
  })

  const bad: [string, Partial<(typeof WHITELIST)[number]>, RegExp][] = [
    ['不是 GET', { op: 'POST /api/purchase-orders' }, /只收 GET/],
    ['导出', { op: 'GET /api/purchase-orders/export' }, /导出/],
    ['返回的不是 R', { op: 'GET /api/raw-bytes' }, /不是数据/],
    ['openapi 里没有', { op: 'GET /api/nothing' }, /没有 GET/],
    ['不在 /api/ 下', { op: 'GET /files/a.png' }, /\/api\//],
    ['工具名不合规', { tool: 'List-Orders' }, /工具名/],
    ['没写权限', { perm: ' ' }, /perm/],
    ['label 太长', { label: '这是一个特别特别特别特别长的给人看的名字啊' }, /label/],
  ]
  for (const [name, patch, message] of bad) {
    test(`拒绝：${name}`, () => {
      assert.throws(() => buildTools(OPENAPI, [{ ...WHITELIST[0]!, ...patch }]), message)
    })
  }

  test('拒绝：工具名重复', () => {
    assert.throws(() => buildTools(OPENAPI, [WHITELIST[0]!, { ...WHITELIST[1]!, tool: WHITELIST[0]!.tool }]), /重复/)
  })

  test('tools.yaml 缺字段时说是第几条', () => {
    assert.throws(() => parseWhitelist('- op: GET /api/orders\n  tool: list_orders\n  perm: login\n'), /第 1 条/)
  })

  // 仓库里的 tools.yaml 对上真的 openapi.json（打包时导出的；本机没有时跳过：ASSISTANT_OPENAPI=…/openapi.json pnpm test）
  const openapi = process.env.ASSISTANT_OPENAPI
  test('仓库里的 tools.yaml 全部合规', { skip: openapi ? false : '没有 ASSISTANT_OPENAPI' }, () => {
    const doc = JSON.parse(readFileSync(openapi!, 'utf8')) as OpenApiDoc
    const defs = buildTools(doc, parseWhitelist(readFileSync(new URL('../tools.yaml', import.meta.url), 'utf8')))
    assert.ok(defs.length >= 30)
    for (const d of defs) assert.ok(!/attachments|certs|bank|files/.test(d.path), `${d.path} 不该进白名单`)
  })
})

describe('按权限给工具', () => {
  test('只给有权限码的，加上只要登录的', () => {
    const withLogin = buildTools(OPENAPI, [...WHITELIST, { op: 'GET /api/orders', tool: 'orders_any', label: '订单', perm: 'login' }])
    assert.deepEqual(toolsFor(new Set(['purchase:order:list']), withLogin).map((t) => t.name), ['list_purchase_orders', 'get_purchase_order', 'orders_any'])
    assert.deepEqual(toolsFor(new Set(), withLogin).map((t) => t.name), ['orders_any'])
  })
})

describe('调工具', () => {
  const ctx = (app: ReturnType<typeof fakeApp>['app']) => ({ app, token: 'tok', requestId: 'r1', maxPageSize: 50, maxResultChars: 4000 })

  test('拼地址：路径参数、数组参数；size 不超过上限；带的是助手令牌', async () => {
    const { app, requests } = fakeApp({ '/api/purchase-orders': ok({ list: [], total: 0 }) })
    const list = TOOLS.find((t) => t.name === 'list_purchase_orders')!
    const r = await runTool(list, JSON.stringify({ keyword: 'PO2026', status: ['DRAFT', 'APPROVED'], size: 500 }), ctx(app))
    assert.equal(r.ok, true)
    const url = requests[0]!.url
    assert.equal(url.searchParams.get('keyword'), 'PO2026')
    assert.deepEqual(url.searchParams.getAll('status'), ['DRAFT', 'APPROVED'])
    assert.equal(url.searchParams.get('size'), '50')
    assert.equal(requests[0]!.auth, 'Bearer tok')
    assert.equal(requests[0]!.method, 'GET')
  })

  test('模型给的参数不对：不调 app，把原因交回模型', async () => {
    const { app, requests } = fakeApp({})
    const detail = TOOLS.find((t) => t.name === 'get_purchase_order')!
    for (const args of ['{}', '{"id":"../../auth/me"}', '{"id":"1/../2"}', 'not json', '[1]']) {
      const r = await runTool(detail, args, ctx(app))
      assert.equal(r.ok, false, args)
      assert.equal(r.status, null)
    }
    assert.equal(requests.length, 0)
  })

  test('app 回错误（没权限、不在数据范围）：原样告诉模型', async () => {
    const { app } = fakeApp({ '/api/purchase-orders/9': { status: 200, body: { code: 404, msg: '采购单不存在', data: null } } })
    const r = await runTool(TOOLS.find((t) => t.name === 'get_purchase_order')!, '{"id":9}', ctx(app))
    assert.equal(r.ok, false)
    assert.deepEqual(JSON.parse(r.content), { error: '采购单不存在', code: 404 })
  })

  test('结果太长：截断并说明', async () => {
    const { app } = fakeApp({ '/api/purchase-orders/1': ok({ remark: 'x'.repeat(10_000) }) })
    const r = await runTool(TOOLS.find((t) => t.name === 'get_purchase_order')!, '{"id":1}', { ...ctx(app), maxResultChars: 1000 })
    assert.ok(r.content.length <= 1000)
    assert.equal(JSON.parse(r.content).truncated, true)
  })
})

describe('瘦身', () => {
  test('去掉图片、文件地址、按钮列表、空值；列表最多 20 行并写明总数', () => {
    const rows = Array.from({ length: 25 }, (_, i) => ({ id: String(i), code: `PO${i}`, image: '/files/a.png?e=1&s=x', actions: ['audit'], remark: null }))
    const { data, rows: n } = shrink({ list: rows, total: 130 })
    const page = data as { total: number; list: Record<string, unknown>[]; note: string }
    assert.equal(n, 25)
    assert.equal(page.total, 130)
    assert.equal(page.list.length, 20)
    assert.deepEqual(page.list[0], { id: '0', code: 'PO0' })
    assert.match(page.note, /前 20 行/)
  })

  test('嵌套里的文件地址、颜色值也去掉；数字和文字原样', () => {
    const { data } = shrink({ code: 'M1', qty: '12.50', colorHex: '#fff', skus: [{ sku: 'M1-01', url: 'https://x/y', pic: '/files/2026/a.jpg' }] })
    assert.deepEqual(data, { code: 'M1', qty: '12.50', skus: [{ sku: 'M1-01' }] })
  })
})
