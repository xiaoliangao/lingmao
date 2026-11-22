import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { cp, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, before, beforeEach, describe, test } from 'node:test'
import pg from 'pg'
import { Store } from '../src/store.ts'

/**
 * 连真的 PostgreSQL 测（pnpm test:db）。要先起本机开发库、跑一次 dev/setup-db.sh scm_assistant_test。
 * 每个用例前清空 assistant schema 重新迁移。库名不以 _test 结尾就拒绝跑，免得清掉开发库
 */
const ADMIN_URL = process.env.ASSISTANT_TEST_ADMIN_URL ?? 'postgres://scm@127.0.0.1:5433/scm_assistant_test'
const URL_ = process.env.ASSISTANT_TEST_DATABASE_URL ?? 'postgres://scm_assistant@127.0.0.1:5433/scm_assistant_test'
for (const u of [ADMIN_URL, URL_]) {
  if (!new URL(u).pathname.endsWith('_test')) throw new Error(`DB 测试只连 _test 结尾的库：${u}`)
}
const MIGRATIONS = new URL('../migrations', import.meta.url).pathname

const admin = new pg.Pool({ connectionString: ADMIN_URL, max: 2 })
let store: Store

before(async () => {
  await admin.query('SELECT 1')
})
after(async () => {
  await store?.close()
  await admin.end()
})

beforeEach(async () => {
  await store?.close()
  await admin.query('DROP SCHEMA IF EXISTS assistant CASCADE')
  await admin.query('CREATE SCHEMA assistant AUTHORIZATION scm_assistant')
  store = new Store(URL_)
})

describe('迁移文件', () => {
  test('文件名不合规、版本号重复：起不来，不悄悄跳过', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'ast-mig-'))
    try {
      await cp(MIGRATIONS, dir, { recursive: true })
      await writeFile(join(dir, 'V2__add-proposal.sql'), 'SELECT 1;')
      await assert.rejects(store.migrate(dir), /文件名 V2__add-proposal\.sql 不对/)
      await rm(join(dir, 'V2__add-proposal.sql'))
      await writeFile(join(dir, 'V1__again.sql'), 'SELECT 1;')
      await assert.rejects(store.migrate(dir), /版本号一样/)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })
})

describe('迁移', () => {
  test('空库执行全部；再启动一次什么都不做；表都在 assistant 下、归助手账号', async () => {
    assert.deepEqual(await store.migrate(MIGRATIONS), ['V1__init.sql'])
    assert.deepEqual(await store.migrate(MIGRATIONS), [])
    const owners = await admin.query<{ tablename: string; tableowner: string }>(
      `SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'assistant' ORDER BY tablename`,
    )
    assert.deepEqual(owners.rows.map((r) => r.tablename), ['ast_conversation', 'ast_message', 'ast_schema_version', 'ast_tool_call', 'ast_usage'])
    assert.ok(owners.rows.every((r) => r.tableowner === 'scm_assistant'))
  })

  test('执行过的迁移被改了：拒绝启动', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'ast-mig-'))
    try {
      await cp(MIGRATIONS, dir, { recursive: true })
      await store.migrate(dir)
      await writeFile(join(dir, 'V1__init.sql'), '-- 改了一个字\n', { flag: 'a' })
      await assert.rejects(store.migrate(dir), /校验和对不上/)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  test('新加的迁移出错：整个回滚，不记版本', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'ast-mig-'))
    try {
      await cp(MIGRATIONS, dir, { recursive: true })
      await writeFile(join(dir, 'V2__broken.sql'), 'CREATE TABLE assistant.half_done(id INT);\nSELECT * FROM no_such_table;\n')
      await assert.rejects(store.migrate(dir))
      const versions = await admin.query('SELECT version FROM assistant.ast_schema_version ORDER BY version')
      assert.deepEqual(versions.rows.map((r) => r.version), [1])
      const half = await admin.query(`SELECT 1 FROM pg_tables WHERE schemaname = 'assistant' AND tablename = 'half_done'`)
      assert.equal(half.rowCount, 0)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  test('助手账号读不到 app 的表（没授权）', async () => {
    await admin.query('CREATE TABLE IF NOT EXISTS public.app_secret(id INT)')
    try {
      await assert.rejects(store.pool.query('SELECT * FROM public.app_secret'), /permission denied/)
      await assert.rejects(store.pool.query('CREATE TABLE public.sneaky(id INT)'), /permission denied/)
    } finally {
      await admin.query('DROP TABLE IF EXISTS public.app_secret')
    }
  })
})

describe('限额', () => {
  test('每人每天的次数：到上限后不再记；20 个请求同时来也不会多算', async () => {
    await store.migrate(MIGRATIONS)
    assert.deepEqual([await store.takeRequest('1', 2), await store.takeRequest('1', 2), await store.takeRequest('1', 2)], [true, true, false])
    const results = await Promise.all(Array.from({ length: 20 }, () => store.takeRequest('2', 5)))
    assert.equal(results.filter(Boolean).length, 5)
    const row = await admin.query(`SELECT requests FROM assistant.ast_usage WHERE user_id = 2`)
    assert.equal(row.rows[0].requests, 5)
  })

  test('token 记到今天这个人的行上；全系统按天合计', async () => {
    await store.migrate(MIGRATIONS)
    await store.takeRequest('1', 10)
    await store.takeRequest('2', 10)
    await store.addTokens('1', 1000, 200)
    await store.addTokens('2', 50, 5)
    assert.equal(await store.tokensToday(), 1255)
    await admin.query(`INSERT INTO assistant.ast_usage(user_id, day, prompt_tokens) VALUES (3, current_date - 3, 99999)`)
    assert.equal(await store.tokensToday(), 1255, '前几天的不算')
  })

  test('过了零点才答完：今天还没有这个人的行，新建一行记上（不悄悄丢掉）', async () => {
    await store.migrate(MIGRATIONS)
    await admin.query(`INSERT INTO assistant.ast_usage(user_id, day, requests) VALUES (4, (now() AT TIME ZONE 'Asia/Shanghai')::date - 1, 1)`)
    await store.addTokens('4', 300, 30)
    assert.equal(await store.tokensToday(), 330)
    const today = await admin.query(`SELECT requests FROM assistant.ast_usage WHERE user_id = 4 AND day = (now() AT TIME ZONE 'Asia/Shanghai')::date`)
    assert.equal(today.rows[0].requests, 0, '次数记在问的那天，这里不加')
  })
})

describe('连接', () => {
  test('空闲连接被数据库踢掉（重启、备份时）：进程不退出，下一次查询重新连上', async () => {
    await store.migrate(MIGRATIONS)
    await store.ping()
    const killed = await admin.query(`SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'scm_assistant' AND datname = current_database() AND pid <> pg_backend_pid()`)
    assert.ok(killed.rowCount! >= 1)
    await new Promise((r) => setTimeout(r, 100))
    await store.ping()
    assert.equal(await store.tokensToday(), 0)
  })

  test('记回答的事务做到一半连接被踢（审查 R-01）：这次报错、进程不退出，断掉的连接不放回池里，下一次照常记', async () => {
    await store.migrate(MIGRATIONS)
    const id = randomUUID()
    await store.createConversation(id, '7', '断连')
    const connect = store.pool.connect.bind(store.pool)
    let killedPid: number | null = null
    // 只踢第一次借出的那条：第二条 SQL（INSERT ast_message）做完、下一条还没发时踢掉，等数据库那边真的断开
    ;(store.pool as { connect: () => Promise<pg.PoolClient> }).connect = async () => {
      const client = await connect()
      if (killedPid !== null) return client
      const query = client.query.bind(client) as (...a: unknown[]) => Promise<unknown>
      let n = 0
      ;(client as unknown as { query: (...a: unknown[]) => Promise<unknown> }).query = async (...a: unknown[]) => {
        const r = await query(...a)
        if (++n === 2) {
          killedPid = (client as unknown as { processID: number }).processID
          await admin.query('SELECT pg_terminate_backend($1)', [killedPid])
          await new Promise((res) => setTimeout(res, 150))
        }
        return r
      }
      return client
    }
    const calls = [{ tool: 't', args: {}, status: 200, ok: true, rows: 1, durationMs: 1 }]
    await assert.rejects(store.addAnswer(id, '一半', null, 1, 1, calls))
    assert.ok(killedPid !== null)
    // 池子按后进先出借：断掉的那条要是放回去了，这一次就借到它、报错
    await store.addAnswer(id, '好了', null, 1, 1, calls)
    const kept = await admin.query(`SELECT content FROM assistant.ast_message WHERE conversation_id = $1`, [id])
    assert.deepEqual(kept.rows.map((r: { content: string }) => r.content), ['好了'])
  })
})

describe('对话', () => {
  test('问答和工具调用一起记；历史不带出错的回答；只有本人的对话', async () => {
    await store.migrate(MIGRATIONS)
    const id = randomUUID()
    await store.createConversation(id, '7', '待审核的采购单有几张'.repeat(20))
    await store.addUserMessage(id, '待审核的采购单有几张')
    await store.addAnswer(id, '有 3 张。', null, 500, 20, [
      { tool: 'list_purchase_orders', args: { status: ['PENDING'], keyword: 'PO\u00001' }, status: 200, ok: true, rows: 3, durationMs: 42 },
    ])
    await store.addUserMessage(id, '最早的是哪张')
    await store.addAnswer(id, '', '模型太久没回应', 0, 0, [])

    // 没答出来的那一问连同回答都不带：不留落单的 user（严格交替的模型会报 400）
    assert.deepEqual(await store.history(id, 6), [
      { role: 'user', content: '待审核的采购单有几张' },
      { role: 'assistant', content: '有 3 张。' },
    ])
    assert.deepEqual((await store.messages(id)).map((m) => [m.role, m.error]), [['user', null], ['assistant', null], ['user', null], ['assistant', '模型太久没回应']])
    const calls = await admin.query('SELECT tool, args, http_status, ok, rows FROM assistant.ast_tool_call')
    assert.deepEqual(calls.rows, [{ tool: 'list_purchase_orders', args: { status: ['PENDING'], keyword: 'PO1' }, http_status: 200, ok: true, rows: 3 }], '参数里的 \\u0000 去掉了（jsonb 存不了）')

    assert.equal(await store.ownsConversation(id, '7'), true)
    assert.equal(await store.ownsConversation(id, '8'), false)
    const list = await store.listConversations('7', 20)
    assert.equal(list.length, 1)
    assert.equal(list[0]!.title.length, 100, '标题截到 100 个字')
  })

  test('删对话只删本人的、问答和工具记录跟着删；过期清理按最后一次问答的时间', async () => {
    await store.migrate(MIGRATIONS)
    const mine = randomUUID()
    const other = randomUUID()
    await store.createConversation(mine, '7', '我的')
    await store.createConversation(other, '8', '别人的')
    await store.addUserMessage(mine, '问')
    await store.addAnswer(mine, '答', null, 1, 1, [{ tool: 'list_orders', args: {}, status: 200, ok: true, rows: 1, durationMs: 5 }])
    assert.equal(await store.deleteConversation(mine, '8'), false, '别人删不了')
    assert.equal(await store.deleteConversation(mine, '7'), true)
    assert.equal(await store.deleteConversation(mine, '7'), false, '删过了')
    const left = await admin.query('SELECT (SELECT count(*) FROM assistant.ast_message)::int AS m, (SELECT count(*) FROM assistant.ast_tool_call)::int AS t')
    assert.deepEqual(left.rows[0], { m: 0, t: 0 })

    const fresh = randomUUID()
    await store.createConversation(fresh, '7', '刚问的')
    await admin.query("UPDATE assistant.ast_conversation SET updated_at = now() - interval '8 days' WHERE id = $1", [other])
    await admin.query("UPDATE assistant.ast_conversation SET updated_at = now() - interval '6 days' WHERE id = $1", [fresh])
    assert.equal(await store.purgeConversations(7), 1)
    assert.deepEqual((await admin.query('SELECT id FROM assistant.ast_conversation')).rows.map((r) => r.id), [fresh])

    // 接着问：续上 updated_at，过期没清理的也续上、之后不被清理；别人的、已经删掉的续不了
    await admin.query("UPDATE assistant.ast_conversation SET updated_at = now() - interval '8 days' WHERE id = $1", [fresh])
    assert.equal(await store.touchConversation(fresh, '8'), false, '别人的续不了')
    assert.equal(await store.touchConversation(fresh, '7'), true)
    assert.equal(await store.purgeConversations(7), 0, '续上的不清理')
    assert.equal(await store.touchConversation(other, '8'), false, '清理掉的续不了')
    await store.addUserMessage(fresh, '接着问')
    const row = await admin.query("SELECT updated_at > now() - interval '1 minute' AS recent, (SELECT count(*) FROM assistant.ast_message WHERE conversation_id = $1)::int AS m FROM assistant.ast_conversation WHERE id = $1", [fresh])
    assert.deepEqual(row.rows[0], { recent: true, m: 1 })
  })

  test('history 只取最近几轮', async () => {
    await store.migrate(MIGRATIONS)
    const id = randomUUID()
    await store.createConversation(id, '7', 't')
    for (let i = 1; i <= 5; i++) {
      await store.addUserMessage(id, `问${i}`)
      await store.addAnswer(id, `答${i}`, null, 0, 0, [])
    }
    assert.deepEqual((await store.history(id, 2)).map((m) => m.content), ['问4', '答4', '问5', '答5'])
  })
})
