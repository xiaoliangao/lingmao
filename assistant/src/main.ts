import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { AppClient } from './appClient.ts'
import { loadConfig } from './config.ts'
import { OpenAiCompatible } from './llm.ts'
import { buildServer } from './server.ts'
import { Store } from './store.ts'
import { buildTools, parseWhitelist, type OpenApiDoc } from './tools.ts'

/**
 * 启动顺序：读配置 → 执行助手自己的迁移 → 按白名单从 openapi.json 生成工具（不合规起不来）→ 开端口。
 * 收到 SIGTERM（docker stop）先不接新请求，等正在答的说完（最多 20 秒）再退
 */

const here = dirname(fileURLToPath(import.meta.url))
// 源码（src/）和编译后（dist/）都在包目录下一层，迁移在包目录的 migrations/
const packageDir = join(here, '..')

function log(level: 'INFO' | 'ERROR', msg: string, extra: Record<string, unknown> = {}): void {
  const line = JSON.stringify({ time: new Date().toISOString(), level, msg, ...extra })
  if (level === 'ERROR') console.error(line)
  else console.log(line)
}

async function main(): Promise<void> {
  const config = loadConfig(process.env)
  const store = new Store(config.databaseUrl)
  const applied = await store.migrate(join(packageDir, 'migrations'))
  if (applied.length > 0) log('INFO', 'migrated', { applied })

  const doc = JSON.parse(await readFile(config.openapiPath, 'utf8')) as OpenApiDoc
  const tools = buildTools(doc, parseWhitelist(await readFile(config.toolsPath, 'utf8')))
  const app = new AppClient(config.appBaseUrl)
  const llm = config.llm ? new OpenAiCompatible(config.llm) : null

  const server = buildServer({
    chat: { llm, app, store, tools, limits: config.limits, systemName: config.systemName },
    app,
    store,
    llmConfig: config.llm,
  })
  // 一句话最长：模型几轮 × 每轮超时，再加工具调用；Node 默认 5 分钟的请求超时会把长回答掐掉
  server.requestTimeout = 0
  server.headersTimeout = 30_000
  server.keepAliveTimeout = 65_000
  server.listen(config.port, () => {
    log('INFO', 'started', {
      port: config.port,
      tools: tools.length,
      model: config.llm ? { mode: config.llm.mode, host: new URL(config.llm.baseUrl).hostname, model: config.llm.model } : null,
    })
  })

  let stopping = false
  const stop = (signal: string) => {
    if (stopping) return
    stopping = true
    log('INFO', 'stopping', { signal })
    const force = setTimeout(() => process.exit(0), 20_000)
    force.unref()
    server.close(() => {
      store.close().finally(() => process.exit(0))
    })
    server.closeIdleConnections()
  }
  process.on('SIGTERM', () => stop('SIGTERM'))
  process.on('SIGINT', () => stop('SIGINT'))
}

main().catch((e: unknown) => {
  log('ERROR', 'start failed', { error: e instanceof Error ? e.message : String(e) })
  process.exit(1)
})
