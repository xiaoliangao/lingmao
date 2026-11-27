import assert from 'node:assert/strict'
import test from 'node:test'
import { periodRange, rateTone } from '../src/utils/reportPeriod.ts'

test('report periods: weeks start on Monday, months cross the year', () => {
  // 2026-11-27 是星期五
  assert.deepEqual(periodRange('THIS_WEEK', '2026-11-27'), ['2026-11-23', '2026-11-29'])
  assert.deepEqual(periodRange('LAST_WEEK', '2026-11-27'), ['2026-11-16', '2026-11-22'])
  // 星期天还算这一周
  assert.deepEqual(periodRange('THIS_WEEK', '2026-11-29'), ['2026-11-23', '2026-11-29'])
  assert.deepEqual(periodRange('LAST_MONTH', '2027-01-10'), ['2026-12-01', '2026-12-31'])
  assert.deepEqual(periodRange('THIS_MONTH', '2027-02-10'), ['2027-02-01', '2027-02-28'])
  assert.equal(periodRange('CUSTOM', '2026-11-27'), null)
})

test('rate tones: low on-time rate and high defect rate are flagged', () => {
  assert.equal(rateTone(null, 95, 80), '')
  assert.equal(rateTone(96, 95, 80), '')
  assert.equal(rateTone(90, 95, 80), 'pending')
  assert.equal(rateTone(70, 95, 80), 'danger')
  assert.equal(rateTone(1, 2, 5, true), '')
  assert.equal(rateTone(3, 2, 5, true), 'pending')
  assert.equal(rateTone(5, 2, 5, true), 'danger')
})
