<script setup lang="ts">
import { computed } from 'vue'
import type { MatrixCell, MatrixColor } from './SkuMatrix.vue'
import { formatQty } from '@/utils/format'

const props = defineProps<{
  cell?: MatrixCell
  color: MatrixColor
  size: string
  qty: number
  readonly: boolean
  maxDigits: 7 | 8
  problem: string | null
  description: string | null
}>()
const emit = defineEmits<{ change: [id: string, value: number] }>()
const title = computed(() => {
  if (!props.cell) return undefined
  if (props.color.orderable === false) return props.color.reason ?? '这个颜色不能下'
  if (props.cell.status === 0) return '这个 SKU 已停用'
  return props.problem ?? undefined
})

function input(event: Event) {
  if (!props.cell) return
  const target = event.target as HTMLInputElement
  const value = target.value.trim()
  if (!/^\d*$/.test(value) || value.length > props.maxDigits) {
    target.value = props.qty ? String(props.qty) : ''
    return
  }
  emit('change', props.cell.id, value === '' ? 0 : Number(value))
}
</script>

<template>
  <td class="num cell" :class="{ bad: problem, stopped: cell?.status === 0 }" :title="title">
    <template v-if="cell">
      <input v-if="!readonly && color.orderable !== false && cell.status !== 0" class="qty-input num" inputmode="numeric" :aria-label="`${color.name} ${size} 件数`" :value="qty || ''" placeholder="0" @input="input" />
      <span v-else class="qty-text">{{ qty ? formatQty(qty) : '' }}</span>
      <span v-if="description" class="sub">{{ description }}</span>
    </template>
    <span v-else class="dim">—</span>
  </td>
</template>
