import { http } from '@/utils/request'
import type { UserInfo } from '@/types/system'

export const authApi = {
  login: (username: string, password: string) => http.post<string>('/api/auth/login', { username, password }),
  me: () => http.get<UserInfo>('/api/auth/me'),
  logout: () => http.post<void>('/api/auth/logout'),
}
