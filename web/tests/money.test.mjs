import assert from 'node:assert/strict'
import test from 'node:test'
import { lineAmount, lineAmountCents, sumLineAmounts } from '../src/utils/money.ts'

test('数量 × 单价按 HALF_UP 到分，和后端 Num.amount 一样', () => {
  assert.equal(0.018 * 7.5, 0.13499999999999998) // 浮点直接乘的结果，显示成 0.13
  assert.equal(lineAmount(0.018, 7.5), 0.14)
  assert.equal(lineAmount(0.333, 0.005), 0) // 0.001665 → 0.00
  assert.equal(lineAmount(0.334, 0.015), 0.01) // 0.00501 → 0.01
  assert.equal(lineAmount(1.005, 1), 1.01) // 1.005 的浮点是 1.00499…，照样进位
  assert.equal(lineAmount(-0.018, 7.5), -0.14)
  assert.equal(lineAmountCents(999999.999, 9999999.9999), 999999998990000n) // 大数不丢精度
})

test('没填数量或单价的行没有金额，也不算进合计', () => {
  assert.equal(lineAmount(null, 3), null)
  assert.equal(lineAmount(2, null), null)
  assert.equal(lineAmount(2, Number.NaN), null)
  assert.equal(sumLineAmounts([{ qty: 0.018, price: 7.5 }, { qty: 0.018, price: 7.5 }, { qty: 3, price: null }]), 0.28)
})

test('合计是各行到分以后再加：0.1 + 0.2 不出 0.30000000000000004', () => {
  assert.equal(sumLineAmounts([{ qty: 1, price: 0.1 }, { qty: 1, price: 0.2 }]), 0.3)
  assert.equal(sumLineAmounts([]), 0)
})
