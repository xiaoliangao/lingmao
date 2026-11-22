import { randomUUID } from 'node:crypto'
import type { LlmConfig } from './config.ts'

/**
 * 调大模型：OpenAI 兼容的 chat completions 接口（国内多数模型服务、自部署的 vLLM / Ollama 都认这个格式）。
 * 直接按接口格式用 fetch 写，不用厂商 SDK：发出去的只有这里拼的这些字段，审起来一眼看完；换模型只改 .env。
 * 一律流式：文字一段段回给浏览器，工具调用的参数分几段到，按 index 拼起来
 */

export interface ToolCall {
  id: string
  type: 'function'
  function: { name: string; arguments: string }
}

export type ChatMessage =
  | { role: 'system' | 'user'; content: string }
  | { role: 'assistant'; content: string | null; tool_calls?: ToolCall[] }
  | { role: 'tool'; tool_call_id: string; content: string }

export interface ToolSpec {
  type: 'function'
  function: { name: string; description: string; parameters: Record<string, unknown> }
}

export interface Usage {
  promptTokens: number
  completionTokens: number
}

export interface LlmTurn {
  content: string
  toolCalls: ToolCall[]
  /** 接口没回用量时是 null（有的接口流式不给 usage） */
  usage: Usage | null
}

export interface ChatOptions {
  /** none：这一轮不许再调工具，必须用文字回答（最后一轮用） */
  toolChoice?: 'none'
}

export interface LlmProvider {
  chat(messages: ChatMessage[], tools: ToolSpec[], onDelta: (text: string) => void, signal: AbortSignal, options?: ChatOptions): Promise<LlmTurn>
}

/**
 * 模型调用失败。message 给用户看（浏览器、对话记录里都是它）：只说哪类问题、找谁，不带对方的原话。
 * detail 是对方返回的原话（截断、Key 打码），只进日志给管理员查
 */
export class LlmError extends Error {
  readonly status: number | null
  readonly detail: string
  constructor(message: string, status: number | null = null, detail = '') {
    super(message)
    this.status = status
    this.detail = detail
  }
}

interface ChunkToolCall {
  index?: number
  id?: string
  function?: { name?: string; arguments?: string }
}

interface Chunk {
  choices?: { delta?: { content?: string | null; tool_calls?: ChunkToolCall[] } }[]
  usage?: { prompt_tokens?: number; completion_tokens?: number } | null
  error?: { message?: string }
}

export class OpenAiCompatible implements LlmProvider {
  private readonly config: LlmConfig
  private readonly fetchImpl: typeof fetch

  constructor(config: LlmConfig, fetchImpl: typeof fetch = fetch) {
    this.config = config
    this.fetchImpl = fetchImpl
  }

  async chat(messages: ChatMessage[], tools: ToolSpec[], onDelta: (text: string) => void, signal: AbortSignal, options: ChatOptions = {}): Promise<LlmTurn> {
    const body: Record<string, unknown> = { model: this.config.model, messages, stream: true }
    if (tools.length > 0) {
      body.tools = tools
      if (options.toolChoice) body.tool_choice = options.toolChoice
    }
    if (this.config.streamUsage) body.stream_options = { include_usage: true }
    const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
    if (this.config.apiKey) headers.Authorization = `Bearer ${this.config.apiKey}`

    // 超时管整个回合（连上、等第一个字、读完流），流读到一半卡住、断开也在这里变成 LlmError
    const timeout = AbortSignal.timeout(this.config.timeoutMs)
    try {
      const response = await this.fetchImpl(`${this.config.baseUrl}/chat/completions`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: AbortSignal.any([signal, timeout]),
      })
      if (!response.ok || !response.body) {
        throw new LlmError(statusMessage(response.status), response.status, await brief(response))
      }
      return await readStream(response.body, onDelta)
    } catch (e) {
      if (signal.aborted) throw e
      if (e instanceof LlmError) throw e
      if (timeout.aborted || isTimeout(e)) throw new LlmError('模型太久没回应，稍后再问')
      throw new LlmError('和模型服务的连接断了，稍后再问', null, e instanceof Error ? e.message : String(e))
    }
  }
}

/** 对方回 HTTP 错误时给用户看的话：按类别说，不转述对方的原话（可能带 Key 片段、内网地址） */
function statusMessage(status: number): string {
  if (status === 401 || status === 403) return '模型服务不认这个 Key（或没开通这个模型），请联系管理员'
  if (status === 404) return '模型服务上找不到配置的模型，请联系管理员'
  if (status === 429) return '模型服务太忙（限流或额度用完），稍后再问'
  if (status >= 500) return '模型服务出错了，稍后再问'
  return `模型服务不接受这次请求（HTTP ${status}），请联系管理员`
}

/**
 * 读 SSE：一行一个 data:，[DONE] 结束。拼文字、拼工具调用、记用量。
 * 工具调用按 index 拼；有的服务不给 index，就按 id 分（同一个 id 是同一个调用的后续片段），id 也没有就接在上一个后面
 */
export async function readStream(stream: ReadableStream<Uint8Array>, onDelta: (text: string) => void): Promise<LlmTurn> {
  const decoder = new TextDecoder()
  let buffer = ''
  let content = ''
  const calls = new Map<number, ToolCall>()
  let lastIndex = -1
  let usage: Usage | null = null
  let sawData = false
  let other = '' // 不是 data: 的行（HTTP 200 却回了一个 JSON 错误的服务）

  const handle = (line: string): boolean => {
    if (!line.startsWith('data:')) {
      if (other.length < 2000 && line.trim()) other += line.trim().slice(0, 2000 - other.length)
      return false
    }
    const data = line.slice(5).trim()
    if (data === '[DONE]') {
      sawData = true
      return true
    }
    if (!data) return false
    sawData = true
    let chunk: Chunk
    try {
      chunk = JSON.parse(data) as Chunk
    } catch {
      throw new LlmError('模型服务返回的格式不对，请联系管理员', null, redact(data.slice(0, 200)))
    }
    if (chunk.error) throw new LlmError('模型服务出错了，稍后再问', null, redact(String(chunk.error.message ?? '').slice(0, 200)))
    if (chunk.usage) {
      usage = { promptTokens: chunk.usage.prompt_tokens ?? 0, completionTokens: chunk.usage.completion_tokens ?? 0 }
    }
    const delta = chunk.choices?.[0]?.delta
    if (delta?.content) {
      content += delta.content
      onDelta(delta.content)
    }
    for (const part of delta?.tool_calls ?? []) {
      let index: number
      if (typeof part.index === 'number') index = part.index
      else if (part.id) index = [...calls].find(([, c]) => c.id === part.id)?.[0] ?? (calls.size === 0 ? 0 : Math.max(...calls.keys()) + 1)
      else index = lastIndex >= 0 ? lastIndex : 0
      lastIndex = index
      let call = calls.get(index)
      if (!call) {
        call = { id: part.id ?? '', type: 'function', function: { name: '', arguments: '' } }
        calls.set(index, call)
      }
      if (part.id) call.id = part.id
      if (part.function?.name) call.function.name += part.function.name
      if (part.function?.arguments) call.function.arguments += part.function.arguments
    }
    return false
  }

  const reader = stream.getReader()
  try {
    let done = false
    while (!done) {
      const { value, done: end } = await reader.read()
      if (end) break
      buffer += decoder.decode(value, { stream: true })
      let newline: number
      while (!done && (newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '')
        buffer = buffer.slice(newline + 1)
        done = handle(line)
      }
    }
    buffer += decoder.decode()
    if (!done && buffer.trim()) handle(buffer.trim())
  } finally {
    await reader.cancel().catch(() => undefined)
  }
  if (!sawData) throw new LlmError('模型服务没有按流式返回结果，请联系管理员', null, redact(other))
  // 没给 id 的调用补一个：同一组消息里 tool_call_id 不能重复
  const toolCalls = [...calls.entries()].sort((a, b) => a[0] - b[0]).map(([, c]) => (c.id ? c : { ...c, id: `call_${randomUUID().slice(0, 12)}` }))
  return { content, toolCalls, usage }
}

function isTimeout(e: unknown): boolean {
  return e instanceof Error && e.name === 'TimeoutError'
}

/** 对方原话里像 Key 的部分打码（sk-xxxx、Bearer xxx），日志里也不留 */
export function redact(text: string): string {
  return text.replace(/\b(sk|ak|key)-[A-Za-z0-9_\-]{4,}/gi, '$1-***').replace(/Bearer\s+[A-Za-z0-9._\-]+/gi, 'Bearer ***')
}

/** 出错时的响应体最多读 4KB，取前 200 个字进日志 */
async function brief(response: Response): Promise<string> {
  try {
    const text = await readCapped(response, 4096)
    try {
      const json = JSON.parse(text) as { error?: { message?: string }; message?: string }
      return redact(String(json.error?.message ?? json.message ?? text).slice(0, 200))
    } catch {
      return redact(text.slice(0, 200))
    }
  } catch {
    return '（读不到内容）'
  }
}

/** 读响应体，最多 max 字节，多的不读（对方回一个几十 MB 的错误页也不会整个读进内存） */
export async function readCapped(response: Response, max: number): Promise<string> {
  if (!response.body) return ''
  const reader = response.body.getReader()
  const parts: Uint8Array[] = []
  let size = 0
  try {
    while (size < max) {
      const { value, done } = await reader.read()
      if (done) break
      parts.push(value)
      size += value.length
    }
  } finally {
    await reader.cancel().catch(() => undefined)
  }
  const all = new Uint8Array(Math.min(size, max))
  let at = 0
  for (const p of parts) {
    const take = Math.min(p.length, all.length - at)
    all.set(p.subarray(0, take), at)
    at += take
    if (at >= all.length) break
  }
  return new TextDecoder().decode(all)
}
