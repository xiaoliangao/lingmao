import { ref } from 'vue'
import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { showBizError } from '@/composables/useBizError'
import { LIST_ERROR_CODES } from './errorCodes'
import { clearToken, getToken, sessionChanged, sessionEpoch, sessionIdentity, setToken } from './token'
import { clientActionUuid, unknownActionResult } from './clientAction'
import { currentReadScope } from './requestScope'
import { singleFlight } from './singleFlight'
import { validateKeywords } from './keywords'
import { onSessionChanged } from './token'
import { readWriteReceipts, removeWriteReceipt, requestFingerprint, storeWriteReceipt, type WriteReceipt } from './pendingWrite'
import { currentCompanyScope } from './companyScope'
import { canReplayWrite } from './writePolicy'

/** 后端所有接口的统一返回体 */
export interface R<T> {
  code: number
  msg: string
  data: T
}

/**
 * 单个请求的选项。silent：出错不弹提示，由调用方自己汇总（批量操作条 BatchBar 逐条调接口时就这样）。
 * 接口函数想让调用方能静默，就多收一个 options 原样传下来：
 *   disable: (id: string, options?: RequestOptions) => http.post<void>(`/api/materials/${id}/disable`, undefined, options)
 */
export interface RequestOptions {
  silent?: boolean
  signal?: AbortSignal
  /** The page's document company, never a mutable cross-tab default. */
  companyId?: string
  idempotencyKey?: string
  /** Explicitly declared calculations such as BOM defaults, although transported as POST. */
  readOnly?: boolean
  /** Internal wrapper context captured before an asynchronous company lookup. */
  authEpoch?: string
}
type RequestConfig = AxiosRequestConfig<unknown> & RequestOptions & { replayed?: boolean }

function isSilent(config: AxiosRequestConfig<unknown> | undefined): boolean {
  return !!config && 'silent' in config && config.silent === true
}

/** 接口出错。消息已经在下面的拦截器里弹过了（silent 的除外），调用方不用再提示 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    /** 出错时后端带的数据，如 1003 的错误清单（见 errorCodes.ts）；没有就是 null */
    public readonly data: unknown = null,
    /** 这次请求的错误编号（响应头 X-Trace-Id），在服务器日志里 grep 它能找到这次请求的全部日志 */
    public readonly traceId: string | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 上传文件的大小上限，和后端 FileServiceImpl 一致：一般 10MB；设计源文件 AI、PSD 100MB（只有款式的设计附件收） */
export const MAX_UPLOAD_SIZE = 10 * 1024 * 1024
export const MAX_DESIGN_SOURCE_SIZE = 100 * 1024 * 1024

/** 上传、下载用的超时：10MB 的文件在慢网上要传几十秒，默认的 15 秒不够 */
const TRANSFER_TIMEOUT = 120_000
/** 大文件按每秒 100KB 的慢网算超时：100MB 的 PSD 给 17 分钟 */
const SLOW_BYTES_PER_SECOND = 100_000

export const instance = axios.create({
  timeout: 15000,
  // 数组参数发成 status=A&status=B，Spring 用 List 接得住；axios 默认的 status[]=A 它不认
  paramsSerializer: { indexes: null },
})

// 请求前：带上 token
instance.interceptors.request.use((config) => {
  config.headers['X-Request-Id'] ??= clientActionUuid()
  const token = getToken()
  if (token && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let refreshHandler: (() => Promise<string>) | undefined
class SessionRefreshFailure extends Error {}
/** Enabled only after the server cookie refresh contract is installed. */
export function configureSessionRefresh(handler: (() => Promise<string>) | undefined) { refreshHandler = handler }
function assertRefreshSession(epoch: string) {
  if (sessionChanged() || sessionEpoch() !== epoch) throw new ApiError(409, '登录身份已变化，请重新打开本页')
}
const refreshOnce = singleFlight(async (failedToken: string, epoch: string) => {
  const rotate = async () => {
    // A different tab may have completed its rotation while this tab waited for the lock.
    assertRefreshSession(epoch)
    if (getToken() !== failedToken) return getToken()
    if (!refreshHandler) throw new ApiError(401, '登录已过期，请重新登录')
    let token: string
    try { token = await refreshHandler() } catch (error) {
      assertRefreshSession(epoch)
      throw error
    }
    assertRefreshSession(epoch)
    setToken(token, true)
    return token
  }
  // On browsers without Web Locks the server's bounded successor replay window covers overlap.
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request(`lingmao:session-refresh:${epoch}`, { mode: 'exclusive' }, rotate)
  }
  return rotate()
})
export function enableCookieSessionRefresh() {
  configureSessionRefresh(async () => {
    const response = await axios.post<R<string>>('/api/auth/refresh', {}, { timeout: 15_000, withCredentials: true, headers: { 'X-Requested-With': 'XMLHttpRequest' } })
    if (response.data.code !== 0 || typeof response.data.data !== 'string') throw new ApiError(401, '登录已过期，请重新登录')
    return response.data.data
  })
}
async function replayAfter401(response: AxiosResponse<unknown>, code: number): Promise<AxiosResponse<unknown> | null> {
  const config = response.config as typeof response.config & { replayed?: boolean; readOnly?: boolean; authEpoch?: string }
  if (code !== 401 || config.url?.startsWith('/api/auth/')) return null
  const epoch = config.authEpoch ?? sessionEpoch()
  assertRefreshSession(epoch)
  if (!refreshHandler || !getToken() || config.replayed) return null
  const method = (config.method ?? 'get').toLowerCase()
  if (!['get', 'head'].includes(method) && !config.readOnly && (!config.headers['Idempotency-Key'] || !canReplayWrite(method, config.url ?? ''))) return null
  config.replayed = true
  const sentToken = String(config.headers.Authorization ?? '').replace(/^Bearer /, '')
  // A late 401 from the old access token must not rotate the newly issued cookie again.
  if (sentToken === getToken()) {
    try { await refreshOnce(sentToken, epoch) } catch (error) {
      if (error instanceof ApiError && error.code === 409) throw error
      throw new SessionRefreshFailure('登录已过期，请重新登录')
    }
  }
  assertRefreshSession(epoch)
  if (config.signal?.aborted) throw new axios.CanceledError()
  config.headers.Authorization = `Bearer ${getToken()}`
  return instance.request(config)
}

// 收到响应后：code 是 0 才算成功；401 跳登录页；带清单的错误弹表格；其他错误弹出 msg
instance.interceptors.response.use(
  async (response) => {
    // 下载文件时拿到的就是文件，原样交出去；出错时后端回的还是 JSON 的 R，下面读出来按普通错误处理
    if (response.data instanceof Blob && !isJson(response)) {
      return response
    }
    const body = await readBody(response)
    if (body?.code === 0) {
      response.data = body
      return response
    }
    try {
      const replay = await replayAfter401(response, body?.code ?? response.status)
      if (replay) return replay
    } catch (error) {
      if (error instanceof SessionRefreshFailure) throw fail({ code: 401, msg: error.message, data: null }, response)
      throw error
    }
    throw fail(body ?? { code: -1, msg: '服务器返回的内容不对，请稍后再试', data: null }, response)
  },
  async (error: AxiosError<unknown, unknown>) => {
    // HTTP 状态不是 2xx。后端一律回 200，到这里多半是网关：升级期间 Nginx 回的也是 R，照普通错误处理
    if (axios.isCancel(error)) throw error
    const body = error.response ? await readBody(error.response) : null
    if (error.response) {
      try {
        const replay = await replayAfter401(error.response, body?.code ?? error.response.status)
        if (replay) return replay
      } catch (refreshError) {
        if (refreshError instanceof SessionRefreshFailure) throw fail({ code: 401, msg: refreshError.message, data: null }, error.response)
        throw refreshError
      }
    }
    if (error.response?.status === 401 && !body) throw fail({ code: 401, msg: '登录已过期，请重新登录', data: null }, error.response)
    if (body && error.response) {
      throw fail(body, error.response)
    }
    let message = '连不上服务器，请检查网络后再试'
    if (error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT) {
      message = '服务器太久没有回应，请稍后再试'
    } else if (error.response?.status === 413) {
      // 超过 Nginx 的上传上限，请求没到后端
      message = '文件太大：AI、PSD 最大 100MB，其他文件最大 10MB'
    } else if (error.response) {
      message = `服务器出错了（HTTP ${error.response.status}），请稍后再试`
    }
    if (!isSilent(error.config)) {
      ElMessage.error(message)
    }
    throw new ApiError(-1, message)
  },
)

/**
 * 只给 web/src/mock 用（pnpm dev:mock）：后端还没写的接口先由假数据回答。answer 返回 undefined = 不归 mock 管，照常发给后端；
 * 返回 R 就当后端回了这个（code 不是 0 照样走下面的出错处理），返回 Blob 就当下载到的文件。正式打包不会调它
 */
export function installMock(answer: (config: InternalAxiosRequestConfig<unknown>) => Promise<R<unknown> | Blob> | undefined) {
  const real = axios.getAdapter(instance.defaults.adapter)
  instance.defaults.adapter = async (config) => {
    const answered = answer(config)
    if (!answered) return real(config)
    const data = await answered
    const contentType = data instanceof Blob ? data.type || 'application/octet-stream' : 'application/json'
    return { data, status: 200, statusText: 'OK', headers: { 'content-type': contentType }, config, request: null }
  }
}

/** 后端说出错了：401 回登录页，带清单的弹表格，其他弹一行消息。返回给调用方的 ApiError */
let redirectingToLogin = false
function fail(body: R<unknown>, response: AxiosResponse<unknown>): ApiError {
  if (body.code === 401) {
    // Concurrent failures share one logout/navigation. Reassigning location for each
    // response aborts the login navigation and can leave the expired page visible.
    if (!redirectingToLogin) {
      clearToken()
      if (!location.pathname.startsWith('/login')) {
        redirectingToLogin = true
        location.replace(`/login?redirect=${encodeURIComponent(location.pathname + location.search)}`)
      }
    }
  } else if (!isSilent(response.config)) {
    if (LIST_ERROR_CODES.includes(body.code) && Array.isArray(body.data) && body.data.length > 0) {
      showBizError(body.code, body.msg, body.data)
    } else {
      const trace = response.headers['x-request-id'] ?? response.headers['x-trace-id']
      ElMessage.error(`${body.msg || '请求失败'}${typeof trace === 'string' ? `（追踪码 ${trace}）` : ''}`)
    }
  }
  const traceId = response.headers['x-request-id'] ?? response.headers['x-trace-id']
  return new ApiError(body.code, body.msg, body.data ?? null, typeof traceId === 'string' ? traceId : null)
}

function isJson(response: AxiosResponse<unknown>) {
  return /json/i.test(String(response.headers['content-type'] ?? ''))
}

/** 从响应里读出 R。下载时 data 是 Blob，要先读成文本；读不出来（比如网关的 HTML 错误页）返回 null */
async function readBody(response: AxiosResponse<unknown>): Promise<R<unknown> | null> {
  let data: unknown = response.data
  if (data instanceof Blob) {
    try {
      data = JSON.parse(await data.text())
    } catch {
      return null
    }
  }
  const isR = typeof data === 'object' && data !== null && typeof (data as R<unknown>).code === 'number'
  return isR ? (data as R<unknown>) : null
}

/** Only shape defaults are normalized. Null monetary/cost blocks keep their meaning. */
function normalizePage<T>(data: T): T {
  if (data && typeof data === 'object' && 'list' in data && 'total' in data) {
    const page = data as { list: unknown; total: unknown }
    if (page.list == null) page.list = []
    if (!Array.isArray(page.list) || typeof page.total !== 'number' || !Number.isSafeInteger(page.total) || page.total < 0) {
      throw new ApiError(500, '列表返回格式异常，请重试')
    }
  }
  return data
}
interface PendingWrite extends WriteReceipt {
  body?: string
  promise?: Promise<unknown>
  retry?: () => Promise<unknown>
  confirmed?: boolean
}
export interface PendingWriteNotice {
  key: string
  scope: string
  title: string
  createdAt: number
  state: 'unknown' | 'checking' | 'confirmed'
  canRetry: boolean
  manualCheck: boolean
  message: string
}
export const pendingWriteNotices = ref<PendingWriteNotice[]>([])
export const pendingWriteDialog = ref(false)
const pendingWrites = new Map<string, PendingWrite>()
const RESOURCE_NAMES: Record<string, string> = { orders: '需求订单', 'order-changes': '订单变更', 'purchase-orders': '采购单', 'mrp-calcs': '算料单', 'inventory-docs': '出入库单', 'inventory-counts': '盘点单', 'production-orders': '生产单', materials: '物料', suppliers: '供应商', styles: '款式' }
function pendingNotice(action: PendingWrite, conflict = false) {
  const existing = pendingWriteNotices.value.find((notice) => notice.key === action.key)
  const replayable = !conflict && canReplayWrite(action.scope.split('|')[2] ?? '', action.url)
  if (existing) { existing.canRetry = !!action.retry && replayable; existing.manualCheck = !replayable || !action.retry; if (conflict) existing.message = '同一页面发现多笔未确认操作。请在业务列表逐笔核对结果后结束对应操作，系统不会自动选择其中一笔重放。'; return }
  pendingWriteNotices.value.push({
    key: action.key,
    scope: action.scope,
    title: `${RESOURCE_NAMES[action.url.split('/')[2] ?? ''] ?? '业务'}提交`,
    createdAt: action.createdAt,
    state: 'unknown',
    canRetry: !!action.retry && replayable,
    manualCheck: !replayable || !action.retry,
    message: conflict ? '同一页面发现多笔未确认操作。请在业务列表逐笔核对结果后结束对应操作，系统不会自动选择其中一笔重放。' : !replayable ? '此操作暂不支持安全重放。请先在对应业务列表或详情核对结果，再结束本次操作。' : action.retry ? '前次提交结果尚未确认，请重试原提交以核对结果。' : '前次提交结果尚未确认。请恢复原表单后再次提交以核对；系统会校验内容并沿用原提交凭据。',
  })
}
/** Reload restores only a receipt/hash. Matching form data must be supplied again by the page. */
export function restorePendingWrites() {
  const identity = sessionIdentity()
  for (const receipt of readWriteReceipts()) {
    if (receipt.identity !== identity || pendingWrites.has(receipt.key)) continue
    pendingWrites.set(receipt.key, { ...receipt })
  }
  const owned = [...pendingWrites.values()].filter((action) => action.identity === identity)
  const counts = new Map<string, number>()
  for (const action of owned) counts.set(action.scope, (counts.get(action.scope) ?? 0) + 1)
  for (const action of owned) if (!action.promise) pendingNotice(action, (counts.get(action.scope) ?? 0) > 1)
  pendingWriteNotices.value = pendingWriteNotices.value.filter((notice) => pendingWrites.get(notice.key)?.identity === identity)
}

function forgetWrite(key: string) {
  // If storage became unavailable, keep the receipt so a reload never silently issues a new key.
  if (!removeWriteReceipt(key)) return false
  pendingWrites.delete(key)
  pendingWriteNotices.value = pendingWriteNotices.value.filter((item) => item.key !== key)
  if (!pendingWriteNotices.value.length) pendingWriteDialog.value = false
  return true
}
export async function checkPendingWrite(key: string) {
  const action = pendingWrites.get(key)
  const notice = pendingWriteNotices.value.find((item) => item.key === key)
  if (!action?.retry || !notice?.canRetry || notice.state === 'checking') return
  if (sessionChanged() || action.identity !== sessionIdentity()) { notice.message = '账号已变化，请重新登录原账号后核对。'; return }
  notice.state = 'checking'
  try {
    const result = await action.retry()
    action.confirmed = true
    notice.state = 'confirmed'
    const no = result && typeof result === 'object' && 'no' in result && typeof result.no === 'string' ? result.no : ''
    notice.message = `前次提交已确认成功${no ? `：${no}` : ''}。请到对应列表核对，再开始新动作。`
  } catch (error) {
    if (error instanceof ApiError && !unknownActionResult(error.code) && ![401, 403, 409, 1002].includes(error.code)) {
      action.confirmed = true
      notice.state = 'confirmed'
      notice.message = `前次请求未成功：${error.message}。确认后可修改并重新提交。`
    } else {
      notice.state = 'unknown'
      notice.message = error instanceof Error ? error.message : '仍未能确认，请稍后重试原提交。'
    }
  }
}
export function acknowledgePendingWrite(key: string) {
  if (!pendingWrites.get(key)?.confirmed) return
  if (!forgetWrite(key)) ElMessage.error('无法清除已确认的提交凭据，请恢复浏览器存储后重试')
}
export function acknowledgeManualWrite(key: string) {
  const action = pendingWrites.get(key)
  if (!action || !pendingWriteNotices.value.find((notice) => notice.key === key)?.manualCheck) return
  if (!forgetWrite(key)) ElMessage.error('无法清除提交凭据，请恢复浏览器存储后重试')
}
const activeRequests = new Set<AbortController>()
onSessionChanged(() => { for (const controller of activeRequests) controller.abort() })
function assertSession(url: string | undefined) {
  if (!url?.startsWith('/api/auth/') && sessionChanged()) throw new ApiError(409, '另一个标签页已切换账号，请重新打开本页')
}
function prepareRequest(config: RequestConfig): RequestConfig {
  assertSession(config.url)
  const epoch = config.authEpoch ?? sessionEpoch()
  if (!config.url?.startsWith('/api/auth/') && epoch !== sessionEpoch()) throw new ApiError(409, '登录身份已变化，请重新打开本页')
  const readOnly = config.readOnly || ['get', 'head'].includes((config.method ?? 'get').toLowerCase())
  const payload = config.data
  const bodyCompany = payload && typeof payload === 'object' && 'companyId' in payload && typeof payload.companyId === 'string' ? payload.companyId : undefined
  const paramsCompany = config.params && typeof config.params.companyId === 'string' ? config.params.companyId as string : undefined
  const pageCompany = config.companyId ?? currentCompanyScope()
  if (bodyCompany && pageCompany && bodyCompany !== pageCompany) throw new ApiError(409, '表单主体与当前单据不一致，请重新核对主体')
  const company = pageCompany ?? bodyCompany ?? paramsCompany
  const headers = { ...config.headers, ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}), ...(company ? { 'X-Company-Id': company } : {}) }
  return { ...config, authEpoch: epoch, companyId: company, headers, signal: config.signal ?? (readOnly ? currentReadScope()?.signal : undefined) }
}
/** Freeze JSON payloads before an API wrapper awaits a prerequisite; Vue proxies are supported. */
export function snapshotRequestBody<T>(body: T): T {
  return body === undefined ? body : JSON.parse(JSON.stringify(body)) as T
}
/** The same gate/controller lifecycle serves JSON, uploads and downloaded files. */
function transport<T>(prepared: RequestConfig, epoch: string): Promise<AxiosResponse<T>> {
  try {
    assertSession(prepared.url)
    if (!prepared.url?.startsWith('/api/auth/') && epoch !== sessionEpoch()) throw new ApiError(409, '登录身份已变化，请重新打开本页')
  } catch (error) { return Promise.reject(error) }
  const controller = new AbortController()
  const source = prepared.signal
  const abort = () => controller.abort()
  if (source?.aborted) controller.abort()
  else source?.addEventListener?.('abort', abort, { once: true })
  activeRequests.add(controller)
  return instance.request<T>({ ...prepared, signal: controller.signal }).finally(() => {
    activeRequests.delete(controller)
    source?.removeEventListener?.('abort', abort)
  })
}
export function request<T>(config: RequestConfig): Promise<T> {
  let prepared: RequestConfig
  try {
    validateKeywords((config.params as { keyword?: unknown } | undefined)?.keyword)
    prepared = prepareRequest(config)
    restorePendingWrites()
  } catch (error) {
    const failure = error instanceof ApiError ? error : new ApiError(400, error instanceof Error ? error.message : '请求内容不合法')
    if (!config.silent) ElMessage.error(failure.message)
    return Promise.reject(failure)
  }
  const method = (config.method ?? 'get').toLowerCase()
  const epoch = sessionEpoch()
  const send = () => transport<R<T>>(prepared, epoch).then((response) => normalizePage(response.data.data))
  if (config.readOnly || ['get', 'head'].includes(method) || config.url?.startsWith('/api/auth/') || config.data instanceof FormData) return send()
  return performWrite(config, prepared, send, method, epoch)
}
async function performWrite<T>(config: RequestConfig, prepared: RequestConfig, send: () => Promise<T>, method: string, epoch: string): Promise<T> {
  const clientFailure = (code: number, message: string) => {
    if (!config.silent) ElMessage.error(message)
    return new ApiError(code, message)
  }
  const identity = sessionIdentity()
  const url = config.url ?? ''
  // Neither company nor caller-created UUID may bypass an unresolved action on this page.
  const scope = `${identity}|${location.pathname}|${method}|${url.split('?')[0]}`
  let action = [...pendingWrites.values()].find((item) => item.scope === scope)
  let body = JSON.stringify(config.data ?? null)
  prepared.data = config.data === undefined ? undefined : JSON.parse(body)
  const payload = prepared.data
  if (action && payload && typeof payload === 'object' && 'clientActionId' in payload) {
    payload.clientActionId = action.key
    body = JSON.stringify(payload)
  }
  let fingerprint: string
  try { fingerprint = await requestFingerprint(body, prepared.companyId, url) } catch {
    throw clientFailure(409, '无法生成安全提交凭据，本次尚未发送，请稍后重试')
  }
  assertSession(config.url)
  if (epoch !== sessionEpoch()) throw new ApiError(409, '登录身份已变化，请重新打开本页')
  // Digest work yields; another click may already have installed this action.
  restorePendingWrites()
  const matches = [...pendingWrites.values()].filter((item) => item.scope === scope)
  if (matches.length > 1) {
    matches.forEach((item) => pendingNotice(item, true))
    pendingWriteDialog.value = true
    throw new ApiError(1002, '同一页面有多笔提交待核对，请先逐笔确认结果')
  }
  action = matches[0]
  if (action && action.fingerprint !== fingerprint) {
    pendingWrites.set(action.key, action)
    pendingNotice(action)
    pendingWriteDialog.value = true
    throw new ApiError(1002, '请先在待确认操作中核对前次提交，再开始新动作')
  }
  if (action?.promise) return action.promise as Promise<T>
  if (action && !canReplayWrite(method, url)) {
    pendingWrites.set(action.key, action)
    pendingNotice(action)
    pendingWriteDialog.value = true
    throw new ApiError(1002, '此操作暂不支持安全重放，请先核对前次结果')
  }
  if (!action) {
    const explicit = config.idempotencyKey ?? (payload && typeof payload === 'object' && 'clientActionId' in payload && typeof payload.clientActionId === 'string' ? payload.clientActionId : undefined)
    action = { scope, identity, key: explicit ?? clientActionUuid(), fingerprint, body, url, companyId: prepared.companyId, createdAt: Date.now() }
  }
  const { scope: receiptScope, identity: owner, key, fingerprint: digest, url: endpoint, companyId, createdAt } = action
  if (!storeWriteReceipt({ scope: receiptScope, identity: owner, key, fingerprint: digest, url: endpoint, companyId, createdAt })) {
    throw clientFailure(409, '无法记录提交凭据，本次尚未发送。请允许浏览器存储后重试')
  }
  prepared.headers = { ...prepared.headers, 'Idempotency-Key': action.key }
  action.body = body
  action.retry = send
  pendingWrites.set(action.key, action)
  const notice = pendingWriteNotices.value.find((item) => item.key === key)
  if (notice) notice.canRetry = canReplayWrite(method, url)
  action.promise = send().then((value) => {
    if (!forgetWrite(action.key)) { action.confirmed = true; pendingNotice(action); const saved = pendingWriteNotices.value.find((item) => item.key === action.key); if (saved) { saved.state = 'confirmed'; saved.message = '提交已成功，但浏览器未能清理凭据。请恢复存储后确认，再开始新动作。' } }
    return value
  }, (error: unknown) => {
    if (error instanceof ApiError && !unknownActionResult(error.code) && ![401, 403, 409].includes(error.code)) forgetWrite(action.key)
    else pendingNotice(action)
    throw error
  }).finally(() => { action.promise = undefined })
  return action.promise as Promise<T>
}

export const http = {
  get: <T>(url: string, params?: object, options?: RequestOptions) => request<T>({ url, method: 'get', params, ...options }),
  post: <T>(url: string, data?: object, options?: RequestOptions) => request<T>({ url, method: 'post', data, ...options }),
  put: <T>(url: string, data?: object, options?: RequestOptions) => request<T>({ url, method: 'put', data, ...options }),
  delete: <T>(url: string, options?: RequestOptions) => request<T>({ url, method: 'delete', ...options }),
}

/** 上传一个文件：multipart，字段名 file（后端约定）。filename 不传就用文件自己的名字 */
export function upload<T>(url: string, file: Blob, options: { filename?: string; silent?: boolean } = {}): Promise<T> {
  const form = new FormData()
  form.append('file', file, options.filename ?? (file instanceof File ? file.name : 'file'))
  const timeout = Math.max(TRANSFER_TIMEOUT, (file.size / SLOW_BYTES_PER_SECOND) * 1000)
  return request<T>({ url, method: 'post', data: form, timeout, silent: options.silent })
}

/**
 * 下载文件（导出、导入模板）：带着 token 按 blob 取，再交给浏览器保存。
 * 不能用 <a href> 直接打开，那样带不上 token。文件名取响应头 Content-Disposition，取不到用 fallbackName
 */
export async function download(url: string, params?: object, fallbackName = '下载的文件', options?: RequestOptions): Promise<void> {
  const file = await fetchFile(url, params, fallbackName, options)
  saveBlob(file, file.name)
}

/**
 * 只取回文件、不保存：手机上拿去调系统分享（资料包 PDF 发微信，架构 §11.1），分享不了再 saveFile 存下来。
 *   const file = await fetchFile(`/api/tech-packs/${id}/pdf`, params, '工艺单.pdf')
 *   if (navigator.canShare?.({ files: [file] })) await navigator.share({ files: [file] })
 *   else saveFile(file)
 */
export async function fetchFile(url: string, params?: object, fallbackName = '下载的文件', options?: RequestOptions): Promise<File> {
  const prepared = prepareRequest({ url, method: 'get', params, responseType: 'blob', timeout: TRANSFER_TIMEOUT, ...options })
  const response = await transport<Blob>(prepared, sessionEpoch())
  if (!(response.data instanceof Blob)) {
    const message = '服务器没有返回文件，请稍后再试'
    ElMessage.error(message)
    throw new ApiError(-1, message)
  }
  const name = filenameOf(response.headers['content-disposition']) ?? fallbackName
  return new File([response.data], name, { type: response.data.type })
}

/** 把 fetchFile 取回的文件交给浏览器保存 */
export function saveFile(file: File) {
  saveBlob(file, file.name)
}

/**
 * 从 Content-Disposition 里取文件名。中文名在 filename*=UTF-8''%E4%BE%9B... 里（RFC 5987），优先用它；
 * 普通的 filename="..." 是 =?UTF-8?Q?...?= 这种编码过的就不认，让调用方给的名字顶上
 */
function filenameOf(disposition: unknown): string | null {
  if (typeof disposition !== 'string') return null
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1]!.trim().replace(/^"|"$/g, ''))
    } catch {
      // 编码坏了，往下试普通的 filename
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)
  const name = plain?.[1]?.trim()
  return name && !name.startsWith('=?') ? name : null
}

const blobTimers = new Map<string, ReturnType<typeof setTimeout>>()
if (typeof window !== 'undefined') window.addEventListener('pagehide', () => {
  for (const [href, timer] of blobTimers) { clearTimeout(timer); URL.revokeObjectURL(href) }
  blobTimers.clear()
})
function saveBlob(blob: Blob, filename: string) {
  const href = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = href
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 马上回收的话，有的浏览器还没开始存；等一会儿再回收
  blobTimers.set(href, setTimeout(() => { URL.revokeObjectURL(href); blobTimers.delete(href) }, 10_000))
}
