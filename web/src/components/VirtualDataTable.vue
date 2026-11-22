<script setup lang="ts" generic="Row">
import { computed, h, type VNode } from 'vue'
import { ElAutoResizer, ElTableV2, TableV2Alignment, TableV2FixedDir, type Column } from 'element-plus'

export interface VirtualColumn {
  key: string
  label: string
  width: number
  fixed?: 'left' | 'right'
  align?: 'left' | 'center' | 'right'
  className?: string
}
const props = withDefaults(defineProps<{
  rows: Row[]
  columns: VirtualColumn[]
  rowKey?: string
  height?: number
  emptyText?: string
}>(), { rowKey: 'id', height: 480, emptyText: '没有符合条件的数据' })
const slots = defineSlots<{ [name: string]: (props: { row: Row; rowIndex: number }) => VNode[] }>()
const columns = computed<Column<unknown>[]>(() => props.columns.map((column) => ({
  key: column.key,
  dataKey: column.key,
  title: column.label,
  width: column.width,
  class: column.className,
  headerClass: column.className,
  fixed: column.fixed === 'left' ? TableV2FixedDir.LEFT : column.fixed === 'right' ? TableV2FixedDir.RIGHT : undefined,
  align: column.align === 'center' ? TableV2Alignment.CENTER : column.align === 'right' ? TableV2Alignment.RIGHT : TableV2Alignment.LEFT,
  cellRenderer: ({ rowData, rowIndex }: { rowData: unknown; rowIndex: number }) => {
    const row = rowData as Row
    const content = slots[column.key]?.({ row, rowIndex })
    const value = (rowData as Record<string, unknown>)[column.key]
    const text = value == null ? '—' : String(value)
    return h('div', { class: 'virtual-cell', title: content ? undefined : text }, content ?? text)
  },
})))
</script>
<template>
  <div class="virtual-data-table" :style="{ height: `${height}px` }">
    <ElAutoResizer>
      <template #default="{ width, height: availableHeight }">
        <ElTableV2 :columns="columns" :data="rows" :width="width" :height="availableHeight" :row-key="rowKey" :row-height="44" :header-height="40" :cache="3" fixed>
          <template #empty><span class="virtual-empty">{{ emptyText }}</span></template>
        </ElTableV2>
      </template>
    </ElAutoResizer>
  </div>
</template>
<style scoped>
.virtual-data-table { width: 100%; min-width: 0; background: var(--lm-paper); }
:deep(.virtual-cell) { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.el-table-v2__header-cell) { color: var(--lm-muted); background: var(--lm-subtle); font-weight: 500; }
:deep(.el-table-v2__row-cell) { background: var(--lm-paper); color: var(--lm-ink); }
:deep(.el-table-v2__row:hover .el-table-v2__row-cell) { background: var(--lm-subtle); }
:deep(.el-table-v2__left), :deep(.el-table-v2__right) { background: var(--lm-paper); }
.virtual-empty { color: var(--lm-muted); }
</style>
