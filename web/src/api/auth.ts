import { http, request, type RequestOptions } from '@/utils/request'
import type { AssistantToken, UserInfo } from '@/types/system'

export const authApi = {
  login: (username: string, password: string) => http.post<string>('/api/auth/login', { username, password }),
  me: () => http.get<UserInfo>('/api/auth/me'),
  logout: () => request<void>({ url: '/api/auth/logout', method: 'post', data: {}, withCredentials: true, headers: { 'X-Requested-With': 'XMLHttpRequest' } }),
  /** 换一张给智能助手用的只读令牌（要 assistant:chat:use；AI 功能关着回 403）。助手卡片传 silent，出错自己显示 */
  assistantToken: (options?: RequestOptions) => http.post<AssistantToken>('/api/auth/assistant-token', {}, { ...options, readOnly: true }),
  /** 自己改密码：旧密码不对返回 1000，新密码不到 8 位返回 400 */
  changePassword: (data: { oldPassword: string; newPassword: string }) => http.put<void>('/api/auth/password', data),
}
