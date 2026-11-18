<script setup lang="ts">
import { computed } from 'vue'
import type { MatrixColor, MatrixCell } from './SkuMatrix.vue'
import { formatQty } from '@/utils/format'
const props = defineProps<{
  color: MatrixColor
  sizes: string[]
  cells: Map<string, MatrixCell>
  quantities: Record<string, number>
  readonly: boolean
  max?: Record<string, number> | ((id: string) => number | null | undefined)
  error?: (id: string, qty: number) => string | null
  sub?: (id: string) => string | null
}>()
const emit = defineEmits<{ change: [id: string, value: number] }>()
const cellOf = (size: string) => props.cells.get(`${props.color.id}|${size}`)
const qtyOf = (id: string) => props.quantities[id] ?? 0
const rowTotal = computed(() => props.sizes.reduce((total, size) => { const cell = cellOf(size); return total + (cell ? qtyOf(cell.id) : 0) }, 0))
function problemOf(id: string): string | null {
  const max = typeof props.max === 'function' ? props.max(id) : props.max?.[id]
  if (max != null && qtyOf(id) > max) return `最多 ${formatQty(max)} 件`
  return props.error?.(id, qtyOf(id)) ?? null
}
function titleOf(cell: MatrixCell): string | undefined {
  if (props.color.orderable === false) return props.color.reason ?? '这个颜色不能下'
  if (cell.status === 0) return '这个 SKU 已停用'
  return problemOf(cell.id) ?? undefined
}
function input(id: string, event: Event) {
  const target = event.target as HTMLInputElement
  const value = target.value.trim()
  if (!/^\d{0,7}$/.test(value)) { target.value = qtyOf(id) ? String(qtyOf(id)) : ''; return }
  emit('change', id, value === '' ? 0 : Number(value))
}
</script>
<template>
  <tr :class="{ off: color.orderable === false }" :title="color.orderable === false ? color.reason ?? undefined : undefined">
    <td class="color-col"><span class="swatch" :style="{ background: color.hex ?? undefined }" /><span>{{ color.name }}</span><span v-if="color.code" class="code dim">{{ color.code }}</span><span v-if="color.orderable === false && color.reason" class="reason">{{ color.reason }}</span></td>
    <td v-for="size in sizes" :key="size" class="num cell" :class="{ bad: cellOf(size) && problemOf(cellOf(size)!.id), stopped: cellOf(size)?.status === 0 }" :title="cellOf(size) ? titleOf(cellOf(size)!) : undefined">
      <template v-if="cellOf(size)">
        <input v-if="!readonly && color.orderable !== false && cellOf(size)!.status !== 0" class="qty-input num" inputmode="numeric" :aria-label="`${color.name} ${size} 件数`" :value="qtyOf(cellOf(size)!.id) || ''" placeholder="0" @input="input(cellOf(size)!.id, $event)" />
        <span v-else class="qty-text">{{ qtyOf(cellOf(size)!.id) ? formatQty(qtyOf(cellOf(size)!.id)) : '' }}</span>
        <span v-if="sub?.(cellOf(size)!.id)" class="sub">{{ sub(cellOf(size)!.id) }}</span>
      </template>
      <span v-else class="dim">—</span>
    </td>
    <td class="num total-col">{{ formatQty(rowTotal) }}</td>
  </tr>
</template>
