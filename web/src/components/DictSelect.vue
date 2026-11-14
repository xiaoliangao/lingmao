<script setup lang="ts">
import { useDict } from '@/composables/useDict'

/**
 * 字典下拉框：按字典类型编码取启用的选项，v-model 是选项的值。能输入文字筛选（下拉一律能搜，和原型的 Pick 一样）。
 * el-select 的其他属性（clearable、multiple、placeholder、disabled……）直接写在它上面，会落到里面的 el-select 上
 *
 *   <DictSelect v-model="form.unit" type="material_unit" clearable />
 */
const props = defineProps<{ type: string }>()
const model = defineModel<string | string[] | null>()
const { options, loading } = useDict(() => props.type)
</script>

<template>
  <el-select v-model="model" :loading="loading" filterable placeholder="请选择" no-match-text="没有匹配的选项" no-data-text="这个字典还没有选项，找管理员在字典管理里添加">
    <el-option v-for="option in options" :key="option.value" :label="option.label" :value="option.value" />
  </el-select>
</template>
