<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { DocRef } from '@/types/biz'
import StatusTag from '@/components/StatusTag.vue'
import { docRoute } from '@/utils/docRoute'

/**
 * 详情页的"下游单据"页签（docs/api/README.md §4.3、§4.5）：类型、单号、负责人、状态，点单号按 docRoute 去那张单。
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

const router = useRouter()
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

function open(row: DocRef) {
  const to = docRoute(row)
  if (to) router.push(to)
}

defineExpose({ reload })
</script>

<template>
  <el-table v-loading="loading" :data="rows" row-key="id" :empty-text="emptyText ?? '还没有关联的下游单据'">
    <el-table-column label="单据类型" prop="typeLabel" width="140" />
    <el-table-column label="单号" min-width="180">
      <template #default="{ row }">
        <button type="button" class="doc-link code" @click="open(row as DocRef)">{{ row.no }}</button>
      </template>
    </el-table-column>
    <el-table-column label="负责人" min-width="120">
      <template #default="{ row }">{{ row.ownerName ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="120">
      <template #default="{ row }">
        <StatusTag :tone="row.statusTone">{{ row.statusLabel }}</StatusTag>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.doc-link {
  padding: 0;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.doc-link:hover {
  text-decoration: underline;
}
</style>
