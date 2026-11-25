/**
 * 表单上先算给人看的金额，和后端存的一样（Num.amount：数量 × 单价，HALF_UP 到分；合计 = 各行金额相加）。
 * 浮点直接乘会差一分：0.018 × 7.5 = 0.13499999…，显示成 0.13，后端存 0.14。
 * 数量最多 3 位小数、单价最多 4 位（PRD §5.3），先放大成整数再乘，用 BigInt 不丢精度。
 */

const QTY_SCALE = 1000n
const PRICE_SCALE = 10000n
/** 数量 × 单价放大了 10^7，到分要再除 10^5 */
const TO_CENTS = (QTY_SCALE * PRICE_SCALE) / 100n

function scaled(value: number, scale: bigint): bigint {
  return BigInt(Math.round(value * Number(scale)))
}

/** 一行的金额，单位分；数量或单价没填返回 null */
export function lineAmountCents(qty: number | null | undefined, price: number | null | undefined): bigint | null {
  if (qty == null || price == null || !Number.isFinite(qty) || !Number.isFinite(price)) return null
  const product = scaled(qty, QTY_SCALE) * scaled(price, PRICE_SCALE)
  const negative = product < 0n
  const abs = negative ? -product : product
  const cents = (abs + TO_CENTS / 2n) / TO_CENTS // HALF_UP：离 0 远的一边
  return negative ? -cents : cents
}

/** 一行的金额（元），给 formatMoney 用 */
export function lineAmount(qty: number | null | undefined, price: number | null | undefined): number | null {
  const cents = lineAmountCents(qty, price)
  return cents == null ? null : Number(cents) / 100
}

/** 几行的合计（元）：各行先到分再相加，和后端一样；没填数量或单价的行不算 */
export function sumLineAmounts(rows: readonly { qty?: number | null; price?: number | null }[]): number {
  let cents = 0n
  for (const row of rows) cents += lineAmountCents(row.qty, row.price) ?? 0n
  return Number(cents) / 100
}
