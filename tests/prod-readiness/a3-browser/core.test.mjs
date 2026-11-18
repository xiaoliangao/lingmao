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
import { visibleWriteBody } from '../../../web/src/utils/fieldAccess.ts'
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
test('export task create, cancel and retry are protected; arbitrary export mutations cannot replay', () => {
  for (const path of ['/api/export-jobs/stock-logs', '/api/export-jobs/9007199254740993/cancel', '/api/export-jobs/9007199254740993/retry']) assert.equal(canReplayWrite('post', path), true)
  for (const [method, path] of [['post', '/api/export-jobs/9007199254740993/file'], ['post', '/api/export-jobs/9007199254740993'], ['put', '/api/export-jobs/9007199254740993/retry'], ['post', '/api/export-jobs/stock-logs/extra']]) assert.equal(canReplayWrite(method, path), false)
})
test('batch search accepts 200 distinct codes and rejects 201', () => {
  validateKeywords(Array.from({ length: 200 }, (_, i) => `A${i}`).join(','))
  assert.throws(() => validateKeywords(Array.from({ length: 201 }, (_, i) => `A${i}`).join(' ')), /200/)
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
test('financial visibility keeps sales, production and cost grants independent', () => {
  const order = permission => permission === 'order:price:query'
  const production = permission => permission === 'production:price:query'
  const cost = permission => permission === 'techpack:cost:query'
  const body = { id: '9007199254740993', version: 3, companyId: '1', items: [{ id: '9007199254740995', qty: 7, price: 12.3, refCost: 4 }], totalAmount: 86.1, tagPrice: 88 }
  assert.equal(visibleWriteBody('/api/orders', body, order).items[0].price, 12.3)
  assert.equal(visibleWriteBody('/api/orders', body, order).items[0].refCost, undefined)
  assert.equal(visibleWriteBody('/api/orders', body, cost).items[0].price, undefined)
  assert.equal(visibleWriteBody('/api/production-orders/1', { unitPrice: 12.3 }, order).unitPrice, undefined)
  assert.equal(visibleWriteBody('/api/production-orders/1', { unitPrice: 12.3 }, production).unitPrice, 12.3)
  assert.equal(visibleWriteBody('/api/materials', { refPrice: 12.3 }, production).refPrice, undefined)
  assert.equal(visibleWriteBody('/api/materials', { refPrice: 12.3 }, cost).refPrice, 12.3)
  assert.deepEqual(visibleWriteBody('/api/orders', body, () => false), { id: '9007199254740993', version: 3, companyId: '1', items: [{ id: '9007199254740995', qty: 7 }], tagPrice: 88 })
  assert.equal(body.items[0].price, 12.3, 'caller data must not be mutated')
})
test('price archives use their entity type and protected fields are omitted rather than zeroed', () => {
  const production = permission => permission === 'production:price:query'
  assert.equal(visibleWriteBody('/api/prices', { type: 'PROCESS', price: 17.5 }, production).price, 17.5)
  assert.equal(visibleWriteBody('/api/prices', { type: 'MATERIAL', price: 17.5 }, production).price, undefined)
  const saved = visibleWriteBody('/api/tech-packs/1', { versionLock: 7, bomItems: [{ id: '9007199254740993', materialId: '2', refPrice: 5, priceTaxIncluded: false, priceFreightIncluded: false, skus: [{ materialSkuId: '3', colorId: '4', sizeCode: 'M', refPrice: 6 }] }], processes: [{ id: '9007199254740995', name: '车缝', price: 9 }], cost: { buyPrice: 19 } }, () => false)
  assert.deepEqual(saved, { versionLock: 7, bomItems: [{ id: '9007199254740993', materialId: '2', skus: [{ materialSkuId: '3', colorId: '4', sizeCode: 'M' }] }], processes: [{ id: '9007199254740995', name: '车缝' }] })
  assert.equal(visibleWriteBody('/api/orders', { items: [{ qty: 1 }] }, () => true).items[0].price, undefined)
  assert.deepEqual(visibleWriteBody('/api/production-orders/1', { taxPct: 13, taxIncluded: true }, () => false), { taxPct: 13, taxIncluded: true })
  assert.deepEqual(visibleWriteBody('/api/approval-rules/1', { overAmount: 5000 }, () => false), { overAmount: 5000 })
})
test('scheduling uses the production price domain', () => {
  const body = { blocks: [{ unitPrice: 17.25, priceSourceId: '9007199254740993', items: [{ orderItemId: '8', qty: 2 }] }] }
  const url = '/api/production-orders/schedule'
  assert.equal(visibleWriteBody(url, body, permission => permission === 'production:price:query').blocks[0].unitPrice, 17.25)
  for (const grant of ['order:price:query', 'techpack:cost:query']) assert.deepEqual(visibleWriteBody(url, body, permission => permission === grant), { blocks: [{ items: [{ orderItemId: '8', qty: 2 }] }] })
})
test('order change fee notes and the entire price array require the order price grant', () => {
  const body = { version: 3, feeNote: '合成费用说明', quantities: [{ styleSkuId: '9007199254740993', qty: 4 }], prices: [{ styleId: '11', price: 19.5 }] }
  for (const grant of ['', 'techpack:cost:query', 'production:price:query']) {
    assert.deepEqual(visibleWriteBody('/api/order-changes/707', body, permission => permission === grant), { version: 3, quantities: body.quantities })
  }
  assert.deepEqual(visibleWriteBody('/api/order-changes/707', body, permission => permission === 'order:price:query'), body)
  assert.deepEqual(visibleWriteBody('/api/production-orders/1', { feeNote: 'unrelated domain' }, () => false), { feeNote: 'unrelated domain' })
})
test('supplier contact and bank omission does not affect unrelated user phone fields', () => {
  const body = { name: '合成供应商', contact: '联系人', phone: 'test-only', wechat: 'test-only', address: 'test-only', bankName: 'test-only', bankAccount: 'test-only', bankAccountName: 'test-only' }
  assert.deepEqual(visibleWriteBody('/api/suppliers/1', body, () => false), { name: '合成供应商' })
  const contact = visibleWriteBody('/api/suppliers', body, permission => permission === 'supplier:supplier:contact')
  assert.equal(contact.contact, '联系人'); assert.equal(contact.bankName, undefined)
  assert.equal(visibleWriteBody('/api/users/1', { phone: 'test-only' }, () => false).phone, 'test-only')
})
