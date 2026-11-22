/** 前端预览的定点数；服务端仍独立计算最终金额。 */
const formats = new Map<number, Intl.NumberFormat>()

function assertScale(scale: number) {
  if (!Number.isInteger(scale) || scale < 0 || scale > 20) throw new RangeError('小数位数必须在 0 至 20 之间')
}

/** 使用十进制字符串 HALF_UP 取舍，再转整数；不做浮点乘法。 */
export function toScaledInteger(value: number | string, scale: number): bigint {
  assertScale(scale)
  const text = String(value).trim()
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(text) || !Number.isFinite(Number(text))) {
    throw new TypeError('必须是有限十进制数')
  }
  let format = formats.get(scale)
  if (!format) {
    format = new Intl.NumberFormat('en-US', { useGrouping: false, minimumFractionDigits: scale, maximumFractionDigits: scale })
    formats.set(scale, format)
  }
  return BigInt(format.format(text as Intl.StringNumericLiteral).replace('.', ''))
}

/** 将整数金额还原成十进制文本，显示时也不经过 Number。 */
export function fromScaledInteger(value: bigint, scale: number): string {
  assertScale(scale)
  if (scale === 0) return String(value)
  const sign = value < 0n ? '-' : ''
  const digits = String(value < 0n ? -value : value).padStart(scale + 1, '0')
  return `${sign}${digits.slice(0, -scale)}.${digits.slice(-scale)}`
}

/** 定点乘积或合计调整小数位；负数也按 HALF_UP（中点远离零）取舍。 */
export function rescaleInteger(value: bigint, fromScale: number, toScale: number): bigint {
  assertScale(fromScale)
  assertScale(toScale)
  if (fromScale <= toScale) return value * 10n ** BigInt(toScale - fromScale)
  const divisor = 10n ** BigInt(fromScale - toScale)
  const absolute = value < 0n ? -value : value
  const rounded = (absolute + divisor / 2n) / divisor
  return value < 0n ? -rounded : rounded
}

/** 单据行：数量 3 位 × 单价 4 位，按后端规则逐行 HALF_UP 到分；未估值仍为空。 */
export function quantityPriceAmount(qty: number | string | null | undefined, price: number | string | null | undefined): string | null {
  if (qty == null || price == null) return null
  return fromScaledInteger(rescaleInteger(toScaledInteger(qty, 3) * toScaledInteger(price, 4), 7, 2), 2)
}
