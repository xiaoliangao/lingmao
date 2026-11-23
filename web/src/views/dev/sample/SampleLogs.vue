<script setup lang="ts">
import { computed, ref } from 'vue'
import { sampleApi } from '@/api/sample'
import DocLogList from '@/components/DocLogList.vue'
import { SAMPLE_LOG_ACTIONS, SAMPLE_LOG_STATUS } from './common'
import { useSampleContext } from './sampleScope'
const props = defineProps<{ sampleId: string; companyId: string }>()
const page = ref(1), total = ref<number | null>(null), context = useSampleContext(() => { page.value = 1; total.value = null })
const load = computed(() => {
  const id = props.sampleId, companyId = props.companyId, currentPage = page.value
  return async () => {
    const current = context.capture()
    const response = await sampleApi.logs(id, companyId, currentPage, 20, { silent: true })
    if (current() && props.sampleId === id && props.companyId === companyId && currentPage === page.value) total.value = response.total
    return response.list
  }
})
</script>
<template>
  <DocLogList compact :load="load" :actions="SAMPLE_LOG_ACTIONS" :statuses="SAMPLE_LOG_STATUS" />
  <el-pagination v-if="total != null && total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" :pager-count="5" small class="log-pages" />
</template>
<style scoped>.log-pages { flex-wrap: wrap; margin-top: 12px; justify-content: center; }</style>
