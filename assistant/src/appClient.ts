/**
 * 替用户调 app：带浏览器换来的助手令牌（RFC 017 §3.1），只发 GET。
 * app 自己就只放行返回数据的 GET（过滤器 + 拦截器），这里再挡一层：没有发别的方法的入口，地址只能是 /api/ 下的。
 * 数据范围、打码、看成本的权限都在 app 的接口里，这里不另做
 */

/** /api/auth/me 里助手要用的几项 */
export interface Me {
  id: string
  username: string
  nickname: string
  perms: string[]
  features: { ai: boolean }
}

export interface AppReply {
  /** HTTP 状态 */
  status: number
  /** R 的 code；不是 JSON 时是 null */
  code: number | null
  msg: string
  data: unknown
}

const MAX_BODY = 2 * 1024 * 1024
const SAFE_PATH = /^\/api\/[A-Za-z0-9\-_/.]*$/

export class AppClient {
  private readonly baseUrl: string
  private readonly fetchImpl: typeof fetch

  constructor(baseUrl: string, fetchImpl: typeof fetch = fetch) {
    this.baseUrl = baseUrl
    this.fetchImpl = fetchImpl
  }

  /** 这个令牌是谁、有什么权限。401 / 403 原样带回（令牌过期、AI 功能关了、没有使用助手的权限） */
  async me(token: string, requestId: string, signal?: AbortSignal): Promise<{ reply: AppReply; me: Me | null }> {
    const reply = await this.get(token, '/api/auth/me', new URLSearchParams(), requestId, signal)
    const me = reply.code === 0 ? (reply.data as Me) : null
    return { reply, me }
  }

  async get(token: string, path: string, query: URLSearchParams, requestId: string, signal?: AbortSignal): Promise<AppReply> {
    if (!SAFE_PATH.test(path) || path.includes('..') || path.includes('//')) {
      throw new Error(`不是能查的地址：${path}`)
    }
    const qs = query.toString()
    const url = `${this.baseUrl}${path}${qs ? `?${qs}` : ''}`
    const timeout = AbortSignal.timeout(15_000)
    const response = await this.fetchImpl(url, {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', 'X-Request-Id': requestId },
      redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    const length = Number(response.headers.get('content-length') ?? '0')
    if (length > MAX_BODY) {
      await response.body?.cancel()
      return { status: response.status, code: null, msg: '返回的内容太大', data: null }
    }
    const text = await response.text()
    if (text.length > MAX_BODY) return { status: response.status, code: null, msg: '返回的内容太大', data: null }
    try {
      const json = JSON.parse(text) as { code?: number; msg?: string; data?: unknown }
      return { status: response.status, code: typeof json.code === 'number' ? json.code : null, msg: json.msg ?? '', data: json.data ?? null }
    } catch {
      return { status: response.status, code: null, msg: `app 返回的不是 JSON（HTTP ${response.status}）`, data: null }
    }
  }
}
