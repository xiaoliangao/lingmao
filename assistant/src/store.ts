import { createHash } from 'node:crypto'
import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'
import pg from 'pg'

/**
 * 助手自己的表，在同一个 PostgreSQL 的 assistant schema（RFC 017 §3.4、RFC 009 §3.5），用单独的库账号：
 * 碰不到 app 的表，app 也不读这些。每天的 pg_dump 照样带上。
 * 迁移是 migrations/V<n>__*.sql，启动时按顺序执行，和 app 的 Flyway 一样只加不改：
 * 已经执行过的文件内容变了（校验和对不上）就拒绝启动。
 * 限额也记在这里（ast_usage），不放 Redis：助手碰不到 app 的限流状态，计数靠一条带条件的 upsert 就够了
 */

export interface StoredMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface ToolCallRecord {
  tool: string
  args: Record<string, unknown>
  status: number | null
  ok: boolean
  rows: number | null
  durationMs: number
}

export interface ConversationRow {
  id: string
  title: string
  updatedAt: string
}

/** 东八区的"今天"，限额按它算 */
const TODAY = `(now() AT TIME ZONE 'Asia/Shanghai')::date`

export class Store {
  readonly pool: pg.Pool

  constructor(databaseUrl: string) {
    // 连接池单独开、上限设小（RFC 017 §3.4）：甲方服务器 2 核 4G，库连接是 app 的
    // 密码不写进连接串时，pg 从环境变量 PGPASSWORD 取（部署包这样传）
    this.pool = new pg.Pool({ connectionString: databaseUrl, max: 3, idleTimeoutMillis: 30_000, connectionTimeoutMillis: 5_000, statement_timeout: 10_000 })
    // 空闲连接被数据库断开（重启、备份时被踢）会在池上报 error：不监听的话整个进程退出。记一笔，下次用时池会重新连
    this.pool.on('error', (e) => console.error(JSON.stringify({ time: new Date().toISOString(), level: 'ERROR', msg: 'db idle connection lost', error: e.message })))
  }

  /** 健康检查用：库连得上 */
  async ping(): Promise<void> {
    await this.pool.query({ text: 'SELECT 1', query_timeout: 2_000 } as pg.QueryConfig)
  }

  async close(): Promise<void> {
    await this.pool.end()
  }

  /** 执行还没执行过的迁移；同时只有一个实例在迁移（advisory lock） */
  async migrate(dir: string): Promise<string[]> {
    const files = (await readdir(dir)).filter((f) => f.endsWith('.sql'))
    for (const f of files) {
      if (!/^V\d+__\w+\.sql$/.test(f)) throw new Error(`迁移文件名 ${f} 不对：要写成 V<数字>__<字母数字下划线>.sql`)
    }
    files.sort((a, b) => version(a) - version(b))
    for (let i = 1; i < files.length; i++) {
      if (version(files[i]!) === version(files[i - 1]!)) throw new Error(`两个迁移的版本号一样：${files[i - 1]}、${files[i]}`)
    }
    const client = await this.pool.connect()
    // 借出去的连接断了（数据库重启、被踢），pool 的 error 监听管不到，不挂一个进程会崩（审查 R-01）。这条用完就扔，监听不用摘
    client.on('error', (e) => dbLost('migrate', e))
    const applied: string[] = []
    let broken = false
    try {
      // 等别的实例迁移完、执行慢的 DDL，都不受连接池 10 秒的语句超时管；这条连接用完就扔，不放回池里
      await client.query('SET statement_timeout = 0')
      await client.query(`SELECT pg_advisory_lock(hashtext('scm-assistant-migrate'))`)
      await client.query(`
        CREATE TABLE IF NOT EXISTS assistant.ast_schema_version (
          version INT PRIMARY KEY,
          name VARCHAR(200) NOT NULL,
          checksum CHAR(64) NOT NULL,
          applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )`)
      const done = new Map<number, string>()
      for (const row of (await client.query<{ version: number; checksum: string }>('SELECT version, checksum FROM assistant.ast_schema_version')).rows) {
        done.set(row.version, row.checksum)
      }
      for (const file of files) {
        const sql = await readFile(join(dir, file), 'utf8')
        const sum = createHash('sha256').update(sql).digest('hex')
        const v = version(file)
        const old = done.get(v)
        if (old !== undefined) {
          if (old !== sum) throw new Error(`迁移 ${file} 执行过以后又被改了（校验和对不上）。已执行的迁移只加不改`)
          continue
        }
        await client.query('BEGIN')
        try {
          await client.query(sql)
          await client.query('INSERT INTO assistant.ast_schema_version(version, name, checksum) VALUES ($1, $2, $3)', [v, file, sum])
          await client.query('COMMIT')
        } catch (e) {
          // 回滚本身再出错（连接断了）不盖住原来的原因
          await client.query('ROLLBACK').catch(() => undefined)
          throw new Error(`迁移 ${file} 执行失败：${(e as Error).message}`)
        }
        applied.push(file)
      }
    } catch (e) {
      broken = true
      throw e
    } finally {
      if (!broken) await client.query(`SELECT pg_advisory_unlock(hashtext('scm-assistant-migrate'))`).catch(() => undefined)
      // 销毁这条连接：会话级的锁、statement_timeout 跟着连接一起没了，不会带回池里
      client.release(true)
    }
    return applied
  }

  /**
   * 记一次提问，没超每人每天的次数才算数：超了返回 false，什么都不记。
   * 一条 upsert 带条件，两个请求同时来也不会多算
   */
  async takeRequest(userId: string, limit: number): Promise<boolean> {
    const r = await this.pool.query(
      `INSERT INTO assistant.ast_usage(user_id, day, requests) VALUES ($1, ${TODAY}, 1)
       ON CONFLICT (user_id, day) DO UPDATE SET requests = assistant.ast_usage.requests + 1
       WHERE assistant.ast_usage.requests < $2
       RETURNING requests`,
      [userId, limit],
    )
    return r.rowCount === 1
  }

  /** 记到用掉的那天：23:59 问、过了零点才答完的，记在新的一天（那天还没有这个人的行就新建一行，次数记 0） */
  async addTokens(userId: string, promptTokens: number, completionTokens: number): Promise<void> {
    await this.pool.query(
      `INSERT INTO assistant.ast_usage(user_id, day, requests, prompt_tokens, completion_tokens) VALUES ($1, ${TODAY}, 0, $2, $3)
       ON CONFLICT (user_id, day) DO UPDATE SET prompt_tokens = assistant.ast_usage.prompt_tokens + EXCLUDED.prompt_tokens,
         completion_tokens = assistant.ast_usage.completion_tokens + EXCLUDED.completion_tokens`,
      [userId, promptTokens, completionTokens],
    )
  }

  /** 今天全系统用了多少 token */
  async tokensToday(): Promise<number> {
    const r = await this.pool.query<{ total: string }>(
      `SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0)::text AS total FROM assistant.ast_usage WHERE day = ${TODAY}`,
    )
    return Number(r.rows[0]?.total ?? 0)
  }

  async createConversation(id: string, userId: string, title: string): Promise<void> {
    await this.pool.query('INSERT INTO assistant.ast_conversation(id, user_id, title) VALUES ($1, $2, $3)', [id, userId, title.slice(0, 100)])
  }

  /** 是这个人的对话才返回 true */
  async ownsConversation(id: string, userId: string): Promise<boolean> {
    const r = await this.pool.query('SELECT 1 FROM assistant.ast_conversation WHERE id = $1 AND user_id = $2', [id, userId])
    return r.rowCount === 1
  }

  /** 删掉这个人的一个对话，问答和工具记录跟着删（外键 ON DELETE CASCADE）。不是他的、不存在的返回 false */
  async deleteConversation(id: string, userId: string): Promise<boolean> {
    const r = await this.pool.query('DELETE FROM assistant.ast_conversation WHERE id = $1 AND user_id = $2', [id, userId])
    return r.rowCount === 1
  }

  /**
   * 删掉超过 days 天没再问过的对话（按最后一次问答的时间 updated_at），返回删了几个。
   * 助手只是问数、问进度，对话不用长留（用户 11-22 定）；用量表 ast_usage 不删，限额要看
   */
  async purgeConversations(days: number): Promise<number> {
    const r = await this.pool.query('DELETE FROM assistant.ast_conversation WHERE updated_at < now() - make_interval(days => $1)', [days])
    return r.rowCount ?? 0
  }

  async listConversations(userId: string, limit: number): Promise<ConversationRow[]> {
    const r = await this.pool.query<{ id: string; title: string; updated_at: Date }>(
      'SELECT id, title, updated_at FROM assistant.ast_conversation WHERE user_id = $1 ORDER BY updated_at DESC LIMIT $2',
      [userId, limit],
    )
    return r.rows.map((x) => ({ id: x.id, title: x.title, updatedAt: x.updated_at.toISOString() }))
  }

  /**
   * 最近 turns 轮问答，旧的在前，一问一答成对：没答出来的那一问（出错、中途停止）连同它的回答都不带。
   * 有的模型要求 user / assistant 严格交替，留下一条落单的问题，这个对话以后就一直报 400
   */
  async history(conversationId: string, turns: number): Promise<StoredMessage[]> {
    const r = await this.pool.query<{ role: 'user' | 'assistant'; content: string; error: string | null }>(
      `SELECT role, content, error FROM (
         SELECT id, role, content, error FROM assistant.ast_message WHERE conversation_id = $1 ORDER BY id DESC LIMIT $2
       ) t ORDER BY id`,
      [conversationId, turns * 4],
    )
    return pairUp(r.rows, turns)
  }

  async messages(conversationId: string): Promise<{ id: string; role: string; content: string; error: string | null; createdAt: string }[]> {
    const r = await this.pool.query<{ id: string; role: string; content: string; error: string | null; created_at: Date }>(
      'SELECT id::text, role, content, error, created_at FROM assistant.ast_message WHERE conversation_id = $1 ORDER BY id',
      [conversationId],
    )
    return r.rows.map((x) => ({ id: x.id, role: x.role, content: x.content, error: x.error, createdAt: x.created_at.toISOString() }))
  }

  async addUserMessage(conversationId: string, content: string): Promise<void> {
    await this.pool.query(`INSERT INTO assistant.ast_message(conversation_id, role, content) VALUES ($1, 'user', $2)`, [conversationId, content])
    await this.pool.query('UPDATE assistant.ast_conversation SET updated_at = now() WHERE id = $1', [conversationId])
  }

  /** 记回答和这次调过的工具（出错时 content 空、error 写原因，调过的工具照样记） */
  async addAnswer(conversationId: string, content: string, error: string | null, promptTokens: number, completionTokens: number,
                  calls: ToolCallRecord[]): Promise<string> {
    const client = await this.pool.connect()
    let lost = false
    const onError = (e: Error) => {
      lost = true
      dbLost('addAnswer', e)
    }
    client.on('error', onError)
    try {
      await client.query('BEGIN')
      const r = await client.query<{ id: string }>(
        `INSERT INTO assistant.ast_message(conversation_id, role, content, error, prompt_tokens, completion_tokens)
         VALUES ($1, 'assistant', $2, $3, $4, $5) RETURNING id::text`,
        [conversationId, content, error?.slice(0, 200) ?? null, promptTokens, completionTokens],
      )
      const id = r.rows[0]!.id
      for (const c of calls) {
        await client.query(
          `INSERT INTO assistant.ast_tool_call(message_id, tool, args, http_status, ok, rows, duration_ms)
           VALUES ($1, $2, $3, $4, $5, $6, $7)`,
          // jsonb 存不了 \u0000：模型给的参数里有就去掉
          [id, c.tool, JSON.stringify(c.args, (_k, v: unknown) => (typeof v === 'string' ? v.replaceAll('\0', '') : v)), c.status, c.ok, c.rows, c.durationMs],
        )
      }
      await client.query('UPDATE assistant.ast_conversation SET updated_at = now() WHERE id = $1', [conversationId])
      await client.query('COMMIT')
      return id
    } catch (e) {
      await client.query('ROLLBACK').catch(() => undefined)
      throw e
    } finally {
      // 断过的连接扔掉，好的放回池里（放回去的要把监听摘掉，不然借一次多挂一个）
      if (lost) client.release(true)
      else {
        client.off('error', onError)
        client.release()
      }
    }
  }
}

function dbLost(where: string, e: Error): void {
  console.error(JSON.stringify({ level: 'WARN', msg: 'db connection lost', where, error: e.message }))
}

/** 按顺序挑出"一问紧跟着一个好的回答"的对子，留最后 turns 对 */
export function pairUp(rows: { role: 'user' | 'assistant'; content: string; error: string | null }[], turns: number): StoredMessage[] {
  const pairs: [StoredMessage, StoredMessage][] = []
  for (let i = 0; i + 1 < rows.length; i++) {
    const q = rows[i]!
    const a = rows[i + 1]!
    if (q.role === 'user' && a.role === 'assistant' && a.error === null && a.content !== '') {
      pairs.push([{ role: 'user', content: q.content }, { role: 'assistant', content: a.content }])
      i++
    }
  }
  return turns > 0 ? pairs.slice(-turns).flat() : []
}

function version(file: string): number {
  return Number(/^V(\d+)__/.exec(file)?.[1] ?? NaN)
}
