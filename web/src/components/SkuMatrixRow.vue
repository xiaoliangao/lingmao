<script setup lang="ts">
import { computed } from 'vue'
import SkuMatrixCell from './SkuMatrixCell.vue'
import type { MatrixColor, MatrixCell } from './SkuMatrix.vue'
import { formatQty } from '@/utils/format'
const props = defineProps<{
  color: MatrixColor
  sizes: string[]
  cells: Map<string, MatrixCell>
  quantities: Record<string, number>
  readonly: boolean
  maxDigits: 7 | 8
  max?: Record<string, number> | ((id: string) => number | null | undefined)
  error?: (id: string, qty: number) => string | null
  sub?: (id: string) => string | null
}>()
const emit = defineEmits<{ change: [id: string, value: number] }>()
const cellOf = (size: string) => props.cells.get(`${props.color.id}|${size}`)
const qtyOf = (id: string) => props.quantities[id] ?? 0
const rowTotal = computed(() => props.sizes.reduce((total, size) => { const cell = cellOf(size); return total + (cell ? qtyOf(cell.id) : 0) }, 0))
function problemOf(id?: string): string | null {
  if (!id) return null
  const max = typeof props.max === 'function' ? props.max(id) : props.max?.[id]
  if (max != null && qtyOf(id) > max) return `最多 ${formatQty(max)} 件`
  return props.error?.(id, qtyOf(id)) ?? null
}
function descriptionOf(id?: string): string | null {
  return id ? props.sub?.(id) ?? null : null
}
const forwardChange = (id: string, value: number) => emit('change', id, value)
</script>
<template>
  <tr :class="{ off: color.orderable === false }" :title="color.orderable === false ? color.reason ?? undefined : undefined">
    <td class="color-col"><span class="swatch" :style="{ background: color.hex ?? undefined }" /><span>{{ color.name }}</span><span v-if="color.code" class="code dim">{{ color.code }}</span><span v-if="color.orderable === false && color.reason" class="reason">{{ color.reason }}</span></td>
    <SkuMatrixCell v-for="size in sizes" :key="size" :cell="cellOf(size)" :color="color" :size="size" :qty="cellOf(size) ? qtyOf(cellOf(size)!.id) : 0" :readonly="readonly" :max-digits="maxDigits" :problem="problemOf(cellOf(size)?.id)" :description="descriptionOf(cellOf(size)?.id)" @change="forwardChange" />
    <td class="num total-col edge-total">{{ formatQty(rowTotal) }}</td>
  </tr>
</template>
