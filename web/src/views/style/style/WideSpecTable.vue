<script setup lang="ts">
import { computed } from 'vue'
import VirtualDataTable, { type VirtualColumn } from '@/components/VirtualDataTable.vue'
import type { SpecPointForm, TechPackDetail } from '@/types/techpack'
import { formatQty } from '@/utils/format'

/** 只负责超宽表渲染；行仍是父表单中的原对象，默认方法、档差生成和保存沿用资料包逻辑。 */
const props = defineProps<{
  rows: SpecPointForm[]
  sizes: string[]
  baseSize: string
  pointOptions: TechPackDetail['pointOptions']
  editable: boolean
}>()
const emit = defineEmits<{
  pointPicked: [row: SpecPointForm]
  move: [index: number, step: -1 | 1]
  remove: [index: number]
}>()
let nextKey = 0
const rowKeys = new WeakMap<SpecPointForm, string>()
const keyedRows = computed(() => props.rows.map((form) => {
  let key = rowKeys.get(form)
  if (!key) { key = `spec-${++nextKey}`; rowKeys.set(form, key) }
  return { key, form }
}))
const columns = computed<VirtualColumn[]>(() => [
  { key: 'seq', label: '#', width: 44, fixed: 'left', align: 'right' },
  { key: 'point', label: '部位', width: 150, fixed: 'left' },
  { key: 'method', label: '测量方法', width: 160 },
  { key: 'tolerance', label: '公差 ±', width: 90, align: 'right' },
  { key: 'grade', label: '档差', width: 84, align: 'right' },
  ...props.sizes.map((size) => ({ key: `size:${size}`, label: size === props.baseSize ? `${size}（基码）` : size, width: 104, align: 'right' as const, className: size === props.baseSize ? 'base-col' : undefined })),
  { key: 'remark', label: '备注', width: 160 },
  ...(props.editable ? [{ key: 'actions', label: '', width: 110, fixed: 'right' as const }] : []),
])
</script>
<template>
  <VirtualDataTable class="wide-spec-table" :rows="keyedRows" :columns="columns" row-key="key" empty-text="还没有尺寸指示（不是必填）">
    <template #seq="{ rowIndex }"><span class="num">{{ rowIndex + 1 }}</span></template>
    <template #point="{ row }">
      <el-select v-if="editable" v-model="row.form.point" size="small" filterable allow-create default-first-option placeholder="选或填部位" @change="emit('pointPicked', row.form)">
        <el-option v-for="option in pointOptions" :key="option.label" :label="option.label" :value="option.label" />
      </el-select>
      <span v-else>{{ row.form.point }}</span>
    </template>
    <template #method="{ row }">
      <el-input v-if="editable" v-model="row.form.method" size="small" />
      <span v-else>{{ row.form.method || '—' }}</span>
    </template>
    <template #tolerance="{ row }">
      <el-input-number v-if="editable" v-model="row.form.toleranceCm" size="small" :min="0" :precision="1" :controls="false" />
      <span v-else class="num">{{ row.form.toleranceCm == null ? '—' : formatQty(row.form.toleranceCm) }}</span>
    </template>
    <template #grade="{ row }">
      <el-input-number v-if="editable" v-model="row.form.gradeCm" size="small" :precision="1" :controls="false" />
      <span v-else class="num">{{ row.form.gradeCm == null ? '—' : formatQty(row.form.gradeCm) }}</span>
    </template>
    <template v-for="size in sizes" :key="size" #[`size:${size}`]="{ row, rowIndex }">
      <el-input-number v-if="editable" v-model="row.form.values[size]" :data-testid="`spec-size-${rowIndex}-${size}`" size="small" :precision="1" :controls="false" />
      <span v-else class="num">{{ row.form.values[size] == null ? '—' : formatQty(row.form.values[size]) }}</span>
    </template>
    <template #remark="{ row }">
      <el-input v-if="editable" v-model="row.form.remark" size="small" />
      <span v-else>{{ row.form.remark || '' }}</span>
    </template>
    <template #actions="{ rowIndex }">
      <el-button link size="small" :disabled="rowIndex === 0" aria-label="上移" @click="emit('move', rowIndex, -1)"><el-icon><Top /></el-icon></el-button>
      <el-button link size="small" :disabled="rowIndex === rows.length - 1" aria-label="下移" @click="emit('move', rowIndex, 1)"><el-icon><Bottom /></el-icon></el-button>
      <el-button link type="danger" size="small" @click="emit('remove', rowIndex)">删</el-button>
    </template>
  </VirtualDataTable>
</template>
<style scoped>
.wide-spec-table :deep(.el-input-number), .wide-spec-table :deep(.el-select) { width: 100%; }
.wide-spec-table :deep(.base-col) { background: var(--lm-accent-bg); }
</style>
