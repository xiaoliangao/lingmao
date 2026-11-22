import assert from 'node:assert/strict'
import test from 'node:test'
import { fromScaledInteger, quantityPriceAmount, rescaleInteger, toScaledInteger } from '../src/utils/fixedPoint.ts'

test('decimal HALF_UP conversion handles binary float boundaries and signed ties', () => {
  assert.equal(toScaledInteger(1.00185, 4), 10019n)
  assert.equal(toScaledInteger('1.005', 2), 101n)
  assert.equal(toScaledInteger('-1.005', 2), -101n)
  assert.equal(toScaledInteger('5e-5', 4), 1n)
  assert.equal(toScaledInteger('4e-5', 4), 0n)
})

test('amount totals and large decimal strings never round-trip through binary numbers', () => {
  assert.equal(fromScaledInteger(toScaledInteger(0.1, 2) + toScaledInteger(0.2, 2), 2), '0.30')
  assert.equal(toScaledInteger('9007199254740993.01', 2), 900719925474099301n)
  assert.equal(fromScaledInteger(900719925474099301n, 2), '9007199254740993.01')
  assert.equal(fromScaledInteger(-1n, 2), '-0.01')
  assert.equal(fromScaledInteger(0n, 2), '0.00')
  assert.equal(fromScaledInteger(3n, 0), '3')
})

test('invalid or nonfinite decimal input is rejected explicitly', () => {
  for (const value of ['', '  ', '0x10', 'invalid', Number.NaN, Number.POSITIVE_INFINITY]) {
    assert.throws(() => toScaledInteger(value, 2), /有限十进制/)
  }
  for (const scale of [-1, 21, 1.5]) {
    assert.throws(() => toScaledInteger('1', scale), /小数位数/)
    assert.throws(() => fromScaledInteger(1n, scale), /小数位数/)
  }
})

test('BOM costs and labor sums round once at the final money scale', () => {
  const cost = toScaledInteger('0.018', 6) * (10_000n + toScaledInteger('0', 2)) * toScaledInteger('7.5', 4)
  assert.equal(fromScaledInteger(rescaleInteger(cost, 14, 2), 2), '0.14')
  const labor = toScaledInteger('0.0001', 4) + toScaledInteger('0.0249', 4)
  assert.equal(rescaleInteger(labor, 4, 2), 3n)
  assert.equal(rescaleInteger(-labor, 4, 2), -3n)
  assert.equal(rescaleInteger(3n, 0, 4), 30_000n)
  assert.equal(rescaleInteger(12n, 2, 2), 12n)
  assert.throws(() => rescaleInteger(1n, -1, 2), /小数位数/)
})

test('document line previews preserve null valuation and sum already-rounded cents', () => {
  assert.equal(quantityPriceAmount(0.018, 7.5), '0.14')
  assert.equal(quantityPriceAmount('0.018', '7.5000'), '0.14')
  assert.equal(quantityPriceAmount(0.018, 0), '0.00')
  assert.equal(quantityPriceAmount(null, 7.5), null)
  assert.equal(quantityPriceAmount(0.018, undefined), null)
  const cents = [0.018, 0.018].reduce((sum, qty) => sum + toScaledInteger(quantityPriceAmount(qty, 7.5), 2), 0n)
  assert.equal(fromScaledInteger(cents, 2), '0.28')
})
