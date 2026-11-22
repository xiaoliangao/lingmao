import assert from 'node:assert/strict'
import { describe, test } from 'node:test'
import { suggestionsFor, todoGroups } from '../src/suggest.ts'
import { buildTools, parseWhitelist, type OpenApiDoc } from '../src/tools.ts'

const R = { '200': { content: { '*/*': { schema: { $ref: '#/components/schemas/RObject' } } } } }
const DOC: OpenApiDoc = {
  paths: Object.fromEntries(
    ['/api/board/home/todos', '/api/orders', '/api/orders/{id}', '/api/order-progress', '/api/purchase-orders', '/api/order-kitting', '/api/stocks'].map((p) => [p, { get: { summary: p, responses: R } }]),
  ),
}
const TOOLS = buildTools(DOC, parseWhitelist(`
- { op: GET /api/board/home/todos, tool: my_todos, label: 我的待办, perm: login, ask: 今天有哪些待我处理的事 }
- { op: GET /api/orders, tool: list_orders, label: 需求订单, perm: order:order:list, ask: 待审核的需求订单有哪些 }
- { op: "GET /api/orders/{id}", tool: get_order, label: 订单详情, perm: order:order:list }
- { op: GET /api/order-progress, tool: order_progress, label: 订单进度, perm: order:progress:list, ask: 哪些订单已经逾期了 }
- { op: GET /api/purchase-orders, tool: list_purchase_orders, label: 采购单, perm: purchase:order:list, ask: 哪些物料到料逾期了 }
- { op: GET /api/order-kitting, tool: order_kitting, label: 订单齐料, perm: order:order:list, ask: 已审核的订单料齐了没有 }
- { op: GET /api/stocks, tool: list_stocks, label: 物料库存, perm: inventory:stock:list, ask: 面料库存还剩多少 }
`))

describe('推荐问题', () => {
  test('只推有权限的；各模块轮流，头几句不全是一个模块；他权限多的模块在前；没写例句的（详情）不推', () => {
    const all = new Set(['order:order:list', 'order:progress:list', 'purchase:order:list', 'inventory:stock:list'])
    assert.deepEqual(suggestionsFor(all, TOOLS, []).map((s) => s.text), [
      '今天有哪些待我处理的事', // 只要登录的首页那几句总在最前
      '待审核的需求订单有哪些', // order 有 2 个权限码，排第一个模块
      '哪些物料到料逾期了', // purchase、inventory 各 1 个，照 tools.yaml 的顺序
      '面料库存还剩多少',
      '哪些订单已经逾期了', // 第二轮：order 第二句
      '已审核的订单料齐了没有',
    ])
    // 仓管：库存的权限多，库存排在采购前面
    const keeper = new Set(['inventory:stock:list', 'inventory:doc:list', 'inventory:doc:add', 'purchase:order:list'])
    assert.deepEqual(suggestionsFor(keeper, TOOLS, []).map((s) => s.text), ['今天有哪些待我处理的事', '面料库存还剩多少', '哪些物料到料逾期了'])
  })

  test('手上的待办在前、带件数；和例句重复的只出一次（带件数的那句）；不认识的分组不推；最多 max 句', () => {
    const got = suggestionsFor(new Set(['purchase:order:list']), TOOLS, [{ key: 'ARRIVAL_LATE', total: 3 }, { key: 'AUDIT', total: 1 }, { key: 'WHAT', total: 9 }])
    assert.deepEqual(got.slice(0, 3), [
      { text: '哪些物料到料逾期了', label: '我的待办', count: 3 },
      { text: '有哪些单据等我审核', label: '我的待办', count: 1 },
      { text: '今天有哪些待我处理的事', label: '我的待办', count: null },
    ])
    assert.equal(got.filter((s) => s.text === '哪些物料到料逾期了').length, 1)
    assert.equal(suggestionsFor(new Set(['purchase:order:list']), TOOLS, [], 2).length, 2)
  })

  test('待办的返回形状不对、件数是 0 的：不要', () => {
    assert.deepEqual(todoGroups(null), [])
    assert.deepEqual(todoGroups({ groups: 'x' }), [])
    assert.deepEqual(todoGroups({ groups: [{ key: 'AUDIT', total: 0 }, { key: 'ISSUE', total: 2 }, { key: 1, total: 1 }, null] }), [{ key: 'ISSUE', total: 2 }])
  })

  test('tools.yaml 的 ask：一行、40 个字以内', () => {
    const one = (ask: string) => buildTools(DOC, [{ op: 'GET /api/orders', tool: 'list_orders', label: '需求订单', perm: 'order:order:list', ask }])
    assert.equal(one('  待审核的需求订单有哪些 ')[0]!.ask, '待审核的需求订单有哪些')
    assert.throws(() => one('a'.repeat(41)), /ask/)
    assert.throws(() => one('第一行\n第二行'), /ask/)
    assert.throws(() => one('  '), /ask/)
  })
})
