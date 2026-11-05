/**
 * 数量、金额、日期的显示格式（PRD §5.3）。表格、详情、打印都用这里的函数，同一个数在哪儿看都一样。
 * 后端的 BigDecimal 到前端是 JSON 数字，手里的数也可能是字符串，两种都收；空值显示成空串。
 */

type NumberInput = number | string | null | undefined

// 先变成十进制字符串再交给 Intl：Intl 直接按字符串里的十进制数取舍，不经过浮点数（1.005 → 1.01，不会变成 1.00）
const qtyFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3, useGrouping: false })
const moneyFormat = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const priceFormat = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4, useGrouping: false })

function formatNumber(value: NumberInput, format: Intl.NumberFormat): string {
  if (value == null) return ''
  const text = typeof value === 'number' ? String(value) : value.trim()
  if (text === '') return ''
  // 不是数字的原样显示：数据有问题时能看出来，不会变成 NaN
  if (!Number.isFinite(Number(text))) return text
  return format.format(text as Intl.StringNumericLiteral)
}

/** 数量：最多 3 位小数，去掉末尾的 0。1.500 → 1.5，2.000 → 2 */
export function formatQty(value: NumberInput): string {
  return formatNumber(value, qtyFormat)
}

/** 金额：两位小数，千分位。1234.5 → 1,234.50 */
export function formatMoney(value: NumberInput): string {
  return formatNumber(value, moneyFormat)
}

/** 单价：最多 4 位小数，去掉末尾的 0。12.3400 → 12.34 */
export function formatPrice(value: NumberInput): string {
  return formatNumber(value, priceFormat)
}

type DateInput = string | number | Date | null | undefined

/**
 * 已经是东八区的写法：后端 @JsonFormat 出来的 "2026-11-05 10:30:00"、LocalDate 的 "2026-11-05"，
 * 或者带 +08:00 的 ISO 时间。这几种直接截，不经过 Date（Safari 解析不了中间带空格的写法）
 */
const LOCAL_TIME = /^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?)?(Z|[+-]\d{2}:?\d{2})?$/

/** 别的时区、时间戳、Date：换算成东八区再显示（PRD §8 时间一律东八区，不看这台电脑设的时区） */
const shanghai = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

/** 拆成日期、时间两段；认不出来返回 null */
function splitDate(value: Exclude<DateInput, null | undefined>): { date: string; time: string } | null {
  if (typeof value === 'string') {
    const match = LOCAL_TIME.exec(value.trim())
    const zone = match?.[3]
    if (match && (!zone || zone === '+08:00' || zone === '+0800')) {
      return { date: match[1]!, time: match[2] ?? '' }
    }
  }
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const parts = Object.fromEntries(shanghai.formatToParts(date).map((part) => [part.type, part.value]))
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` }
}

/** 日期：2026-11-05 */
export function formatDate(value: DateInput): string {
  if (value == null || value === '') return ''
  return splitDate(value)?.date ?? String(value)
}

/** 日期时间，到分钟：2026-11-05 10:30。只有日期的就只显示日期 */
export function formatDateTime(value: DateInput): string {
  if (value == null || value === '') return ''
  const parts = splitDate(value)
  if (!parts) return String(value)
  return parts.time ? `${parts.date} ${parts.time}` : parts.date
}

/** 文件大小：812 KB、3.4 MB */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes)) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
