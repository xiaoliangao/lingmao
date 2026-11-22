import { parse as parseYaml } from 'yaml'
import type { AppClient } from './appClient.ts'
import type { ToolSpec } from './llm.ts'

/**
 * 工具 = app 已有接口的薄包装（RFC 017 §3.3、RFC 009 §3.2）。
 * tools.yaml 是白名单：一个接口一条，没列的模型看不见。参数和说明从打包时导出的 openapi.json 取（springdoc 的中文说明），
 * hint 只补"什么时候用它"。启动时逐条核对，不合规的直接起不来：
 * 只收 GET、返回的是数据（R）、不是导出 / 下载 / 打印 / 模板 / 预览，工具名不重复，权限码要填。
 * 每次问话按 /api/auth/me 的权限码，只把用户有权限的工具交给模型
 */

export interface WhitelistEntry {
  /** "GET /api/stocks"，对上 openapi.json 的路径 */
  op: string
  /** 给模型的工具名，小写字母、数字、下划线 */
  tool: string
  /** 给人看的，查的时候对话里显示"正在查：…"，20 个字以内 */
  label: string
  /** 用户有这个权限码才交给模型（和接口上 @PreAuthorize 的一致；只要登录的接口写 login） */
  perm: string
  /** 什么时候用它，补在接口说明后面 */
  hint?: string
}

interface ParamInfo {
  name: string
  in: 'path' | 'query'
  required: boolean
  schema: Record<string, unknown>
}

export interface ToolDef {
  name: string
  label: string
  path: string
  perm: string
  spec: ToolSpec
  params: ParamInfo[]
}

/** 登录就能调的接口在白名单里写 perm: login */
export const LOGIN_ONLY = 'login'

const FILE_LIKE = /(export|download|print|template|preview|sheet|\/file)/i
/** 列表上勾选导出用的参数，模型用不上，白占提示词 */
const DROP_PARAMS = new Set(['ids'])
const TOOL_NAME = /^[a-z][a-z0-9_]{1,63}$/

interface OpenApiParam {
  name: string
  in: string
  required?: boolean
  description?: string
  schema?: Record<string, unknown>
}

interface OpenApiOp {
  summary?: string
  description?: string
  parameters?: OpenApiParam[]
  responses?: Record<string, { content?: Record<string, { schema?: { $ref?: string } }> }>
}

export interface OpenApiDoc {
  paths: Record<string, Record<string, OpenApiOp>>
}

export function parseWhitelist(text: string): WhitelistEntry[] {
  const raw = parseYaml(text) as unknown
  if (!Array.isArray(raw)) throw new Error('tools.yaml 应该是一个列表')
  return raw.map((item, i) => {
    const e = item as Partial<WhitelistEntry>
    if (typeof e.op !== 'string' || typeof e.tool !== 'string' || typeof e.perm !== 'string' || typeof e.label !== 'string') {
      throw new Error(`tools.yaml 第 ${i + 1} 条缺 op、tool、label 或 perm`)
    }
    return { op: e.op, tool: e.tool, label: e.label, perm: e.perm, hint: typeof e.hint === 'string' ? e.hint : undefined }
  })
}

/** 按白名单从 openapi.json 生成工具；有一条不合规就抛错，服务起不来 */
export function buildTools(doc: OpenApiDoc, entries: WhitelistEntry[]): ToolDef[] {
  const names = new Set<string>()
  return entries.map((entry) => {
    const where = `tools.yaml 的 ${entry.tool}`
    const match = /^(\S+)\s+(\S+)$/.exec(entry.op.trim())
    if (!match) throw new Error(`${where}：op 要写成 "GET /api/..."`)
    const [, method, path] = match as unknown as [string, string, string]
    if (method !== 'GET') throw new Error(`${where}：白名单只收 GET，现在是 ${method}`)
    if (!path.startsWith('/api/')) throw new Error(`${where}：只能是 /api/ 下的接口`)
    if (FILE_LIKE.test(path)) throw new Error(`${where}：导出、下载、打印、模板、预览不进白名单`)
    if (!TOOL_NAME.test(entry.tool)) throw new Error(`${where}：工具名只能是小写字母、数字、下划线`)
    if (names.has(entry.tool)) throw new Error(`${where}：工具名重复`)
    names.add(entry.tool)
    if (!entry.label.trim() || entry.label.length > 20) throw new Error(`${where}：label 要写，20 个字以内`)
    if (!entry.perm.trim()) throw new Error(`${where}：要写 perm（只要登录的写 ${LOGIN_ONLY}）`)
    const op = doc.paths[path]?.get
    if (!op) throw new Error(`${where}：openapi.json 里没有 GET ${path}`)
    const content = op.responses?.['200']?.content ?? {}
    const refs = Object.values(content).map((c) => c.schema?.$ref ?? '')
    if (refs.length === 0 || !refs.every((r) => /\/schemas\/R($|[A-Z])/.test(r))) {
      throw new Error(`${where}：GET ${path} 返回的不是数据（R），不进白名单`)
    }

    const params: ParamInfo[] = []
    const properties: Record<string, unknown> = {}
    const required: string[] = []
    for (const p of op.parameters ?? []) {
      if ((p.in !== 'path' && p.in !== 'query') || DROP_PARAMS.has(p.name)) continue
      const schema = simplify(p.schema ?? { type: 'string' })
      if (schema.type === 'object') continue // 没拆开的查询对象，拿不准怎么传，不给模型
      const isRequired = p.in === 'path' || p.required === true
      params.push({ name: p.name, in: p.in, required: isRequired, schema })
      const said = tidy(p.description ?? '')
      properties[p.name] = said ? { ...schema, description: said } : schema
      if (isRequired) required.push(p.name)
    }
    const summary = tidy(op.summary ?? op.description ?? '')
    const description = [summary, entry.hint].filter(Boolean).join('。')
    const spec: ToolSpec = {
      type: 'function',
      function: {
        name: entry.tool,
        description: description.slice(0, 1000),
        parameters: { type: 'object', properties, required, additionalProperties: false },
      },
    }
    return { name: entry.tool, label: entry.label, path, perm: entry.perm, spec, params }
  })
}

/** 说明里给开发看的出处（"（docs/api/06 §2.4）""（RFC 013 §2）""甲方 11-21 答 Q10"）对模型没用，去掉省提示词 */
export function tidy(text: string): string {
  return text
    .replace(/[（(][^（）()]*(?:docs\/|RFC\s*\d|§|甲方\s*\d|用户\s*\d+-\d+)[^（）()]*[）)]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 只留模型用得上的几项，别的（format、$ref……）去掉，免得有的模型服务不认 */
function simplify(schema: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const type = schema.type
  out.type = type === 'integer' || type === 'number' || type === 'boolean' || type === 'array' || type === 'object' ? type : 'string'
  if (Array.isArray(schema.enum)) out.enum = schema.enum
  if (typeof schema.minimum === 'number') out.minimum = schema.minimum
  if (typeof schema.maximum === 'number') out.maximum = schema.maximum
  if (type === 'array') out.items = simplify((schema.items as Record<string, unknown> | undefined) ?? { type: 'string' })
  return out
}

/** 这个用户能用的工具：有权限码的，加上只要登录的 */
export function toolsFor(perms: ReadonlySet<string>, defs: ToolDef[]): ToolDef[] {
  return defs.filter((d) => d.perm === LOGIN_ONLY || perms.has(d.perm))
}

export interface ToolResult {
  ok: boolean
  /** app 的 HTTP 状态；参数不对、没调出去是 null */
  status: number | null
  /** 列表返回几条 */
  rows: number | null
  /** 给模型看的内容（JSON 文本） */
  content: string
  /** 记进 ast_tool_call 的参数 */
  args: Record<string, unknown>
}

export interface RunContext {
  app: AppClient
  token: string
  requestId: string
  maxPageSize: number
  maxResultChars: number
  signal?: AbortSignal
}

/** 调一个工具：参数按 openapi 的定义拼成地址，GET app，结果瘦身后给模型 */
export async function runTool(def: ToolDef, rawArgs: string, ctx: RunContext): Promise<ToolResult> {
  let args: Record<string, unknown>
  try {
    const parsed: unknown = rawArgs.trim() ? JSON.parse(rawArgs) : {}
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error()
    args = parsed as Record<string, unknown>
  } catch {
    return fail('参数不是 JSON 对象', {})
  }
  let path = def.path
  const query = new URLSearchParams()
  for (const p of def.params) {
    const value = args[p.name]
    if (value === undefined || value === null || value === '') {
      if (p.required) return fail(`缺参数 ${p.name}`, args)
      continue
    }
    if (p.in === 'path') {
      const text = String(value)
      if (!/^[A-Za-z0-9_\-]{1,64}$/.test(text)) return fail(`参数 ${p.name} 不对`, args)
      path = path.replace(`{${p.name}}`, encodeURIComponent(text))
    } else if (Array.isArray(value)) {
      for (const v of value.slice(0, 50)) query.append(p.name, String(v))
    } else if (typeof value === 'object') {
      return fail(`参数 ${p.name} 不对`, args)
    } else {
      query.set(p.name, String(value))
    }
  }
  if (def.params.some((p) => p.name === 'size')) {
    const size = Number(query.get('size') ?? '20')
    query.set('size', String(Number.isInteger(size) && size > 0 ? Math.min(size, ctx.maxPageSize) : 20))
  }
  if (/\{[^}]+\}/.test(path)) return fail('缺路径参数', args)

  let reply
  try {
    reply = await ctx.app.get(ctx.token, path, query, ctx.requestId, ctx.signal)
  } catch (e) {
    if (ctx.signal?.aborted) throw e
    return fail('系统暂时连不上', args)
  }
  if (reply.code !== 0) {
    return {
      ok: false,
      status: reply.status,
      rows: null,
      content: JSON.stringify({ error: reply.msg || '查询失败', code: reply.code ?? reply.status }),
      args,
    }
  }
  const { data, rows } = shrink(reply.data)
  return { ok: true, status: reply.status, rows, content: cap(JSON.stringify(data), ctx.maxResultChars), args }
}

function fail(message: string, args: Record<string, unknown>): ToolResult {
  return { ok: false, status: null, rows: null, content: JSON.stringify({ error: message }), args }
}

const DROP_KEYS = new Set(['image', 'images', 'imageUrl', 'thumbnail', 'avatar', 'url', 'fileUrl', 'colorHex', 'attachments', 'actions'])
const FILE_URL = /\/files\//

/**
 * 给模型之前瘦身：图片、文件地址（带签名，不给模型）、颜色值、按钮列表去掉；列表最多 20 行，写明总数。
 * 只删不改：留下的数字和文字都是 app 原样返回的
 */
export function shrink(data: unknown): { data: unknown; rows: number | null } {
  const MAX_ROWS = 20
  const clean = (v: unknown, depth: number): unknown => {
    if (depth > 8) return undefined
    if (typeof v === 'string') return FILE_URL.test(v) ? undefined : v
    if (Array.isArray(v)) return v.map((x) => clean(x, depth + 1)).filter((x) => x !== undefined)
    if (v && typeof v === 'object') {
      const out: Record<string, unknown> = {}
      for (const [k, x] of Object.entries(v)) {
        if (DROP_KEYS.has(k)) continue
        const c = clean(x, depth + 1)
        if (c !== undefined && c !== null) out[k] = c
      }
      return out
    }
    return v
  }
  if (data && typeof data === 'object' && !Array.isArray(data) && Array.isArray((data as { list?: unknown }).list)) {
    const page = data as { list: unknown[]; total?: number }
    const list = page.list.slice(0, MAX_ROWS).map((x) => clean(x, 1))
    const out: Record<string, unknown> = { total: page.total ?? page.list.length, list }
    if (page.list.length > MAX_ROWS) out.note = `只给了前 ${MAX_ROWS} 行，共 ${page.total ?? page.list.length} 行`
    return { data: out, rows: page.list.length }
  }
  if (Array.isArray(data)) {
    const list = data.slice(0, MAX_ROWS).map((x) => clean(x, 1))
    return { data: data.length > MAX_ROWS ? { total: data.length, list, note: `只给了前 ${MAX_ROWS} 行` } : list, rows: data.length }
  }
  return { data: clean(data, 0), rows: null }
}

function cap(text: string, max: number): string {
  if (text.length <= max) return text
  return JSON.stringify({ truncated: true, note: '结果太长，已截断；请缩小条件再查', partial: text.slice(0, max - 120) })
}
