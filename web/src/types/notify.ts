// 管理后台 › 消息推送（RFC 019 §4，docs/api/06-首页.md §2.7），和后端 module/system/dto/Notify* 一一对应

/** 推到钉钉的消息种类 */
export type NotifyEvent = 'DOC_REJECTED' | 'DUE_WARNING'

export interface NotifyEventOption {
  value: NotifyEvent
  label: string
  description: string
  enabled: boolean
}

/** GET /api/admin/notify */
export interface NotifySettings {
  /** 部署配置里钉钉的 AppKey、AppSecret、AgentId 都有了 */
  configured: boolean
  /** 配了系统的外网地址（SCM_PUBLIC_URL）：消息里才带"打开"按钮 */
  linkConfigured: boolean
  events: NotifyEventOption[]
  /** 发失败、不再重试的条数 */
  failedCount: number
}

/** 发件箱里一条消息的状态 */
export type NotifyMessageStatus = 'PENDING' | 'PROCESSING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'DEAD_LETTER' | 'CANCELLED' | 'EXPIRED'

export const NOTIFY_MESSAGE_STATUS: Record<NotifyMessageStatus, { label: string; tone: 'pending' | 'progress' | 'done' | 'danger' | 'off' }> = {
  PENDING: { label: '等着发', tone: 'pending' },
  PROCESSING: { label: '正在发', tone: 'progress' },
  RETRY_WAIT: { label: '等重试', tone: 'pending' },
  SUCCEEDED: { label: '已发出', tone: 'done' },
  DEAD_LETTER: { label: '没发出去', tone: 'danger' },
  CANCELLED: { label: '取消了', tone: 'off' },
  EXPIRED: { label: '过期了', tone: 'off' },
}

/** 最近一次失败的原因 */
export const NOTIFY_ERROR: Record<string, string> = {
  NOT_CONFIGURED: '钉钉没配置',
  NO_RECIPIENT: '找不到收件人：没手机号、钉钉里没这个人或账号停用了',
  REMOTE_REJECTED: '钉钉拒收：多半是权限没开或不在应用的可见范围，错误码在应用日志里',
  TEMPORARY_FAILURE: '网络问题，会自己重试',
  LEASE_EXPIRED: '发送中断，会自己重试',
  SHUTDOWN: '应用重启，会自己重试',
}

export interface NotifyMessageRow {
  id: string
  event: NotifyEvent
  eventLabel: string
  recipientId: string | null
  recipientName: string | null
  title: string
  status: NotifyMessageStatus
  errorCode: string | null
  attempts: number
  createdAt: string
  updatedAt: string
}

export interface NotifyMessageQuery {
  /** 只看发失败的 */
  failed?: boolean
}
