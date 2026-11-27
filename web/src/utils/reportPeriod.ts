import { addBusinessDays, businessMonth, businessToday } from './businessDate.ts'

/** 报表的时间段（甲方 11-27 答 Q66：有的每周看，有的每月看）。周一到周日算一周，按东八区的日历日 */
export type Period = 'LAST_WEEK' | 'THIS_WEEK' | 'LAST_MONTH' | 'THIS_MONTH' | 'CUSTOM'

export const PERIOD_OPTIONS: { label: string; value: Period }[] = [
  { label: '上周', value: 'LAST_WEEK' },
  { label: '本周', value: 'THIS_WEEK' },
  { label: '上月', value: 'LAST_MONTH' },
  { label: '本月', value: 'THIS_MONTH' },
  { label: '自定', value: 'CUSTOM' },
]

function weekStart(day: string): string {
  const weekday = new Date(`${day}T00:00:00Z`).getUTCDay()
  return addBusinessDays(day, -((weekday + 6) % 7))
}

/** 时间段的起止日（含）；CUSTOM 返回 null（用自己选的） */
export function periodRange(period: Period, today = businessToday()): [string, string] | null {
  switch (period) {
    case 'THIS_WEEK': {
      const start = weekStart(today)
      return [start, addBusinessDays(start, 6)]
    }
    case 'LAST_WEEK': {
      const start = addBusinessDays(weekStart(today), -7)
      return [start, addBusinessDays(start, 6)]
    }
    case 'THIS_MONTH':
      return businessMonth(today)
    case 'LAST_MONTH':
      return businessMonth(addBusinessDays(businessMonth(today)[0], -1))
    default:
      return null
  }
}

/** 百分数的颜色：低于 warn 琥珀、低于 danger 红（higherIsBad 时反过来：高于才上色） */
export function rateTone(rate: number | null, warn: number, danger: number, higherIsBad = false): 'danger' | 'pending' | '' {
  if (rate == null) return ''
  if (higherIsBad) return rate >= danger ? 'danger' : rate >= warn ? 'pending' : ''
  return rate < danger ? 'danger' : rate < warn ? 'pending' : ''
}
