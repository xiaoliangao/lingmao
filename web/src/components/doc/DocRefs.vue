<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { onMounted, ref } from 'vue'
import type { DocRef } from '@/types/biz'
import DocRefTable from './DocRefTable.vue'

/**
 * 详情页的"下游单据"页签（docs/api/README.md §4.3、§4.5）：表格在 DocRefTable。
 *
 *   const loadRefs = () => orderApi.references(id)
 *   <el-tab-pane label="下游单据" name="down" lazy>
 *     <DocRefs ref="refs" :load="loadRefs" empty-text="还没有下游单据：审核后点「排单」「算料」" />
 *   </el-tab-pane>
 *
 * load 在 script 里定义好再传（见 DocLogList）。详情页做完排单这类动作要刷新：refs.value?.reload()
 */
const props = defineProps<{
  load: () => Promise<DocRef[]>
  emptyText?: string
}>()

const rows = ref<DocRef[]>([])
const loading = ref(false)

const reloadRequest = useLatestRequest()
async function reload() {
  const read = reloadRequest.begin()
  loading.value = true
  try {
    const fetched1 = await read.run(() => props.load())
    if (!read.current()) return
    rows.value = fetched1
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

onMounted(reload)

defineExpose({ reload })
</script>

<template>
  <DocRefTable :rows="rows" :loading="loading" :empty-text="emptyText" />
</template>
