/** 智能助手（docs/api/08-智能助手.md）。接口在助手服务（Nginx 的 /assistant/），不在 app */

/** GET /assistant/health：服务和它的库好不好、配没配模型 */
export interface AssistantHealth {
  status: 'UP' | 'DOWN'
  db: 'up' | 'down'
  /** none：没配模型，问话会回 503 */
  model: 'configured' | 'none'
}

/** GET /assistant/info：只说模型是外部的还是内网的，不给地址和 Key */
export interface AssistantInfo {
  /** external：外部云端，问题和查到的数据会发出公司；self-hosted：公司内网自部署；none：没配模型 */
  model: { mode: 'external' | 'self-hosted' | 'none' }
  /** 对话留几天：超过这么多天没再问的自动删掉（部署时 ASSISTANT_RETENTION_DAYS 定，默认 7） */
  retentionDays: number
}

/** POST /assistant/chat 的请求体 */
export interface AssistantChatRequest {
  /** 去掉首尾空白后不能空，最多 2000 字 */
  message: string
  /** meta 给过的对话 id，接着问就带上；不带是新对话 */
  conversationId?: string | null
}

/**
 * 问话的 SSE 事件。delta 是模型的原话，只能当纯文本显示；done.text 处理过（只留站内链接），只有它能当 Markdown 渲染。
 * done 和 error 只会到一个
 */
export type AssistantEvent =
  | { event: 'meta'; data: { conversationId: string; created: boolean } }
  /** 开始查（带 label）、查完（ok / failed，rows 是查到几行，不是列表的是 null） */
  | { event: 'tool'; data: { name: string; label?: string; status: 'running' | 'ok' | 'failed'; rows?: number | null } }
  | { event: 'delta'; data: { text: string } }
  | { event: 'done'; data: { messageId: string; text: string; usage: { promptTokens: number; completionTokens: number } } }
  | { event: 'error'; data: { message: string; messageId: string } }

/** GET /assistant/suggestions 的一句：推荐给这个人的问题（按他的权限筛的，手上的待办在前） */
export interface AssistantSuggestion {
  text: string
  /** 哪一类："我的待办"，或者查的是什么（需求订单、采购单……） */
  label: string
  /** 待办的件数；别的是 null */
  count: number | null
}

/** GET /assistant/conversations 的一行 */
export interface AssistantConversation {
  id: string
  /** 第一句话，最多 100 字 */
  title: string
  updatedAt: string
}

/** GET /assistant/conversations/{id} 的一条 */
export interface AssistantMessage {
  id: string
  role: 'user' | 'assistant'
  /** assistant 的处理过、能当 Markdown；user 的是原话，只能当纯文本。没答出来的 assistant 是空串 */
  content: string
  /** 没答出来的原因（模型出错、用户中途停止……） */
  error: string | null
  createdAt: string
}
