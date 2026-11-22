import { AppClient, type Me } from '../src/appClient.ts'
import type { Limits } from '../src/config.ts'
import type { ChatMessage, ChatOptions, LlmProvider, LlmTurn, ToolSpec } from '../src/llm.ts'
import { pairUp, type ConversationRow, type Store, type StoredMessage, type ToolCallRecord } from '../src/store.ts'
import { buildTools, type OpenApiDoc, type WhitelistEntry } from '../src/tools.ts'

/** 测试用的假件：照着脚本回话的模型、记在内存里的库、按地址回固定 JSON 的 app */

export const LIMITS: Limits = {
  userDailyRequests: 3,
  dailyTokens: 10_000,
  maxSteps: 4,
  maxToolCallsPerStep: 2,
  maxResultChars: 4_000,
  maxPageSize: 50,
  historyTurns: 6,
  maxConcurrent: 2,
}

/** 长得像 app 发的 JWT：载荷里有 scope（只读载荷，不验签，签名随便写） */
export function jwt(claims: Record<string, unknown>): string {
  return `eyJhbGciOiJIUzI1NiJ9.${Buffer.from(JSON.stringify({ sub: '7', ...claims })).toString('base64url')}.sig`
}

export const BUYER: Me = {
  id: '7',
  username: 'buyer1',
  nickname: '采购小王',
  perms: ['purchase:order:list', 'assistant:chat:use'],
  features: { ai: true },
}

/** 照脚本一轮轮回话；每轮记下收到的消息和工具 */
export class ScriptedLlm implements LlmProvider {
  readonly seen: { messages: ChatMessage[]; tools: ToolSpec[]; options: ChatOptions }[] = []
  private readonly turns: (LlmTurn | Error)[]

  constructor(turns: (LlmTurn | Error)[]) {
    this.turns = turns
  }

  async chat(messages: ChatMessage[], tools: ToolSpec[], onDelta: (text: string) => void, _signal?: AbortSignal, options: ChatOptions = {}): Promise<LlmTurn> {
    this.seen.push({ messages: structuredClone(messages), tools, options })
    const next = this.turns.shift()
    if (!next) throw new Error('脚本用完了')
    if (next instanceof Error) throw next
    if (next.content) onDelta(next.content)
    return next
  }
}

export function text(content: string, usage = { promptTokens: 100, completionTokens: 20 }): LlmTurn {
  return { content, toolCalls: [], usage }
}

export function call(name: string, args: Record<string, unknown>, id = `c_${name}`): LlmTurn {
  return { content: '', toolCalls: [{ id, type: 'function', function: { name, arguments: JSON.stringify(args) } }], usage: { promptTokens: 80, completionTokens: 10 } }
}

/** 内存里的库：只实现 chat、server 用到的方法 */
export class MemoryStore {
  conversations = new Map<string, { userId: string; title: string }>()
  messagesById: { id: string; conversationId: string; role: 'user' | 'assistant'; content: string; error: string | null }[] = []
  toolCalls: { messageId: string; call: ToolCallRecord }[] = []
  requests = new Map<string, number>()
  tokens = 0

  async takeRequest(userId: string, limit: number): Promise<boolean> {
    const n = this.requests.get(userId) ?? 0
    if (n >= limit) return false
    this.requests.set(userId, n + 1)
    return true
  }
  async addTokens(_userId: string, p: number, c: number): Promise<void> {
    this.tokens += p + c
  }
  async tokensToday(): Promise<number> {
    return this.tokens
  }
  async createConversation(id: string, userId: string, title: string): Promise<void> {
    this.conversations.set(id, { userId, title: title.slice(0, 100) })
  }
  async touchConversation(id: string, userId: string): Promise<boolean> {
    return this.conversations.get(id)?.userId === userId
  }
  async ownsConversation(id: string, userId: string): Promise<boolean> {
    return this.conversations.get(id)?.userId === userId
  }
  async deleteConversation(id: string, userId: string): Promise<boolean> {
    if (this.conversations.get(id)?.userId !== userId) return false
    this.conversations.delete(id)
    this.messagesById = this.messagesById.filter((m) => m.conversationId !== id)
    return true
  }
  async listConversations(userId: string): Promise<ConversationRow[]> {
    return [...this.conversations.entries()].filter(([, c]) => c.userId === userId).map(([id, c]) => ({ id, title: c.title, updatedAt: '' }))
  }
  dbDown = false
  async ping(): Promise<void> {
    if (this.dbDown) throw new Error('db down')
  }
  async history(conversationId: string, turns: number): Promise<StoredMessage[]> {
    return pairUp(this.messagesById.filter((m) => m.conversationId === conversationId), turns)
  }
  async messages(conversationId: string) {
    return this.messagesById.filter((m) => m.conversationId === conversationId).map((m) => ({ id: m.id, role: m.role, content: m.content, error: m.error, createdAt: '' }))
  }
  async addUserMessage(conversationId: string, content: string): Promise<void> {
    this.messagesById.push({ id: String(this.messagesById.length + 1), conversationId, role: 'user', content, error: null })
  }
  async addAnswer(conversationId: string, content: string, error: string | null, _p: number, _c: number, calls: ToolCallRecord[]): Promise<string> {
    const id = String(this.messagesById.length + 1)
    this.messagesById.push({ id, conversationId, role: 'assistant', content, error })
    for (const c of calls) this.toolCalls.push({ messageId: id, call: c })
    return id
  }
  asStore(): Store {
    return this as unknown as Store
  }
}

export interface FakeRoute {
  status?: number
  body: unknown
}

/** 假的 app：按 "路径?查询" 或路径找回复，记下每次请求 */
export function fakeApp(routes: Record<string, FakeRoute | ((url: URL, auth: string | null) => FakeRoute)>): { app: AppClient; requests: { url: URL; auth: string | null; method: string }[] } {
  const requests: { url: URL; auth: string | null; method: string }[] = []
  const impl = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    const headers = new Headers(init?.headers)
    requests.push({ url, auth: headers.get('authorization'), method: init?.method ?? 'GET' })
    const route = routes[url.pathname + url.search] ?? routes[url.pathname]
    if (!route) return new Response(JSON.stringify({ code: 404, msg: '没有这个地址', data: null }), { status: 200 })
    const r = typeof route === 'function' ? route(url, headers.get('authorization')) : route
    return new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: { 'content-type': 'application/json' } })
  }) as typeof fetch
  return { app: new AppClient('http://app.test', impl), requests }
}

export function ok(data: unknown): FakeRoute {
  return { body: { code: 0, msg: 'ok', data } }
}

/** 一份小的 openapi：采购单列表、详情，外加几个不合规的接口给校验用 */
export const OPENAPI: OpenApiDoc = {
  paths: {
    '/api/purchase-orders': {
      get: {
        summary: '列表：下单日期倒序',
        parameters: [
          { name: 'keyword', in: 'query', description: '采购单号、供应商', schema: { type: 'string' } },
          { name: 'status', in: 'query', description: '状态', schema: { type: 'array', items: { type: 'string', enum: ['DRAFT', 'APPROVED'] } } },
          { name: 'ids', in: 'query', schema: { type: 'array', items: { type: 'integer', format: 'int64' } } },
          { name: 'page', in: 'query', schema: { type: 'integer', format: 'int32' } },
          { name: 'size', in: 'query', schema: { type: 'integer', format: 'int32' } },
          { name: 'X-Company-Id', in: 'header', schema: { type: 'string' } },
        ],
        responses: { '200': { content: { '*/*': { schema: { $ref: '#/components/schemas/RPageResultPurchaseRowVO' } } } } },
      },
      post: { summary: '新建', responses: {} },
    },
    '/api/purchase-orders/{id}': {
      get: {
        summary: '详情',
        parameters: [{ name: 'id', in: 'path', required: true, description: '地址里这一条的 id', schema: { type: 'integer', format: 'int64' } }],
        responses: { '200': { content: { '*/*': { schema: { $ref: '#/components/schemas/RPurchaseDetailVO' } } } } },
      },
    },
    '/api/orders': {
      get: { summary: '订单列表', parameters: [], responses: { '200': { content: { '*/*': { schema: { $ref: '#/components/schemas/RPageResultOrderRowVO' } } } } } },
    },
    '/api/purchase-orders/export': {
      get: { summary: '导出', responses: { '200': { content: { '*/*': { schema: { type: 'string' } as never } } } } },
    },
    '/api/raw-bytes': {
      get: { summary: '返回文件', responses: { '200': { content: { '*/*': { schema: { $ref: '#/components/schemas/Resource' } } } } } },
    },
  },
}

export const WHITELIST: WhitelistEntry[] = [
  { op: 'GET /api/purchase-orders', tool: 'list_purchase_orders', label: '采购单', perm: 'purchase:order:list', hint: '按单号找采购单', ask: '待审核的采购单有几张' },
  { op: 'GET /api/purchase-orders/{id}', tool: 'get_purchase_order', label: '采购单详情', perm: 'purchase:order:list' },
  { op: 'GET /api/orders', tool: 'list_orders', label: '需求订单', perm: 'order:order:list', ask: '待审核的需求订单有哪些' },
]

export const TOOLS = buildTools(OPENAPI, WHITELIST)
