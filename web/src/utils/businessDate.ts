/** 业务日期（单据日期、交期）都按东八区的日历日算，和浏览器在哪个时区无关；值是 "2026-11-17" 这种字符串 */
const calendar = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })

export function businessToday(now: Date = new Date()): string {
  const parts = Object.fromEntries(calendar.formatToParts(now).map((part) => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day}`
}

function parseDay(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value ? date : null
}

export function addBusinessDays(value: string | null | undefined, days: number): string {
  const date = parseDay(value ?? '') ?? parseDay(businessToday())!
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

export function businessMonth(value = businessToday()): [string, string] {
  const date = parseDay(value) ?? parseDay(businessToday())!
  const start = `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-01`
  date.setUTCMonth(date.getUTCMonth() + 1, 0)
  return [start, date.toISOString().slice(0, 10)]
}

/** 给 Element Plus 日期控件用的 Date（浏览器本地时区），年月日照字符串里的 */
export function pickerDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year!, month! - 1, day!)
}

/** DatePicker 的 Date 是浏览器本地历法字段，只用其年月日与业务日比较。 */
export function pickerDay(value: Date): string {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
}
export function isFutureBusinessDay(value: Date, now: Date = new Date()): boolean {
  return pickerDay(value) > businessToday(now)
}
