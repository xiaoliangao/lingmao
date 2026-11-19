<script setup lang="ts">
import { computed } from 'vue'
import { useProductLines } from '@/composables/useProductLines'

/**
 * 品类线下拉（RFC 013 §2）：只列我能看的、字典里启用的；已经选着的停用了也列出来、标"（已停用）"。
 * 列表的筛选、手工建采购单用；只有一条线的人页面上不放它（看 useProductLines().multi）。
 * el-select 的其他属性（clearable、placeholder、disabled……）写在它上面
 *
 *   <LineSelect v-model="query.productLine" clearable placeholder="全部品类线" />
 */
const model = defineModel<string | null | undefined>()
const lines = useProductLines()

const shown = computed(() => {
  const options = lines.options.value.map((o) => ({ value: o.value, label: o.label }))
  if (model.value && !options.some((o) => o.value === model.value)) {
    options.push({ value: model.value, label: `${lines.labelOf(model.value)}（已停用）` })
  }
  return options
})
</script>

<template>
  <el-select v-model="model" :loading="lines.loading.value" filterable placeholder="请选品类线" no-match-text="没有匹配的品类线">
    <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
  </el-select>
</template>
