<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { sampleApi } from '@/api/sample'
import type { SampleRef } from '@/types/sample'
import { useEntityContext, useEntityRead } from '@/composables/useEntityContext'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import CompanySelect from '@/components/doc/CompanySelect.vue'
const props = defineProps<{ styleId: string }>()
const user = useUserStore(), canRead = computed(() => user.hasPerm('dev:sample:list'))
const companyId = ref<string | null>(user.defaultCompanyId), source = ref<SampleRef | null>(null)
const loading = ref(false), error = ref<string | null>(null), readSource = useEntityRead()
let resumeOnActivate = false
useEntityContext(() => { resumeOnActivate = false; readSource.cancel(); companyId.value = null; source.value = null; loading.value = false; error.value = null })
async function load() {
  resumeOnActivate = false
  const read = readSource.begin(), company = companyId.value
  loading.value = false; error.value = null; source.value = null
  if (!canRead.value || !company) return
  loading.value = true
  try { const result = await sampleApi.byStyle(props.styleId, company, { signal: read.signal, silent: true }); if (read.current()) source.value = result }
  catch (cause) { if (read.current()) error.value = cause instanceof ApiError && [403, 404].includes(cause.code) ? '无权读取当前主体的样衣来源' : '样衣来源读取失败，请重试' }
  finally { if (read.current()) loading.value = false }
}
watch([companyId, () => props.styleId, canRead], load)
onMounted(load)
onDeactivated(() => { resumeOnActivate = loading.value; loading.value = false })
onActivated(() => { if (resumeOnActivate) void load() })
</script>
<template>
  <section v-if="canRead" class="sample-origin" aria-label="来源样衣">
    <div class="origin-head"><h4>来源样衣</h4><CompanySelect v-model="companyId" placeholder="选择主体" aria-label="来源样衣主体" /></div>
    <span v-if="!companyId" class="dim">选择主体后读取来源样衣。</span>
    <span v-else-if="loading" class="dim" role="status">正在读取来源样衣…</span>
    <div v-else-if="error" role="alert">{{ error }} <el-button size="small" @click="load">重试</el-button></div>
    <router-link v-else-if="source" :to="`/samples/${source.id}?companyId=${source.companyId}`"><span class="code">{{ source.code }}</span> · {{ source.name }}</router-link>
    <span v-else class="dim">当前主体内没有可见的来源样衣。</span>
  </section>
</template>
<style scoped>
.sample-origin { margin: 12px 0; padding-top: 12px; border-top: 1px solid var(--lm-line-soft); }
.origin-head { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; }.origin-head h4 { margin: 0; font-size: 13px; }.origin-head :deep(.el-select) { width: 180px; }
</style>
