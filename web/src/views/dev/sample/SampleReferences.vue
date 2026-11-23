<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { sampleApi } from '@/api/sample'
import type { DocRef } from '@/types/biz'
import { docRoute } from '@/utils/docRoute'
import { useUserStore } from '@/stores/user'
import StatusTag from '@/components/StatusTag.vue'
import SampleFeedback from './SampleFeedback.vue'
import { sampleError, useSampleRead } from './sampleScope'
const props = defineProps<{ sampleId: string; companyId: string }>()
const user = useUserStore(), allowed = computed(() => user.hasPerm('dev:sample:list'))
const rows = ref<DocRef[]>([]), loading = ref(false), error = ref<string | null>(null)
let resumeOnActivate = false
const reads = useSampleRead(() => { resumeOnActivate = false; rows.value = []; loading.value = false; error.value = null })
async function load() {
  resumeOnActivate = false
  const read = reads.begin(); error.value = null; rows.value = []; loading.value = false
  if (!allowed.value) return
  loading.value = true
  try { const result = await sampleApi.references(props.sampleId, props.companyId, { signal: read.signal, silent: true }); if (read.current()) rows.value = result }
  catch (cause) { if (read.current()) error.value = sampleError(cause) }
  finally { if (read.current()) loading.value = false }
}
watch([() => props.sampleId, () => props.companyId, allowed], load)
onMounted(load)
onDeactivated(() => { resumeOnActivate = loading.value; loading.value = false })
onActivated(() => { if (resumeOnActivate) void load() })
</script>
<template>
  <SampleFeedback :loading="loading" :error="error" label="下游引用" @retry="load" />
  <el-table v-if="allowed && !loading && !error" :data="rows" row-key="id" empty-text="还没有可见的关联单据">
    <el-table-column label="单据类型" prop="typeLabel" width="140" />
    <el-table-column label="单号" min-width="190"><template #default="{ row }"><router-link v-if="docRoute(row as DocRef)" :to="docRoute(row as DocRef)!" class="code">{{ row.no }}</router-link><span v-else class="code">{{ row.no }}</span></template></el-table-column>
    <el-table-column label="负责人" prop="ownerName" min-width="100" />
    <el-table-column label="状态" width="125"><template #default="{ row }"><StatusTag :tone="row.statusTone">{{ row.statusLabel }}</StatusTag></template></el-table-column>
  </el-table>
</template>
