import { http } from '@/utils/request'
import type { UserInfo } from '@/types/system'

export const authApi = {
  login: (username: string, password: string) => http.post<string>('/api/auth/login', { username, password }),
  me: () => http.get<UserInfo>('/api/auth/me'),
  logout: () => http.post<void>('/api/auth/logout'),
  /** 自己改密码：旧密码不对返回 1000，新密码不到 8 位返回 400 */
  changePassword: (data: { oldPassword: string; newPassword: string }) => http.put<void>('/api/auth/password', data),
}
