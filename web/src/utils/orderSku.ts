/** SKU 行是保存的单位；批量输入只在显式应用时修改它们。 */
export interface SkuValues {
  price: number | null
  lineDueDate: string | null
  remark: string | null
}
export interface SkuFormRow extends SkuValues { styleSkuId: number; qty: number }
export const MAX_UNIT_PRICE = 9999999999.9999
export const MAX_ORDER_QTY = 2147483647
const MAX_AMOUNT_CENTS = 99999999999999n

export function hydrateSkuValues(rows: readonly (SkuValues & { styleSkuId: number })[]): Record<number, SkuValues> {
  return Object.fromEntries(rows.map(({ styleSkuId, price, lineDueDate, remark }) => [styleSkuId, { price, lineDueDate, remark }]))
}

export function buildSkuRows(ids: readonly number[], qtys: Record<number, number>, values: Record<number, SkuValues>): SkuFormRow[] {
  return ids.flatMap((styleSkuId) => {
    const qty = qtys[styleSkuId] ?? 0
    if (qty <= 0) return []
    return [{ styleSkuId, qty, ...(values[styleSkuId] ?? { price: null, lineDueDate: null, remark: null }) }]
  })
}

export function applySkuValues(values: Record<number, SkuValues>, ids: readonly number[], patch: Partial<SkuValues>): void {
  for (const id of ids) values[id] = { ...(values[id] ?? { price: null, lineDueDate: null, remark: null }), ...patch }
}

/** JSON 容量以内，用整数分计算每行四位单价 × 件数后的 HALF_UP 两位金额。 */
export function lineAmountCents(price: number, qty: number): bigint {
  const scaled = BigInt(Math.round(price * 10000))
  return (scaled * BigInt(qty) + 50n) / 100n
}

export function validateOrderCapacity(rows: readonly SkuFormRow[]): string | null {
  let qty = 0
  let cents = 0n
  for (const row of rows) {
    if (!Number.isSafeInteger(row.qty) || row.qty <= 0 || row.qty > MAX_ORDER_QTY) return '件数必须为不超过 2147483647 的正整数'
    qty += row.qty
    if (qty > MAX_ORDER_QTY) return '整单件数不能超过 2147483647'
    if (row.price == null) continue
    if (!Number.isFinite(row.price) || row.price < 0 || row.price > MAX_UNIT_PRICE) return '单价必须在 0 至 9999999999.9999 之间'
    const amount = lineAmountCents(row.price, row.qty)
    if (amount > MAX_AMOUNT_CENTS) return '单行金额不能超过 999999999999.99'
    cents += amount
    if (cents > MAX_AMOUNT_CENTS) return '整单金额不能超过 999999999999.99'
  }
  return null
}
