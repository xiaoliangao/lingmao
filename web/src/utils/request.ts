import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { clearToken, getToken } from './token'

/** 后端所有接口的统一返回体 */
export interface R<T> {
  code: number
  msg: string
  data: T
}

/** 接口出错。错误消息已经在下面的拦截器里弹过了，调用方不用再提示 */
export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const instance = axios.create({ timeout: 15000 })

// 请求前：带上 token
instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 收到响应后：code 是 0 才算成功；401 跳登录页；其他错误统一弹出 msg
instance.interceptors.response.use(
  (response) => {
    const body = response.data as R<unknown>
    if (body.code === 0) {
      return response
    }
    if (body.code === 401) {
      clearToken()
      if (!location.pathname.startsWith('/login')) {
        location.href = `/login?redirect=${encodeURIComponent(location.pathname + location.search)}`
      }
    } else {
      ElMessage.error(body.msg || '请求失败')
    }
    return Promise.reject(new ApiError(body.code, body.msg))
  },
  (error) => {
    ElMessage.error(`网络错误：${error.message}`)
    return Promise.reject(new ApiError(-1, error.message))
  },
)

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
