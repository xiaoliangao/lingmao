import { ApiError, http, request } from '@/utils/request'
import type { UserInfo } from '@/types/system'
import { withAuthSession, type SessionMode } from '@/utils/authSession'
import { sessionChanged, sessionEpoch } from '@/utils/token'

const sessionHeaders = (mode: SessionMode) => ({ 'X-Session-Mode': mode, 'X-Requested-With': 'XMLHttpRequest' })

export const authApi = {
  login: (username: string, password: string, mode: SessionMode) => request<string>({ url: '/api/auth/login', method: 'post', data: { username, password }, headers: sessionHeaders(mode) }),
  me: () => http.get<UserInfo>('/api/auth/me'),
  logout: (mode: SessionMode) => request<void>({ url: '/api/auth/logout', method: 'post', data: {}, withCredentials: true, headers: sessionHeaders(mode) }),
  /** 自己改密码：旧密码不对返回 1000，新密码不到 8 位返回 400 */
  changePassword: (data: { oldPassword: string; newPassword: string }) => {
    const epoch = sessionEpoch()
    const body = { ...data }
    return withAuthSession((mode) => {
      if (sessionChanged() || sessionEpoch() !== epoch) throw new ApiError(409, '登录身份已变化，请重新打开本页')
      return request<void>({ url: '/api/auth/password', method: 'put', data: body, authEpoch: epoch, headers: sessionHeaders(mode) })
    })
  },
}
