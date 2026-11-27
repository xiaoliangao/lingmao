import { ref } from 'vue'
import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { showBizError } from '@/composables/useBizError'
import { LIST_ERROR_CODES } from './errorCodes'
import { clearToken, getToken, sessionChanged, sessionEpoch, sessionIdentity, setToken } from './token'
import { clientActionUuid, unknownActionResult } from './clientAction'
import { currentReadScope } from './requestScope'
import { singleFlight } from './singleFlight'
import { validateKeywords } from './keywords'
import { onSessionChanged } from './token'
import { readWriteReceipts, removeWriteReceipt, requestFingerprint, storeWriteReceipt, type WriteReceipt } from './pendingWrite'
import { formatDateTime } from './format'
import { currentCompanyScope } from './companyScope'
import { canReplayWrite } from './writePolicy'
import { visibleWriteBody } from './fieldAccess'
import { isDismissed, isFormValidationError } from './expectedError'

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
  /** 页面上单据的主体，不用跨页签可变的默认主体。 */
  companyId?: string
  idempotencyKey?: string
  /** 恢复快照要记下核对已有收据之后实际用的编号。 */
  recordWriteKey?: (key: string) => boolean
  /** 明确声明的计算接口（如 BOM 默认值），虽然用 POST 发。 */
  readOnly?: boolean
  /** 内部用：异步查主体之前先记下的登录周期。 */
  authEpoch?: string
}
/**
 * quietTransient：写操作遇到暂时故障先自己重试，拦截器不弹提示，最后由 performWrite 说一句。
 * quietBusy：upload 遇到 429（服务器忙）先自己等着重试，拦截器不弹提示；别的错误照常弹
 */
type RequestConfig = AxiosRequestConfig<unknown> & RequestOptions & { replayed?: boolean; quietTransient?: boolean; quietBusy?: boolean }

function isSilent(config: AxiosRequestConfig<unknown> | undefined): boolean {
  return !!config && 'silent' in config && config.silent === true
}
function isQuietTransient(config: AxiosRequestConfig<unknown> | undefined): boolean {
  return !!config && 'quietTransient' in config && config.quietTransient === true
}
function isQuietBusy(config: AxiosRequestConfig<unknown> | undefined): boolean {
  return !!config && 'quietBusy' in config && config.quietBusy === true
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
    /**
     * 暂时故障：网络断、超时、网关 502/503/504（升级重启时）、同一笔提交还在处理（409 + Retry-After）。
     * 请求可能到了也可能没到服务器，过一会儿用同一个提交编号再发一次是安全的
     */
    public readonly transient = false,
    /** 服务器让等几毫秒再试（Retry-After），没给是 null */
    public readonly retryAfter: number | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 没接住、但属于正常流程的"错误"：接口错误（消息已经弹过）、请求被取消、确认框点了取消或关闭、表单校验没过。
 * 全局 errorHandler 不打红，ModuleBoundary 不把整页换成"暂时无法显示"（见 expectedError.ts）
 */
export function isExpectedError(err: unknown): boolean {
  return err instanceof ApiError || axios.isCancel(err) || isDismissed(err) || isFormValidationError(err)
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
/** 服务端 Cookie 刷新接好之后才打开。 */
export function configureSessionRefresh(handler: (() => Promise<string>) | undefined) { refreshHandler = handler }
function assertRefreshSession(epoch: string) {
  if (sessionChanged() || sessionEpoch() !== epoch) throw new ApiError(409, '登录身份已变化，请重新打开本页')
}
const refreshOnce = singleFlight(async (failedToken: string, epoch: string) => {
  const rotate = async () => {
    // 等锁的时候，别的页签可能已经轮换完了。
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
  // 浏览器没有 Web Locks 时，靠服务端有限的后继重放窗口兜住重叠。
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
  // 登录相关的接口 401 就是没登录，不刷新；换助手令牌除外：登录令牌过期了照样先用 Cookie 刷新再换（它没有副作用，能重放）
  if (code !== 401 || (config.url?.startsWith('/api/auth/') && config.url !== '/api/auth/assistant-token')) return null
  const epoch = config.authEpoch ?? sessionEpoch()
  assertRefreshSession(epoch)
  if (!refreshHandler || !getToken() || config.replayed) return null
  const method = (config.method ?? 'get').toLowerCase()
  if (!['get', 'head'].includes(method) && !config.readOnly && (!config.headers['Idempotency-Key'] || !canReplayWrite(method, config.url ?? ''))) return null
  config.replayed = true
  const sentToken = String(config.headers.Authorization ?? '').replace(/^Bearer /, '')
  // 旧 access token 迟到的 401 不能再轮换一次刚发的 Cookie。
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
    const transient = !error.response || [502, 503, 504].includes(error.response.status)
    if (!isSilent(error.config) && !(transient && isQuietTransient(error.config))) {
      ElMessage.error(message)
    }
    throw new ApiError(-1, message, null, null, transient)
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
  const seconds = Number(response.headers['retry-after'])
  const retryAfter = Number.isFinite(seconds) && seconds >= 0 ? Math.min(seconds, 30) * 1000 : null
  const transient = [502, 503, 504].includes(body.code) || (body.code === 409 && retryAfter !== null)
  if (body.code === 401) {
    // 同时失败的请求共用一次退出和跳转。每个响应都改一次 location，
    // 会打断跳登录页，过期的页面可能一直留着。
    if (!redirectingToLogin) {
      clearToken()
      if (!location.pathname.startsWith('/login')) {
        redirectingToLogin = true
        location.replace(`/login?redirect=${encodeURIComponent(location.pathname + location.search)}`)
      }
    }
  } else if (!isSilent(response.config) && !(transient && isQuietTransient(response.config)) && !(body.code === 429 && isQuietBusy(response.config))) {
    if (LIST_ERROR_CODES.includes(body.code) && Array.isArray(body.data) && body.data.length > 0) {
      showBizError(body.code, body.msg, body.data)
    } else {
      const trace = response.headers['x-request-id'] ?? response.headers['x-trace-id']
      const msg = body.msg || '请求失败'
      // 500 的消息里已经有"错误编号 <同一个编号>"（TraceIdFilter.serverErrorMsg），再拼一遍追踪码，看着像两个不同的编号
      ElMessage.error(`${msg}${typeof trace === 'string' && !msg.includes(trace) ? `（追踪码 ${trace}）` : ''}`)
    }
  }
  const traceId = response.headers['x-request-id'] ?? response.headers['x-trace-id']
  return new ApiError(body.code, body.msg, body.data ?? null, typeof traceId === 'string' ? traceId : null, transient, retryAfter)
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

/** 只补齐结构上的默认值；金额、成本块是 null 时保留它的意思。 */
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
/*
 * 提交结果不确定时怎么办（docs/api/README §3.6）。
 * 建单这类服务器支持按提交编号防重的写操作（writePolicy.canReplayWrite）：
 *   1. 发之前把提交编号记进浏览器（只记编号、内容摘要、地址，不记表单内容），带上 Idempotency-Key；
 *   2. 网络断、网关 502/503/504、"还在处理"时，用同一个编号自动再发两次（服务器认得编号，不会建两张）；
 *   3. 还不确定就告诉用户"系统会自动核对"，到时间去问 GET /api/http-actions/{编号}：成功了、没保存都弹一条通知，收据删掉；
 *   4. 同一个地方内容没改再点保存 = 用原编号重发；改了内容再保存，先核对上一次：上一次其实成功了就拦下，没保存就照常提交。
 * 改单、删除、审核这类操作不记收据：服务器有版本号和状态检查，重复提交会被拦，结果不确定时请用户刷新看一下。
 */
/** 暂时故障后自动重发的间隔 */
const RETRY_DELAYS = [1000, 3000]
/**
 * 每次请求超时 15 秒、间隔 1 秒和 3 秒，第三次最晚在第 34 秒发出；服务器一次提交最长 30 秒（事务超时）。
 * 所以过了一分半还查不到成功记录，就是没保存。
 */
const SETTLE_AFTER = 90_000
/** 收据最多留一天 */
const RECEIPT_TTL = 24 * 60 * 60_000
interface PendingWrite extends WriteReceipt {
  promise?: Promise<unknown>
  /** 收据存进了浏览器（存不进去时只在这一页的内存里） */
  persisted?: boolean
}
export interface PendingWriteNotice {
  key: string
  title: string
  createdAt: number
  checking: boolean
}
/** 还没核对出结果的提交，右下角显示"正在核对" */
export const pendingWriteNotices = ref<PendingWriteNotice[]>([])
const pendingWrites = new Map<string, PendingWrite>()
const checking = new Set<string>()
/** 改单、审核这类操作连点两下：内容一样就合并成一次 */
const inflight = new Map<string, Promise<unknown>>()
const RESOURCE_NAMES: Record<string, string> = { orders: '需求订单', 'order-changes': '订单变更', 'purchase-orders': '采购单', 'mrp-calcs': '算料单', 'inventory-docs': '出入库单', 'inventory-counts': '盘点单', 'production-orders': '生产单', materials: '物料', suppliers: '供应商', styles: '款式', 'tech-packs': '资料包', 'export-jobs': '导出任务' }
const CHILD_NAMES: [RegExp, string][] = [
  [/\/skus(\/generate)?$/, 'SKU'], [/\/certs$/, '供应商证照'], [/\/revise$/, '资料包改版'],
  [/\/reports$/, '报工'], [/\/issues$/, '生产问题'], [/\/receipts$/, '成品入库'], [/\/from-(purchase|production)\/\d+$/, '入库单'],
  [/\/schedule$/, '排产'], [/\/from-(mrp|reorder)$/, '采购单'],
]
function writeTitle(url: string): string {
  const path = url.split('?')[0] ?? ''
  return CHILD_NAMES.find(([pattern]) => pattern.test(path))?.[1] ?? RESOURCE_NAMES[path.split('/')[2] ?? ''] ?? '提交'
}
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))
/** 结果不确定：请求可能已经在服务器上做完了 */
function uncertain(error: unknown): boolean {
  return !(error instanceof ApiError) || error.transient || unknownActionResult(error.code)
}
function syncNotices() {
  const identity = sessionIdentity()
  pendingWriteNotices.value = [...pendingWrites.values()]
    .filter((action) => action.identity === identity && !action.promise)
    .sort((a, b) => a.createdAt - b.createdAt)
    .map((action) => ({ key: action.key, title: writeTitle(action.url), createdAt: action.createdAt, checking: checking.has(action.key) }))
}
/** 把别的标签页、刷新前留下的收据读进来；超过一天的直接删 */
export function restorePendingWrites() {
  const identity = sessionIdentity()
  const stored = new Set<string>()
  for (const receipt of readWriteReceipts()) {
    if (Date.now() - receipt.createdAt > RECEIPT_TTL) { removeWriteReceipt(receipt.key); continue }
    stored.add(receipt.key)
    if (receipt.identity !== identity || pendingWrites.has(receipt.key)) continue
    pendingWrites.set(receipt.key, { ...receipt, persisted: true })
  }
  // 别的标签页已经核对掉的，这里也不再管
  for (const [key, action] of pendingWrites) if (action.persisted && !action.promise && !checking.has(key) && !stored.has(key)) pendingWrites.delete(key)
  syncNotices()
}
function forgetWrite(key: string) {
  removeWriteReceipt(key)
  pendingWrites.delete(key)
  syncNotices()
}
type Settled = 'done' | 'not-done' | 'unknown'
/** 问服务器这笔提交成功了没有。wait：还没到 SETTLE_AFTER、又查不到时，等到那时再问一次 */
async function settle(action: PendingWrite, wait = false): Promise<Settled> {
  const ask = () => request<{ done: boolean }>({ url: `/api/http-actions/${action.key}`, method: 'get', silent: true, signal: new AbortController().signal })
    .then((state) => state.done, () => null)
  let done = await ask()
  const left = action.createdAt + SETTLE_AFTER - Date.now()
  if (done === false && wait && left > 0) {
    await sleep(left)
    done = await ask()
  }
  if (done === true) return 'done'
  return done === false && Date.now() - action.createdAt >= SETTLE_AFTER ? 'not-done' : 'unknown'
}
let resolveTimer: ReturnType<typeof setTimeout> | undefined
function scheduleResolve(delay: number) {
  clearTimeout(resolveTimer)
  resolveTimer = setTimeout(() => { void resolvePendingWrites() }, Math.min(Math.max(delay, 5000), SETTLE_AFTER + 2000))
}
/** 核对所有还没结果的提交（App 打开时、提交结果不确定一分半以后）。查出结果就弹通知、删收据；查不出过一会儿再来 */
export async function resolvePendingWrites() {
  restorePendingWrites()
  const identity = sessionIdentity()
  let next = Infinity
  for (const action of [...pendingWrites.values()]) {
    if (action.identity !== identity || action.promise || checking.has(action.key)) continue
    checking.add(action.key)
    syncNotices()
    const outcome = await settle(action)
    checking.delete(action.key)
    const when = formatDateTime(action.createdAt)
    if (outcome === 'done') {
      forgetWrite(action.key)
      ElNotification({ type: 'success', title: '提交已经保存了', message: `${when} 网络不稳时提交的${writeTitle(action.url)}已经保存成功，列表里能看到。` })
    } else if (outcome === 'not-done') {
      forgetWrite(action.key)
      ElNotification({ type: 'warning', title: '有一笔提交没有保存', message: `${when} 提交的${writeTitle(action.url)}因为网络中断没有保存，需要的话请重新提交。`, duration: 0 })
    } else {
      // 还没到时间就到时间再查；核对接口不通时隔半分钟再试，不一直问
      next = Math.min(next, Math.max(action.createdAt + SETTLE_AFTER + 2000 - Date.now(), 30_000))
    }
  }
  syncNotices()
  if (Number.isFinite(next)) scheduleResolve(next)
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
  // 只有这几种草稿保存允许明确换主体，别的接口主体必须一致。
  const draftUpdate = (config.method ?? '').toLowerCase() === 'put' && /^\/api\/(orders|purchase-orders|production-orders)\/[1-9]\d*$/.test(config.url ?? '')
  const expectedCompany = draftUpdate && payload && typeof payload === 'object' && 'expectedCompanyId' in payload && typeof payload.expectedCompanyId === 'string' ? payload.expectedCompanyId : undefined
  if (expectedCompany && pageCompany && expectedCompany !== pageCompany) throw new ApiError(409, '已保存主体与页面装载时不一致，请重新核对主体')
  if (!expectedCompany && bodyCompany && pageCompany && bodyCompany !== pageCompany) throw new ApiError(409, '表单主体与当前单据不一致，请重新核对主体')
  const company = expectedCompany ?? pageCompany ?? bodyCompany ?? paramsCompany
  const headers = { ...config.headers, ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}), ...(company ? { 'X-Company-Id': company } : {}) }
  return { ...config, authEpoch: epoch, companyId: company, headers, signal: config.signal ?? (readOnly ? currentReadScope()?.signal : undefined) }
}
/** 接口函数等前置请求之前，先把 JSON 请求体冻结下来；支持 Vue 代理对象。 */
export function snapshotRequestBody<T>(body: T): T {
  return body === undefined ? body : JSON.parse(JSON.stringify(body)) as T
}
/** JSON、上传、下载共用同一套闸门和取消控制。 */
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
  if (!config.readOnly && !['get', 'head'].includes((config.method ?? 'get').toLowerCase()) && !(config.data instanceof FormData)) {
    config = { ...config, data: visibleWriteBody(config.url ?? '', config.data) }
  }
  let prepared: RequestConfig
  try {
    validateKeywords((config.params as { keyword?: unknown } | undefined)?.keyword)
    prepared = prepareRequest(config)
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
/** 内容摘要不算 clientActionId：重发时它要换成原来的编号 */
function fingerprintBody(data: unknown): string {
  if (!data || typeof data !== 'object' || Array.isArray(data) || !('clientActionId' in data)) return JSON.stringify(data ?? null)
  const { clientActionId: _ignored, ...rest } = data as Record<string, unknown>
  return JSON.stringify(rest)
}
async function performWrite<T>(config: RequestConfig, prepared: RequestConfig, send: () => Promise<T>, method: string, epoch: string): Promise<T> {
  const clientFailure = (code: number, message: string) => {
    if (!config.silent) ElMessage.error(message)
    return new ApiError(code, message)
  }
  const identity = sessionIdentity()
  const url = config.url ?? ''
  const title = writeTitle(url)
  const scope = `${identity}|${location.pathname}|${method}|${url.split('?')[0]}`
  prepared.data = config.data === undefined ? undefined : JSON.parse(JSON.stringify(config.data))
  const payload = prepared.data as Record<string, unknown> | undefined
  let fingerprint: string
  try { fingerprint = await requestFingerprint(fingerprintBody(payload), prepared.companyId, url) } catch {
    throw clientFailure(409, '无法生成提交编号，这次还没有发出去，请再点一次')
  }
  assertSession(config.url)
  if (epoch !== sessionEpoch()) throw new ApiError(409, '登录身份已变化，请重新打开本页')
  prepared.quietTransient = true

  if (!canReplayWrite(method, url)) {
    const twinKey = `${scope}|${fingerprint}`
    const twin = inflight.get(twinKey)
    if (twin) return twin as Promise<T>
    prepared.headers = { ...prepared.headers, 'Idempotency-Key': config.idempotencyKey ?? (typeof payload?.clientActionId === 'string' ? payload.clientActionId : clientActionUuid()) }
    const promise = send().catch((error: unknown) => {
      if (error instanceof ApiError && error.transient && !config.silent) {
        ElMessage.warning({ message: `${error.message}。刚才的操作不确定有没有成功，请刷新页面看一下结果再操作。`, duration: 8000 })
      }
      throw error
    }).finally(() => inflight.delete(twinKey))
    inflight.set(twinKey, promise)
    return promise
  }

  restorePendingWrites()
  const matches = [...pendingWrites.values()].filter((item) => item.scope === scope)
  let action = matches.find((item) => item.fingerprint === fingerprint)
  if (action?.promise) {
    if (config.recordWriteKey && !config.recordWriteKey(action.key)) throw clientFailure(409, '浏览器存不下提交编号，请允许网站存储后再试')
    return action.promise as Promise<T>
  }
  // 同一个地方上一次的提交结果还不确定、这次内容又改了：先核对上一次，免得建出两张
  for (const previous of matches.filter((item) => item !== action && !item.promise)) {
    if (!config.silent && Date.now() - previous.createdAt < SETTLE_AFTER) ElMessage.info('正在核对上一次提交的结果，最多等一分半钟…')
    const outcome = await settle(previous, true)
    if (outcome === 'unknown') throw clientFailure(-1, `上一次的${title}还没核对出结果（网络不通），这次先不提交，请稍后再试`)
    forgetWrite(previous.key)
    if (outcome === 'done') {
      if (!config.silent) void ElMessageBox.alert(`上一次的${title}其实已经保存成功了，这次的修改没有提交，免得重复。请到列表里打开它再改。`, '上一次已经保存', { confirmButtonText: '知道了' }).catch(() => undefined)
      throw new ApiError(1002, `上一次的${title}已经保存成功，这次没有重复提交`)
    }
  }
  if (!action) {
    const explicit = config.idempotencyKey ?? (typeof payload?.clientActionId === 'string' ? payload.clientActionId : undefined)
    action = { scope, identity, key: explicit ?? clientActionUuid(), fingerprint, url, companyId: prepared.companyId, createdAt: Date.now() }
  }
  const current = action
  if (payload && typeof payload === 'object' && 'clientActionId' in payload) payload.clientActionId = current.key
  if (config.recordWriteKey && !config.recordWriteKey(current.key)) throw clientFailure(409, '浏览器存不下提交编号，这次还没有发出去，请允许网站存储后再试')
  // 存不进浏览器（无痕模式、存储满）也照常提交：只是刷新以后没法自动核对
  current.persisted = storeWriteReceipt({ scope, identity, key: current.key, fingerprint, url, companyId: current.companyId, createdAt: current.createdAt })
  prepared.headers = { ...prepared.headers, 'Idempotency-Key': current.key }
  pendingWrites.set(current.key, current)
  const attempt = async (): Promise<T> => {
    let last: unknown
    for (let tries = 0; tries <= RETRY_DELAYS.length; tries++) {
      if (tries > 0) {
        await sleep(last instanceof ApiError && last.retryAfter !== null ? last.retryAfter : RETRY_DELAYS[tries - 1]!)
        if (sessionChanged() || epoch !== sessionEpoch()) break
      }
      try {
        const value = await send()
        forgetWrite(current.key)
        return value
      } catch (error) {
        last = error
        if (!uncertain(error)) { forgetWrite(current.key); throw error }
        // 应用自己报的 500 不是网络问题，再发也一样；取消的请求不替用户重发
        if (!(error instanceof ApiError && error.transient)) break
      }
    }
    if (!config.silent && last instanceof ApiError && last.transient) {
      const reason = last.code === 409 ? `服务器还在处理这次${title}` : `网络不稳定，还没确认这次${title}有没有保存成功`
      ElMessage.warning({ message: `${reason}。系统一两分钟内会自动核对并提醒你；也可以稍后再点一次，不会重复保存。`, duration: 10_000 })
    }
    scheduleResolve(current.createdAt + SETTLE_AFTER + 2000 - Date.now())
    throw last
  }
  current.promise = attempt().finally(() => { current.promise = undefined; syncNotices() })
  return current.promise as Promise<T>
}

export const http = {
  get: <T>(url: string, params?: object, options?: RequestOptions) => request<T>({ url, method: 'get', params, ...options }),
  post: <T>(url: string, data?: object, options?: RequestOptions) => request<T>({ url, method: 'post', data, ...options }),
  put: <T>(url: string, data?: object, options?: RequestOptions) => request<T>({ url, method: 'put', data, ...options }),
  delete: <T>(url: string, options?: RequestOptions) => request<T>({ url, method: 'delete', ...options }),
}

/**
 * 服务器同时只处理两个大文件（上传、导入、导出，后端 HeavyIoFilter），多的当场回 429 + Retry-After。
 * 429 时文件还没被收下，原样重发是安全的：等它说的秒数再试，最多 BUSY_RETRIES 次（约 15 秒），中间不提示，最后一次还忙才报错。
 * 别的错误（格式不对、太大）第一次就照常弹、照常抛
 */
const BUSY_RETRIES = 5

/** 上传一个文件：multipart，字段名 file（后端约定）。filename 不传就用文件自己的名字 */
export async function upload<T>(
  url: string,
  file: Blob,
  options: { filename?: string; silent?: boolean; fields?: Record<string, string> } = {},
): Promise<T> {
  const form = new FormData()
  form.append('file', file, options.filename ?? (file instanceof File ? file.name : 'file'))
  // 别的表单字段（账号导入的初始密码）：放在请求体里，不拼进地址
  Object.entries(options.fields ?? {}).forEach(([key, value]) => form.append(key, value))
  const timeout = Math.max(TRANSFER_TIMEOUT, (file.size / SLOW_BYTES_PER_SECOND) * 1000)
  for (let tries = 0; ; tries++) {
    const last = tries >= BUSY_RETRIES
    try {
      return await request<T>({ url, method: 'post', data: form, timeout, silent: options.silent, quietBusy: !last })
    } catch (error) {
      if (last || !(error instanceof ApiError && error.code === 429)) throw error
      await sleep(error.retryAfter ?? 3000)
    }
  }
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
 * 只取回文件、不保存：手机上拿去调系统分享（工艺单发微信），分享不了再 saveFile 存下来。
 *   const file = await fetchFile(`/api/inventory-docs/${id}/export`, params, '单据.xlsx')
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
