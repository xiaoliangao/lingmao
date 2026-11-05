import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { showBizError } from '@/composables/useBizError'
import { LIST_ERROR_CODES } from './errorCodes'
import { clearToken, getToken } from './token'

/** 后端所有接口的统一返回体 */
export interface R<T> {
  code: number
  msg: string
  data: T
}

// 给 axios 的请求配置加一个自己的选项。类型参数要和 axios 自己的声明一模一样，才能合并进去
declare module 'axios' {
  interface AxiosRequestConfig<D = any, P = any> {
    /** true：出错时不弹提示，由调用方自己显示（比如导入对话框把错误清单放在对话框里）。401 照样回登录页 */
    silent?: boolean
  }
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

/** 上传文件的大小上限，和后端 spring.servlet.multipart.max-file-size 一致 */
export const MAX_UPLOAD_SIZE = 10 * 1024 * 1024

/** 上传、下载用的超时：10MB 的文件在慢网上要传几十秒，默认的 15 秒不够 */
const TRANSFER_TIMEOUT = 120_000

const instance = axios.create({
  timeout: 15000,
  // 数组参数发成 status=A&status=B，Spring 用 List 接得住；axios 默认的 status[]=A 它不认
  paramsSerializer: { indexes: null },
})

// 请求前：带上 token
instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
    throw fail(body ?? { code: -1, msg: '服务器返回的内容不对，请稍后再试', data: null }, response)
  },
  async (error: AxiosError) => {
    // HTTP 状态不是 2xx。后端一律回 200，到这里多半是网关：升级期间 Nginx 回的也是 R，照普通错误处理
    const body = error.response ? await readBody(error.response) : null
    if (body && error.response) {
      throw fail(body, error.response)
    }
    let message = '连不上服务器，请检查网络后再试'
    if (error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT) {
      message = '服务器太久没有回应，请稍后再试'
    } else if (error.response?.status === 413) {
      // 超过 Nginx 的上传上限，请求没到后端
      message = `文件太大，最大 ${MAX_UPLOAD_SIZE / 1024 / 1024}MB`
    } else if (error.response) {
      message = `服务器出错了（HTTP ${error.response.status}），请稍后再试`
    }
    if (!error.config?.silent) {
      ElMessage.error(message)
    }
    throw new ApiError(-1, message)
  },
)

/** 后端说出错了：401 回登录页，带清单的弹表格，其他弹一行消息。返回给调用方的 ApiError */
function fail(body: R<unknown>, response: AxiosResponse): ApiError {
  if (body.code === 401) {
    clearToken()
    if (!location.pathname.startsWith('/login')) {
      location.href = `/login?redirect=${encodeURIComponent(location.pathname + location.search)}`
    }
  } else if (!response.config.silent) {
    if (LIST_ERROR_CODES.includes(body.code) && Array.isArray(body.data) && body.data.length > 0) {
      showBizError(body.code, body.msg, body.data)
    } else {
      ElMessage.error(body.msg || '请求失败')
    }
  }
  const traceId = response.headers['x-trace-id']
  return new ApiError(body.code, body.msg, body.data ?? null, typeof traceId === 'string' ? traceId : null)
}

function isJson(response: AxiosResponse) {
  return /json/i.test(String(response.headers['content-type'] ?? ''))
}

/** 从响应里读出 R。下载时 data 是 Blob，要先读成文本；读不出来（比如网关的 HTML 错误页）返回 null */
async function readBody(response: AxiosResponse): Promise<R<unknown> | null> {
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

/** 发请求，直接拿到 R 里的 data */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return instance.request<R<T>>(config).then((response) => response.data.data)
}

export const http = {
  get: <T>(url: string, params?: object) => request<T>({ url, method: 'get', params }),
  post: <T>(url: string, data?: object) => request<T>({ url, method: 'post', data }),
  put: <T>(url: string, data?: object) => request<T>({ url, method: 'put', data }),
  delete: <T>(url: string) => request<T>({ url, method: 'delete' }),
}

/** 上传一个文件：multipart，字段名 file（后端约定）。filename 不传就用文件自己的名字 */
export function upload<T>(url: string, file: Blob, options: { filename?: string; silent?: boolean } = {}): Promise<T> {
  const form = new FormData()
  form.append('file', file, options.filename ?? (file instanceof File ? file.name : 'file'))
  return request<T>({ url, method: 'post', data: form, timeout: TRANSFER_TIMEOUT, silent: options.silent })
}

/**
 * 下载文件（导出、导入模板）：带着 token 按 blob 取，再交给浏览器保存。
 * 不能用 <a href> 直接打开，那样带不上 token。文件名取响应头 Content-Disposition，取不到用 fallbackName
 */
export async function download(url: string, params?: object, fallbackName = '下载的文件'): Promise<void> {
  const response = await instance.request<Blob>({ url, method: 'get', params, responseType: 'blob', timeout: TRANSFER_TIMEOUT })
  if (!(response.data instanceof Blob)) {
    const message = '服务器没有返回文件，请稍后再试'
    ElMessage.error(message)
    throw new ApiError(-1, message)
  }
  saveBlob(response.data, filenameOf(response.headers['content-disposition']) ?? fallbackName)
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

function saveBlob(blob: Blob, filename: string) {
  const href = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = href
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 马上回收的话，有的浏览器还没开始存；等一会儿再回收
  setTimeout(() => URL.revokeObjectURL(href), 10_000)
}
