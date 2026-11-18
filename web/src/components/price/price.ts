import type { PriceRow, PriceType } from '@/types/price'
import { priceReadPermission } from '@/utils/fieldAccess'

/** 价格记录页签（PriceHistory）和录价对话框（PriceForm）共用的小件 */

/** 从哪一页打开的：物料、供应商、款式详情。code、name 是给对话框显示、查 SKU 用的 */
export interface PriceSubject {
  kind: 'supplier' | 'material' | 'style'
  id: string
  code: string
  name: string
}

export const EDIT_PERM: Record<PriceType, string> = { MATERIAL: 'price:material:edit', PROCESS: 'price:process:edit' }

/** 从这一页能录哪几种：有权限，而且对话框里选得到要录的东西（物料页只有物料价，款式页只有加工价） */
export function recordableTypes(kind: PriceSubject['kind'], hasPerm: (perm: string) => boolean): PriceType[] {
  const reachable: PriceType[] = kind === 'material' ? ['MATERIAL'] : kind === 'style' ? ['PROCESS'] : ['MATERIAL', 'PROCESS']
  return reachable.filter((type) => hasPerm(EDIT_PERM[type]) && hasPerm(priceReadPermission(type)))
}

/**
 * 今天，2026-11-15。按东八区算，和后端取现价、生效日期默认值的"今天"是同一天
 * （PRD §8 时间一律东八区；这台电脑设成别的时区时，本地的今天可能差一天）
 */
export function today(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
}

/** 生效日期的短写法：今年的 11-15，别的年份 2025-11-15（和后端提示里的写法一样） */
export function shortDay(day: string | null | undefined): string {
  if (!day) return ''
  return day.startsWith(today().slice(0, 5)) ? day.slice(5) : day
}

/** SKU 的叫法：颜色 · 规格，都没有就用 SKU 码 */
export function skuLabel(row: Pick<PriceRow, 'skuCode' | 'skuColorName' | 'skuSpec'>): string {
  return [row.skuColorName, row.skuSpec].filter(Boolean).join(' · ') || row.skuCode || ''
}

/** 款式的叫法；款式被删掉的不会有（有价格的款式删不掉），"款式 #id" 只是兜底 */
export function styleLabel(row: Pick<PriceRow, 'styleId' | 'styleCode' | 'styleName'>): string {
  return row.styleCode ? `${row.styleCode} ${row.styleName ?? ''}`.trim() : `款式 #${row.styleId}`
}

/** 涨跌百分比，一位小数；没有上一条是 null */
export function changePct(price: number, prev: number | null | undefined): number | null {
  if (prev == null || Number(prev) === 0) return null
  return Math.round(((Number(price) - Number(prev)) / Number(prev)) * 1000) / 10
}
