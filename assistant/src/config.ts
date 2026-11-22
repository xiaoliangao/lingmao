/**
 * 配置全从环境变量读（部署包的 .env），见 deploy/package/部署手册.md 的智能助手一节。
 * 模型没配（地址或模型名空着）时 llm 是 null：服务照样起，问话回"还没有配置模型"。
 * 读配置是纯函数，测试里直接传一个对象进来
 */

export interface LlmConfig {
  /** OpenAI 兼容接口的地址，到 /v1 为止，比如 https://模型服务/v1；后面拼 /chat/completions */
  baseUrl: string
  apiKey: string
  model: string
  /** external：外部云端模型（数据出公司）；self-hosted：内网自部署。只给开关弹窗显示，不影响调用 */
  mode: 'external' | 'self-hosted'
  /** 流式输出时要不要 stream_options.include_usage（有的接口不认这个参数，回 400 时关掉） */
  streamUsage: boolean
  /** 模型多久没动静算超时（一轮最长是它的 3 倍） */
  timeoutMs: number
}

export interface Limits {
  /** 每人每天最多问几句 */
  userDailyRequests: number
  /** 全系统每天最多用多少 token（输入 + 输出），超了当天不再调模型 */
  dailyTokens: number
  /** 一句话里模型最多来回几轮（每轮可以调几个工具） */
  maxSteps: number
  /** 一轮最多调几个工具 */
  maxToolCallsPerStep: number
  /** 一个工具的结果最多给模型多少个字符 */
  maxResultChars: number
  /** 列表一次最多取几条（覆盖模型给的 size） */
  maxPageSize: number
  /** 带进去的历史：最近几轮问答 */
  historyTurns: number
  /** 全系统同时在答的最多几句（每人同时只能一句）。也限住了全系统 token 额度最多超出多少：先查后记，同时在答的几句可能一起越线 */
  maxConcurrent: number
}

export interface Config {
  port: number
  /** 监听哪个地址：本机开发默认只听 127.0.0.1，容器里（镜像设了）听 0.0.0.0 */
  host: string
  /** app 的地址（容器里是 http://app:8080），助手只用 GET 调它 */
  appBaseUrl: string
  /** 助手自己的库账号，只能动 assistant schema */
  databaseUrl: string
  openapiPath: string
  toolsPath: string
  /** 页面上显示的系统名，写进提示词 */
  systemName: string
  llm: LlmConfig | null
  limits: Limits
}

function int(env: Record<string, string | undefined>, key: string, fallback: number, min = 1): number {
  const raw = env[key]
  if (raw === undefined || raw === '') return fallback
  const n = Number(raw)
  if (!Number.isInteger(n) || n < min) throw new Error(`${key} 要填不小于 ${min} 的整数，现在是 ${raw}`)
  return n
}

export function loadConfig(env: Record<string, string | undefined>): Config {
  const baseUrl = (env.ASSISTANT_LLM_BASE_URL ?? '').trim().replace(/\/+$/, '')
  const model = (env.ASSISTANT_LLM_MODEL ?? '').trim()
  const rawMode = (env.ASSISTANT_LLM_MODE ?? '').trim()
  if (rawMode && rawMode !== 'external' && rawMode !== 'self-hosted') {
    throw new Error(`ASSISTANT_LLM_MODE 只能是 external 或 self-hosted，现在是 ${rawMode}`)
  }
  if (Boolean(baseUrl) !== Boolean(model)) {
    throw new Error('ASSISTANT_LLM_BASE_URL 和 ASSISTANT_LLM_MODEL 要一起填（都不填就是不用模型）')
  }
  let llm: LlmConfig | null = null
  if (baseUrl && model) {
    let url: URL
    try {
      url = new URL(baseUrl)
    } catch {
      throw new Error('ASSISTANT_LLM_BASE_URL 不是合法的地址')
    }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new Error('ASSISTANT_LLM_BASE_URL 要以 http:// 或 https:// 开头')
    const mode = rawMode === 'self-hosted' ? 'self-hosted' : 'external'
    llm = {
      baseUrl,
      apiKey: env.ASSISTANT_LLM_API_KEY ?? '',
      model,
      mode,
      streamUsage: env.ASSISTANT_LLM_STREAM_USAGE !== 'false',
      timeoutMs: int(env, 'ASSISTANT_LLM_TIMEOUT_SECONDS', 90) * 1000,
    }
  }
  const databaseUrl = env.ASSISTANT_DATABASE_URL ?? ''
  if (!databaseUrl) throw new Error('没有 ASSISTANT_DATABASE_URL（助手自己的库账号）')
  return {
    port: int(env, 'ASSISTANT_PORT', 8091),
    host: (env.ASSISTANT_HOST ?? '').trim() || '127.0.0.1',
    appBaseUrl: (env.ASSISTANT_APP_URL ?? 'http://app:8080').replace(/\/+$/, ''),
    databaseUrl,
    openapiPath: env.ASSISTANT_OPENAPI ?? './openapi.json',
    toolsPath: env.ASSISTANT_TOOLS ?? './tools.yaml',
    systemName: env.ASSISTANT_SYSTEM_NAME ?? '服装 SCM', // 和前端 web/src/config.ts 的 APP_NAME 一致
    llm,
    limits: {
      userDailyRequests: int(env, 'ASSISTANT_USER_DAILY_REQUESTS', 200),
      // 实测一轮的固定开销约 8 千 token（36 个工具的说明 + 系统提示），一句话一般两轮，约 2 万；500 万够全公司一天问 250 句左右
      dailyTokens: int(env, 'ASSISTANT_DAILY_TOKENS', 5_000_000),
      maxSteps: int(env, 'ASSISTANT_MAX_STEPS', 6),
      maxToolCallsPerStep: 4,
      maxResultChars: 12_000,
      // 列表一次最多取 20 行，和给模型的行数一致：多取了也是丢
      maxPageSize: 20,
      historyTurns: 6,
      maxConcurrent: int(env, 'ASSISTANT_MAX_CONCURRENT', 6),
    },
  }
}
