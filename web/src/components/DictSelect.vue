<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SelectInstance } from 'element-plus'
import { useDict } from '@/composables/useDict'

/**
 * 字典下拉框：按字典类型编码取选项，v-model 是选项的值。能输入文字筛选（下拉一律能搜，和原型的 Pick 一样）。
 * 只列启用的选项；已经选中的值要是停用了，也列在里面、后面标"（已停用）"：编辑旧记录时照样显示文字，不会只剩一个值，
 * 想换成别的就换，不换也能原样保存。
 * el-select 的其他属性（clearable、multiple、placeholder、disabled……）直接写在它上面，会落到里面的 el-select 上。
 * #footer 插槽放在下拉的底下（如"新增物料类型"）；加了选项以后调 useDict.ts 的 reloadDict(type)，这里会重新取
 *
 *   <DictSelect v-model="form.unit" type="material_unit" clearable />
 */
const props = defineProps<{ type: string }>()
const model = defineModel<string | string[] | null>()
const { options, loading } = useDict(() => props.type)
const select = ref<SelectInstance>()

/** 收起下拉：#footer 里的按钮要弹对话框时先调它 */
defineExpose({ blur: () => select.value?.blur() })

const shown = computed(() => {
  const value = model.value
  const selected = new Set(Array.isArray(value) ? value : value == null ? [] : [value])
  return options.value
    .filter((option) => option.status === 1 || selected.has(option.value))
    .map((option) => ({
      value: option.value,
      label: option.status === 1 ? option.label : `${option.label}（已停用）`,
    }))
})
</script>

<template>
  <el-select ref="select" v-model="model" :loading="loading" filterable placeholder="请选择" no-match-text="没有匹配的选项" no-data-text="这个字典还没有选项，找管理员在字典管理里添加">
    <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </el-select>
</template>
