import type { MatrixCell, MatrixColor } from '@/components/SkuMatrix.vue'
import type { FgStyleOption } from '@/types/fg'

/**
 * 成品单据画矩阵（docs/api/07 §3.2）：格子 id 就是款式 SKU id。
 * 停用的颜色、SKU 也能录（老款期初、退回的货），所以格子不带 status、不灰掉。
 *
 *   const cells = fgCells(style, (id) => onOrder.has(id))      只留订单上的、回货单上的那几格
 *   <SkuMatrix :sizes="style.sizes" :colors="fgColors(style, cells)" :skus="cells" … />
 */
export function fgCells(style: FgStyleOption, keep?: (skuId: string) => boolean): MatrixCell[] {
  return style.skus.filter((k) => !keep || keep(k.id)).map((k) => ({ id: k.id, colorId: k.colorId, sizeCode: k.sizeCode }))
}

/** 有格子的颜色才占一行 */
export function fgColors(style: FgStyleOption, cells: MatrixCell[]): MatrixColor[] {
  const used = new Set(cells.map((c) => c.colorId))
  return style.colors.filter((c) => used.has(c.id))
}

/** 有格子的尺码才占一列，按款式尺码组的顺序 */
export function fgSizes(style: FgStyleOption, cells: MatrixCell[]): string[] {
  const used = new Set(cells.map((c) => c.sizeCode))
  return style.sizes.filter((s) => used.has(s))
}
