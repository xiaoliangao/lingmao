import { ApiError } from '@/utils/request'
import { addBusinessDays } from '@/utils/businessDate'
import type { FgDocType, FgStyleOption } from '@/types/fg'
import { readFgBatches } from './fgRead'

const idPattern = /^[1-9]\d{0,18}$/
function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(400, '本地草稿格式无效，未恢复任何内容')
  return value as Record<string, unknown>
}
function text(value: unknown, limit: number): string {
  if (typeof value !== 'string' || value.length > limit) throw new ApiError(400, '本地草稿字段无效，未恢复任何内容')
  return value
}
function id(value: unknown, nullable = false): string | null {
  if (nullable && value === null) return null
  if (typeof value !== 'string' || !idPattern.test(value)) throw new ApiError(400, '本地草稿对象无效，未恢复任何内容')
  return value
}
function date(value: unknown): string {
  const day = text(value, 10)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || addBusinessDays(day, 0) !== day) throw new ApiError(400, '本地草稿日期无效，未恢复任何内容')
  return day
}
function quantities(value: unknown): Record<string, number> {
  const entries = Object.entries(record(value))
  if (entries.length > 50_000 || entries.some(([key, qty]) => !idPattern.test(key) || typeof qty !== 'number' || !Number.isSafeInteger(qty) || qty < 0 || qty > 99_999_999)) {
    throw new ApiError(400, '本地草稿数量无效，未恢复任何明细')
  }
  return Object.fromEntries(entries) as Record<string, number>
}
export function assertDraftSkus(qtys: Record<string, number>, ids: ReadonlySet<string>) {
  if (Object.keys(qtys).some((key) => !ids.has(key))) throw new ApiError(409, '草稿包含已不可用的色码，未恢复任何明细，请核对原草稿')
}
export function assertFgMatrixBudget(styles: readonly FgStyleOption[]) {
  if (styles.reduce((sum, style) => sum + style.colors.length, 0) > 50_000 ||
      styles.reduce((sum, style) => sum + style.sizes.length, 0) > 50_000 ||
      styles.reduce((sum, style) => sum + style.skus.length, 0) > 50_000) {
    throw new ApiError(413, '所选款式的颜色、尺码或 SKU 资料超过 50000 项，请分开录单')
  }
}
export async function readFgStyles(ids: readonly string[], fetch: (batch: string[]) => Promise<FgStyleOption[]>, signal: AbortSignal) {
  const seen: FgStyleOption[] = []
  return readFgBatches(ids, async (batch) => {
    const styles = await fetch(batch)
    assertFgMatrixBudget([...seen, ...styles])
    seen.push(...styles)
    return styles
  }, signal)
}
export function readReceiptDraft(value: unknown) {
  const saved = record(value)
  return { warehouseId: id(saved.warehouseId, true), docDate: date(saved.docDate), remark: text(saved.remark, 500), qtys: quantities(saved.qtys) }
}
export interface ManualDraftHead {
  companyId: string
  warehouseId: string | null
  orderId: string | null
  docDate: string
  reason: string | null
  shipTo: string
  logisticsNo: string
  remark: string
}
export interface ManualDraftBlock { styleId: string; qtys: Record<string, number> }
export function readManualDraft(value: unknown, companyId: string, docType: FgDocType): { head: ManualDraftHead; blocks: ManualDraftBlock[] } {
  const saved = record(value), head = record(saved.head)
  if (saved.docType !== docType || head.companyId !== companyId) throw new ApiError(409, '草稿主体或单据类型不一致，未恢复任何内容')
  if (!Array.isArray(saved.blocks) || saved.blocks.length > 50_000) throw new ApiError(400, '本地草稿款式数量无效，未恢复任何明细')
  const blocks = saved.blocks.map((entry) => {
    const block = record(entry)
    return { styleId: id(block.styleId) as string, qtys: quantities(block.qtys) }
  })
  if (new Set(blocks.map((block) => block.styleId)).size !== blocks.length) throw new ApiError(400, '本地草稿包含重复款式，未恢复任何明细')
  return { head: { companyId, warehouseId: id(head.warehouseId, true), orderId: id(head.orderId, true), docDate: date(head.docDate),
    reason: head.reason === null ? null : text(head.reason, 60), shipTo: text(head.shipTo, 200), logisticsNo: text(head.logisticsNo, 60), remark: text(head.remark, 500) }, blocks }
}
