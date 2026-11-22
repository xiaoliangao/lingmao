import type {
  AssistantChatRequest,
  AssistantConversation,
  AssistantEvent,
  AssistantHealth,
  AssistantInfo,
  AssistantMessage,
} from '@/types/assistant'
import { readSse } from '@/utils/sse'

/**
 * 智能助手服务的接口（docs/api/08-智能助手.md §2）。不走 utils/request.ts：带的是助手令牌（不是登录令牌），问话是流式的，
 * 出错也不自动弹消息——抽屉里自己显示。令牌由 composables/useAssistant.ts 换、管、401 时重换
 */

/** 助手服务没答成：status 是 HTTP 状态（连不上是 0），message 能直接给用户看 */
export class AssistantError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

const NOT_RUNNING = '智能助手没有启动，请联系管理员'

/** 不是 2xx：有 R 就用它的 msg；网关的错误页、没配代理时的 index.html，都当没启动 */
async function failure(res: Response): Promise<AssistantError> {
  if ((res.headers.get('content-type') ?? '').includes('application/json')) {
    try {
      const body = (await res.json()) as { msg?: unknown }
      if (typeof body.msg === 'string' && body.msg) return new AssistantError(res.status, body.msg)
    } catch {
      // 不是合法的 JSON，按状态说
    }
  }
  return new AssistantError(res.status || 502, NOT_RUNNING)
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(path, { cache: 'no-store', credentials: 'omit', ...init })
  } catch (e) {
    if (init.signal?.aborted) throw e
    throw new AssistantError(0, '连不上智能助手，检查一下网络')
  }
}

async function getJson<T>(path: string, token?: string, signal?: AbortSignal): Promise<T> {
  const res = await send(path, { headers: token ? { Authorization: `Bearer ${token}` } : {}, signal })
  if (!res.ok || !(res.headers.get('content-type') ?? '').includes('application/json')) throw await failure(res)
  const body = (await res.json()) as { code: number; msg: string; data: T }
  if (body.code !== 0) throw new AssistantError(res.status, body.msg)
  return body.data
}

export const assistantApi = {
  /** 服务好不好、配没配模型。库连不上、容器没起都是 AssistantError（503） */
  health: () => getJson<AssistantHealth>('/assistant/health'),
  /** 模型是外部的还是内网的（打开 AI 开关前的提醒）。signal 中止时抛 AbortError */
  info: (signal?: AbortSignal) => getJson<AssistantInfo>('/assistant/info', undefined, signal),
  /** 自己最近的对话，最多 20 个 */
  conversations: (token: string) => getJson<AssistantConversation[]>('/assistant/conversations', token),
  /** 一个对话的全部问答；别人的、不存在的 404 */
  messages: (token: string, id: string) => getJson<AssistantMessage[]>(`/assistant/conversations/${encodeURIComponent(id)}`, token),

  /**
   * 问一句。回的不是 SSE 就抛 AssistantError（401 由调用方换令牌重发一次）；是 SSE 就一个个事件交给 onEvent，流结束才返回。
   * signal 中止（用户点停止）时抛 AbortError；流读到一半断了抛别的错。认不出的事件、坏的 JSON 跳过
   */
  async chat(token: string, body: AssistantChatRequest, onEvent: (event: AssistantEvent) => void, signal: AbortSignal): Promise<void> {
    const res = await send('/assistant/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
      signal,
    })
    if (!res.ok || !res.body || !(res.headers.get('content-type') ?? '').includes('text/event-stream')) throw await failure(res)
    try {
      await readSse(res.body, (event, data) => {
        let parsed: unknown
        try {
          parsed = JSON.parse(data)
        } catch {
          return
        }
        if (parsed && typeof parsed === 'object' && ['meta', 'tool', 'delta', 'done', 'error'].includes(event)) {
          onEvent({ event, data: parsed } as AssistantEvent)
        }
      })
    } catch (e) {
      await res.body.cancel().catch(() => undefined)
      throw e
    }
  },
}
