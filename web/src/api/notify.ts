import { http } from '@/utils/request'
import type { PageParams } from '@/api/system'
import type { PageResult } from '@/types/system'
import type { NotifyEvent, NotifyMessageQuery, NotifyMessageRow, NotifySettings } from '@/types/notify'

/** 管理后台 › 消息推送（RFC 019 §4）：只给超级管理员 */
export const notifyApi = {
  settings: () => http.get<NotifySettings>('/api/admin/notify'),
  /** 勾上的种类，空 = 都不推 */
  setEvents: (events: NotifyEvent[]) => http.put<NotifySettings>('/api/admin/notify/events', { events }),
  messages: (params: NotifyMessageQuery & PageParams) => http.get<PageResult<NotifyMessageRow>>('/api/admin/notify/messages', params),
  retry: (id: string) => http.post<void>(`/api/admin/notify/messages/${id}/retry`),
  /** 给自己发一条测试消息，返回"已发到 xx 的钉钉" */
  test: () => http.post<string>('/api/admin/notify/test'),
}
