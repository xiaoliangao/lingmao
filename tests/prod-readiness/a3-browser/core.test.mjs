import assert from 'node:assert/strict'
import test from 'node:test'
import { singleFlight } from '../../../web/src/utils/singleFlight.ts'
import { createLatestRequest, currentReadScope } from '../../../web/src/utils/requestScope.ts'
import { routeId } from '../../../web/src/utils/entityId.ts'
import { isComposing } from '../../../web/src/utils/ime.ts'
import { businessToday, pickerDate, pickerDay, addBusinessDays } from '../../../web/src/utils/businessDate.ts'
import { validateKeywords } from '../../../web/src/utils/keywords.ts'
import { containsSecrets, restoreDraftValue } from '../../../web/src/utils/draft.ts'
import { sha256 } from '../../../web/src/utils/sha256.ts'
import { canReplayWrite } from '../../../web/src/utils/writePolicy.ts'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'

test('T11 eight concurrent calls share exactly one refresh and all complete', async () => {
  let calls = 0
  let finish
  const release = new Promise((resolve) => { finish = resolve })
  const run = singleFlight(async () => { ++calls; await release; return 'renewed' })
  const all = Promise.all(Array.from({ length: 8 }, () => run()))
  await Promise.resolve()
  assert.equal(calls, 1)
  finish()
  assert.deepEqual(await all, Array(8).fill('renewed'))
  assert.equal(await run(), 'renewed')
  assert.equal(calls, 2)
})
test('refresh failure releases the flight and rejects every waiter', async () => {
  let calls = 0
  const run = singleFlight(async () => { calls++; throw new Error('expired') })
  const results = await Promise.allSettled(Array.from({ length: 8 }, () => run()))
  assert.equal(calls, 1)
  assert.ok(results.every((result) => result.status === 'rejected'))
  await assert.rejects(run(), /expired/)
  assert.equal(calls, 2)
})
test('single flight captures the initiating token and epoch before the microtask runs', async () => {
  const seen = []
  const run = singleFlight(async (token, epoch) => { seen.push([token, epoch]); return token })
  const pending = [run('old-access', 'initial-epoch'), run('later-access', 'later-epoch')]
  assert.deepEqual(await Promise.all(pending), ['old-access', 'old-access'])
  assert.deepEqual(seen, [['old-access', 'initial-epoch']])
})
test('T11 stale read aborts transport and cannot apply a late result', async () => {
  const requests = createLatestRequest()
  const old = requests.begin()
  let observed
  old.run(() => { observed = currentReadScope()?.signal })
  assert.equal(observed, old.signal)
  assert.equal(currentReadScope(), undefined)
  const fresh = requests.begin()
  assert.equal(old.signal.aborted, true)
  assert.equal(old.current(), false)
  assert.equal(fresh.current(), true)
  requests.cancel()
  assert.equal(fresh.signal.aborted, true)
})
test('IME Enter and legacy 229 never trigger a search', () => {
  assert.equal(isComposing({ isComposing: true, keyCode: 13 }), true)
  assert.equal(isComposing({ isComposing: false, keyCode: 229 }), true)
  assert.equal(isComposing({ isComposing: false, keyCode: 13 }), false)
})
test('BIGINT routes preserve the final digit and reject invalid/overflow ids', () => {
  assert.equal(routeId('9007199254740993'), '9007199254740993')
  assert.equal(routeId('9223372036854775807'), '9223372036854775807')
  for (const value of ['9223372036854775808', '1e4', '-1', '0', ['1'], 9007199254740992]) assert.throws(() => routeId(value))
})
test('T11 business dates remain date strings under the process timezone', () => {
  assert.equal(businessToday(new Date('2026-11-17T16:05:00Z')), '2026-11-18')
  assert.equal(pickerDay(pickerDate('2026-11-17')), '2026-11-17')
  assert.equal(addBusinessDays('2026-11-17', 1), '2026-11-18')
})
test('date-only controls stay unchanged in Shanghai, UTC and Los Angeles', () => {
  const url = new URL('../../../web/src/utils/businessDate.ts', import.meta.url).href
  const script = `import { pickerDay, pickerDate, businessToday } from ${JSON.stringify(url)}; console.log(JSON.stringify([pickerDay(pickerDate('2026-11-17')), businessToday(new Date('2026-11-17T16:05:00Z'))]))`
  for (const TZ of ['Asia/Shanghai', 'UTC', 'America/Los_Angeles']) {
    const output = execFileSync(process.execPath, ['--input-type=module', '-e', script], { env: { ...process.env, TZ }, encoding: 'utf8' })
    assert.deepEqual(JSON.parse(output), ['2026-11-17', '2026-11-18'])
  }
})
test('intranet HTTP SHA-256 fallback matches the native implementation', () => {
  for (const text of ['', 'abc', '合成库存单 🚚', 'a'.repeat(55), 'b'.repeat(56), 'c'.repeat(64), 'd'.repeat(100000)]) {
    assert.equal(sha256(new TextEncoder().encode(text)), createHash('sha256').update(text).digest('hex'))
  }
})
test('only server-protected writes can replay; read-only POST and CAS actions cannot', () => {
  for (const url of ['/api/orders', '/api/materials', '/api/styles/9007199254740993/skus/generate', '/api/inventory-docs/from-purchase/9007199254740993', '/api/production-orders/1/nodes/2/reports']) assert.equal(canReplayWrite('post', url), true)
  for (const [method, url] of [['post', '/api/tech-packs/1/bom-defaults'], ['post', '/api/orders/1/audit'], ['put', '/api/orders/1'], ['post', '/api/roles']]) assert.equal(canReplayWrite(method, url), false)
})
test('batch search accepts 100 distinct codes and rejects 101', () => {
  validateKeywords(Array.from({ length: 100 }, (_, i) => `A${i}`).join(','))
  assert.throws(() => validateKeywords(Array.from({ length: 101 }, (_, i) => `A${i}`).join(' ')), /100/)
})
test('draft restore keeps reactive containers and never writes dangerous prototype fields', () => {
  const lines = [{ qty: 1 }]
  const head = { remark: '' }
  const target = { head, lines }
  restoreDraftValue(target, JSON.parse('{"head":{"remark":"draft"},"lines":[{"qty":2}],"__proto__":{"polluted":true}}'))
  assert.equal(target.lines, lines)
  assert.equal(target.head, head)
  assert.equal(head.remark, 'draft')
  assert.equal(lines[0].qty, 2)
  assert.equal({}.polluted, undefined)
  assert.equal(containsSecrets({ form: { password: 'test-only' } }), true)
  assert.equal(containsSecrets({ lines: [{ qty: 2 }] }), false)
  const maps = { qtys: {}, sizeUsages: {}, skuMap: {} }
  restoreDraftValue(maps, JSON.parse('{"qtys":{"9007199254740993":12},"sizeUsages":{"XL":2},"skuMap":{"FIXED":{"materialSkuId":"9007199254740993","refPrice":null,"__proto__":{"polluted":true}}}}'))
  assert.deepEqual(maps.qtys, { '9007199254740993': 12 })
  assert.deepEqual(maps.sizeUsages, { XL: 2 })
  assert.deepEqual(maps.skuMap.FIXED, { materialSkuId: '9007199254740993', refPrice: null })
})
