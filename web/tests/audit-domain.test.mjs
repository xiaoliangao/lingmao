import assert from 'node:assert/strict'
import test from 'node:test'
import { addBusinessDays, businessMonth, businessToday, isFutureBusinessDay, pickerDate, pickerDay } from '../src/utils/businessDate.ts'
import { safeRedirect } from '../src/utils/redirect.ts'
import { cleanQuery, statusQuery } from '../src/utils/query.ts'
import { applySkuValues, buildSkuRows, hydrateSkuValues, lineAmountCents, MAX_UNIT_PRICE, validateOrderCapacity } from '../src/utils/orderSku.ts'
import { newClientAction, unknownActionResult } from '../src/utils/clientAction.ts'
import { createPendingTasks } from '../src/utils/pendingTasks.ts'

const original = [
  { styleSkuId: 12, qty: 2, price: 12.3456, lineDueDate: '2027-01-02', remark: 'M / 红' },
  { styleSkuId: 21, qty: 3, price: 23.4567, lineDueDate: null, remark: null },
  { styleSkuId: 11, qty: 4, price: null, lineDueDate: '2027-01-03', remark: 'S / 红' },
]
const quantities = () => Object.fromEntries(original.map((row) => [row.styleSkuId, row.qty]))

test('SKU round-trip retains heterogenous prices, dates, notes and nulls', () => {
  const values = hydrateSkuValues(original)
  assert.deepEqual(buildSkuRows([12, 21, 11], quantities(), values), original)
  values[12].remark = 'changed'
  assert.equal(original[0].remark, 'M / 红')
})
test('explicit size batch affects only selected SKU prices', () => {
  const values = hydrateSkuValues(original)
  applySkuValues(values, [12, 21], { price: 45.6789 })
  const saved = buildSkuRows([12, 21, 11], quantities(), values)
  assert.deepEqual(saved.map((row) => row.price), [45.6789, 45.6789, null])
  assert.deepEqual(saved.map(({ lineDueDate, remark }) => ({ lineDueDate, remark })), original.map(({ lineDueDate, remark }) => ({ lineDueDate, remark })))
})
test('quantity-only changes, deletion and new SKU defaults are lossless', () => {
  const values = hydrateSkuValues(original)
  const rows = buildSkuRows([12, 21, 11, 31], { 12: 10, 21: 0, 11: 4, 31: 1 }, values)
  assert.equal(rows.find((r) => r.styleSkuId === 12).price, 12.3456)
  assert.ok(!rows.some((r) => r.styleSkuId === 21))
  assert.deepEqual(rows.at(-1), { styleSkuId: 31, qty: 1, price: null, lineDueDate: null, remark: null })
})
test('explicit whole-style updates do not leak into another style or other fields', () => {
  const values = hydrateSkuValues(original)
  values[99] = { price: 9, lineDueDate: null, remark: 'other' }
  applySkuValues(values, [12, 21, 11], { price: 67.8912 })
  assert.equal(values[99].price, 9)
  assert.equal(values[12].lineDueDate, '2027-01-02')
  assert.equal(values[21].remark, null)
})
test('line amounts use four-place prices and HALF_UP cents', () => {
  assert.equal(lineAmountCents(1.005, 1), 101n)
  assert.equal(lineAmountCents(1.005, 3), 302n)
  assert.equal(lineAmountCents(12.3456, 2), 2469n)
  assert.equal(lineAmountCents(MAX_UNIT_PRICE, 1), 1000000000000n)
})
test('order capacity rejects total quantity, row amount and total amount separately', () => {
  const row = (qty, price) => ({ styleSkuId: 1, qty, price, lineDueDate: null, remark: null })
  assert.equal(validateOrderCapacity([row(1, MAX_UNIT_PRICE)]), null)
  assert.match(validateOrderCapacity([row(1, 10000000000)]), /单价/)
  assert.match(validateOrderCapacity([row(2147483647, null), row(1, null)]), /整单件数/)
  assert.match(validateOrderCapacity([row(1000000, 1000000000)]), /单行金额/)
  assert.match(validateOrderCapacity([row(100, 6000000000), row(100, 6000000000)]), /整单金额/)
  assert.match(validateOrderCapacity([row(1.5, 1)]), /正整数/)
  assert.match(validateOrderCapacity([row(1, Number.NaN)]), /单价/)
})
test('query normalization removes UI fields and freezes nested filter values', () => {
  const query = { keyword: '  DD001 DD002  ', statusTab: 'DRAFT', empty: '', owner: null, ids: [], range: ['2026-01-01', '2026-01-31'], mine: false }
  const snapshot = cleanQuery(statusQuery(query))
  assert.deepEqual(snapshot.status, ['DRAFT'])
  assert.equal(snapshot.keyword, 'DD001 DD002')
  assert.equal(snapshot.mine, false)
  for (const key of ['statusTab', 'empty', 'owner', 'ids']) assert.ok(!(key in snapshot))
  query.range[0] = '2030-01-01'
  query.keyword = 'not searched yet'
  assert.equal(snapshot.range[0], '2026-01-01')
  assert.equal(snapshot.keyword, 'DD001 DD002')
})
test('all-status filter does not invent an empty API status', () => {
  assert.deepEqual(cleanQuery(statusQuery({ statusTab: '', keyword: '' })), {})
})
test('business midnight is Shanghai midnight regardless of an input offset', () => {
  assert.equal(businessToday(new Date('2026-12-31T15:59:59Z')), '2026-12-31')
  assert.equal(businessToday(new Date('2026-12-31T16:00:00Z')), '2027-01-01')
  assert.equal(businessToday(new Date('2026-12-31T08:30:00-08:00')), '2027-01-01')
})
test('date addition and month ranges are calendar operations across leap/DST/year boundaries', () => {
  assert.equal(addBusinessDays('2024-02-28', 1), '2024-02-29')
  assert.equal(addBusinessDays('2024-02-29', 1), '2024-03-01')
  assert.equal(addBusinessDays('2027-01-01', -6), '2026-12-26')
  assert.equal(addBusinessDays('2027-01-01', -29), '2026-12-03')
  assert.deepEqual(businessMonth('2024-02-14'), ['2024-02-01', '2024-02-29'])
  assert.deepEqual(businessMonth('2026-12-31'), ['2026-12-01', '2026-12-31'])
})
test('date-picker preserves calendar fields and compares to the business date', () => {
  assert.equal(pickerDay(pickerDate('2027-01-01')), '2027-01-01')
  const now = new Date('2026-12-31T16:30:00Z')
  assert.equal(isFutureBusinessDay(pickerDate('2027-01-01'), now), false)
  assert.equal(isFutureBusinessDay(pickerDate('2027-01-02'), now), true)
})
test('safe login redirect retains internal query/hash', () => {
  assert.equal(safeRedirect('/orders/1?tab=lines#sku', 'https://scm.example'), '/orders/1?tab=lines#sku')
})
test('unsafe or ambiguous login redirects fall back to home', () => {
  const invalid = [null, undefined, [], ['/orders'], '', 'https://bad.example/', '//bad.example', 'javascript:alert(1)', '/\\bad.example', '/%5cbad.example', '/%2f%2fbad.example', '/x\n', '/x%00', '/%', '/x%20y']
  for (const value of invalid) assert.equal(safeRedirect(value, 'https://scm.example'), '/', String(value))
})
test('a client action preserves the payload snapshot and distinct genuine actions receive distinct IDs', () => {
  const input = { items: [{ qty: 2 }], post: true }
  const first = newClientAction(input, '00000000-0000-4000-8000-000000000001')
  input.items[0].qty = 9
  const retry = globalThis.structuredClone(first)
  assert.equal(retry.items[0].qty, 2)
  assert.equal(retry.clientActionId, first.clientActionId)
  const next = newClientAction(input, '00000000-0000-4000-8000-000000000002')
  assert.notEqual(next.clientActionId, first.clientActionId)
})
function withCrypto(source, check) {
  const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  assert.ok(originalCrypto)
  try {
    Object.defineProperty(globalThis, 'crypto', { configurable: true, value: source })
    check()
  } finally {
    Object.defineProperty(globalThis, 'crypto', originalCrypto)
  }
}
test('client actions prefer the native randomUUID when it is available', () => {
  const nativeId = '12345678-1234-4234-8234-123456789abc'
  let calls = 0
  withCrypto({
    randomUUID() { calls++; return nativeId },
    getRandomValues() { assert.fail('Native UUID support must not use the fallback') },
  }, () => {
    assert.equal(newClientAction({ qty: 2 }).clientActionId, nativeId)
    assert.equal(calls, 1)
  })
})
test('HTTP contexts without randomUUID generate distinct v4 actions using getRandomValues', () => {
  const browserCrypto = globalThis.crypto
  let calls = 0
  withCrypto({
    getRandomValues(bytes) {
      assert.ok(bytes instanceof Uint8Array)
      assert.equal(bytes.length, 16)
      calls++
      return browserCrypto.getRandomValues(bytes)
    },
  }, () => {
    const payload = { items: [{ qty: 2 }] }
    const first = newClientAction(payload)
    const second = newClientAction(payload)
    const v4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    assert.match(first.clientActionId, v4)
    assert.match(second.clientActionId, v4)
    assert.notEqual(first.clientActionId, second.clientActionId)
    assert.equal(calls, 2)
    payload.items[0].qty = 9
    assert.equal(first.items[0].qty, 2)
    assert.equal(globalThis.structuredClone(first).clientActionId, first.clientActionId)
  })
})
test('network/5xx failures retain unknown action identity; business validation failures do not', () => {
  for (const code of [undefined, -1, 500, 502, 503]) assert.equal(unknownActionResult(code), true)
  for (const code of [400, 403, 1000, 1002, 1004]) assert.equal(unknownActionResult(code), false)
})
test('saving awaits all current uploads and reports failure', async () => {
  const queue = createPendingTasks()
  const a = queue.begin(), b = queue.begin()
  let completed = false
  const wait = queue.wait().then((ok) => { completed = true; return ok })
  a.finish(true)
  await Promise.resolve()
  assert.equal(completed, false)
  b.finish(false)
  assert.equal(await wait, false)
})
test('discarding upload invalidates late result without invalidating a new session', async () => {
  const queue = createPendingTasks()
  const old = queue.begin()
  const wait = queue.wait()
  queue.discard()
  const fresh = queue.begin()
  assert.equal(old.current(), false)
  assert.equal(fresh.current(), true)
  old.finish(true)
  assert.equal(fresh.current(), true)
  assert.equal(await wait, false)
  const nextWait = queue.wait()
  fresh.finish(true)
  assert.equal(await nextWait, true)
})
