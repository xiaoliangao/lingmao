<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, reactive } from 'vue'
import { FILTER_BAR, type FilterEntry } from './filterBar'

/**
 * 筛选弹层里的一个条件，只能放在 FilterBar 里：上面一行标题，下面放控件。
 * text 是选中的值怎么念（空串或 null = 没选）；选了就在"筛选"按钮旁边出一个小标签，点小标签的 × 触发 clear。
 * 用法见 FilterBar.vue 开头
 */
const props = defineProps<{ label: string; text?: string | null }>()
const emit = defineEmits<{ clear: [] }>()

const bar = inject(FILTER_BAR, null)
const entry: FilterEntry = reactive({
  label: computed(() => props.label),
  text: computed(() => props.text ?? ''),
  clear: () => emit('clear'),
})

onMounted(() => bar?.register(entry))
onBeforeUnmount(() => bar?.unregister(entry))
</script>

<template>
  <div class="filter-item">
    <span class="label">{{ label }}</span>
    <slot />
  </div>
</template>

<style scoped>
.filter-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.label {
  font-size: 12px;
  font-weight: 500;
  color: var(--lm-muted);
}

.filter-item :deep(.el-input),
.filter-item :deep(.el-select),
.filter-item :deep(.el-date-editor) {
  width: 100%;
}
</style>
