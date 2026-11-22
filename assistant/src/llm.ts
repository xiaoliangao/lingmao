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

export interface LlmProvider {
  chat(messages: ChatMessage[], tools: ToolSpec[], onDelta: (text: string) => void, signal: AbortSignal): Promise<LlmTurn>
}

/** 模型调用失败。message 给用户看，不带 Key、不带请求内容 */
export class LlmError extends Error {
  readonly status: number | null
  constructor(message: string, status: number | null = null) {
    super(message)
    this.status = status
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

  async chat(messages: ChatMessage[], tools: ToolSpec[], onDelta: (text: string) => void, signal: AbortSignal): Promise<LlmTurn> {
    const body: Record<string, unknown> = { model: this.config.model, messages, stream: true }
    if (tools.length > 0) body.tools = tools
    if (this.config.streamUsage) body.stream_options = { include_usage: true }
    const headers: Record<string, string> = { 'Content-Type': 'application/json', Accept: 'text/event-stream' }
    if (this.config.apiKey) headers.Authorization = `Bearer ${this.config.apiKey}`

    let response: Response
    try {
      response = await this.fetchImpl(`${this.config.baseUrl}/chat/completions`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: AbortSignal.any([signal, AbortSignal.timeout(this.config.timeoutMs)]),
      })
    } catch (e) {
      if (signal.aborted) throw e
      throw new LlmError(isTimeout(e) ? '模型太久没回应' : '连不上模型服务')
    }
    if (!response.ok || !response.body) {
      throw new LlmError(`模型服务返回 ${response.status}：${await brief(response)}`, response.status)
    }
    return readStream(response.body, onDelta)
  }
}

/** 读 SSE：一行一个 data:，[DONE] 结束。拼文字、按 index 拼工具调用、记用量 */
export async function readStream(stream: ReadableStream<Uint8Array>, onDelta: (text: string) => void): Promise<LlmTurn> {
  const decoder = new TextDecoder()
  let buffer = ''
  let content = ''
  const calls = new Map<number, ToolCall>()
  let usage: Usage | null = null

  const handle = (line: string): boolean => {
    if (!line.startsWith('data:')) return false
    const data = line.slice(5).trim()
    if (data === '[DONE]') return true
    if (!data) return false
    let chunk: Chunk
    try {
      chunk = JSON.parse(data) as Chunk
    } catch {
      throw new LlmError('模型服务返回的格式不对')
    }
    if (chunk.error) throw new LlmError(`模型服务出错：${String(chunk.error.message ?? '').slice(0, 200)}`)
    if (chunk.usage) {
      usage = { promptTokens: chunk.usage.prompt_tokens ?? 0, completionTokens: chunk.usage.completion_tokens ?? 0 }
    }
    const delta = chunk.choices?.[0]?.delta
    if (delta?.content) {
      content += delta.content
      onDelta(delta.content)
    }
    for (const part of delta?.tool_calls ?? []) {
      const index = part.index ?? 0
      let call = calls.get(index)
      if (!call) {
        call = { id: part.id ?? `call_${index}`, type: 'function', function: { name: '', arguments: '' } }
        calls.set(index, call)
      }
      if (part.id) call.id = part.id
      if (part.function?.name) call.function.name += part.function.name
      if (part.function?.arguments) call.function.arguments += part.function.arguments
    }
    return false
  }

  const reader = stream.getReader()
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
  if (!done && buffer.trim()) handle(buffer.trim())
  await reader.cancel().catch(() => undefined)
  return { content, toolCalls: [...calls.entries()].sort((a, b) => a[0] - b[0]).map(([, c]) => c), usage }
}

function isTimeout(e: unknown): boolean {
  return e instanceof Error && (e.name === 'TimeoutError' || e.name === 'AbortError')
}

/** 出错时的响应体只取前 200 个字给人看 */
async function brief(response: Response): Promise<string> {
  try {
    const text = await response.text()
    try {
      const json = JSON.parse(text) as { error?: { message?: string }; message?: string }
      return String(json.error?.message ?? json.message ?? text).slice(0, 200)
    } catch {
      return text.slice(0, 200)
    }
  } catch {
    return '（读不到内容）'
  }
}
