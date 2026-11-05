<script setup lang="ts">
import { computed } from 'vue'

/**
 * 错误清单表格。1003 的每一项是 {row, column, message}，显示成"行 / 列 / 问题"三列。
 * 1001、1004、1005 带什么还没定，先按"对象数组"通用地显示：每个字段一列，不认识的字段用字段名当表头，定了再往 LABELS 里加
 */
const props = defineProps<{ items: unknown[] }>()

type Item = Record<string, unknown>

const LABELS: Record<string, string> = { row: '行', column: '列', message: '问题' }

/** 不是对象的（只有一句话）当成 message */
const rows = computed<Item[]>(() =>
  props.items.map((item) => (item !== null && typeof item === 'object' ? (item as Item) : { message: item })),
)

/** 所有行出现过的字段，按第一次出现的顺序 */
const columns = computed(() => [...new Set(rows.value.flatMap((row) => Object.keys(row)))])

function text(value: unknown) {
  if (value == null) return ''
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
</script>

<template>
  <el-table :data="rows" max-height="360" empty-text="这个错误没有带明细">
    <el-table-column
      v-for="key in columns"
      :key="key"
      :label="LABELS[key] ?? key"
      :width="key === 'row' ? 64 : undefined"
      :min-width="key === 'message' ? 240 : 100"
      :class-name="key === 'row' ? 'num' : undefined"
    >
      <template #default="{ row }">{{ text((row as Item)[key]) }}</template>
    </el-table-column>
  </el-table>
</template>
