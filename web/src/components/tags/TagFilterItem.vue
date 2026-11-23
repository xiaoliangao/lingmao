<script setup lang="ts">
import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import FilterItem from '@/components/FilterItem.vue'
import { useUserStore } from '@/stores/user'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { tagApi } from '@/api/tag'
import { TAG_LIMIT, type PublicTag } from '@/types/tag'
import { tagError } from '@/utils/publicTag'
import { onSessionChanged, sessionEpoch } from '@/utils/token'

/** FilterBar 统一触发搜索和重置页码；值始终是稳定 ID，多个标签要求同时命中。 */
const selected = defineModel<string[]>({ required: true })
const user = useUserStore()
const allowed = computed(() => user.hasPerm('biz:tag:list'))
const options = ref<PublicTag[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const request = useLatestRequest({ cancelOnDeactivate: true })
let timer: ReturnType<typeof setTimeout> | undefined
let keyword = ''
const label = (tag: PublicTag) => '#' + tag.name + (tag.archived ? `（已归档 · ${tag.id}）` : '')
const text = computed(() => selected.value.map((id) => {
  const tag = options.value.find((value) => value.id === id)
  return tag ? label(tag) : '#' + id
}).join('、'))

function invalidate(clearSelection = false) {
  clearTimeout(timer); request.cancel(); options.value = []; loading.value = false; error.value = null
  if (clearSelection) selected.value = []
}
watch([() => user.info?.id, () => user.defaultCompanyId], () => invalidate(true), { flush: 'sync' })
watch(allowed, () => invalidate(), { flush: 'sync' })
const stop = onSessionChanged(() => invalidate(true))
onBeforeUnmount(() => { clearTimeout(timer); stop() })
onDeactivated(() => { clearTimeout(timer); request.cancel(); loading.value = false })

async function search() {
  clearTimeout(timer); request.cancel(); error.value = null; loading.value = false
  if (!allowed.value) return
  const read = request.begin(), epoch = sessionEpoch()
  loading.value = true
  try {
    const result = await tagApi.filterOptions({ keyword, size: 50, includeIds: [...selected.value] }, { signal: read.signal, silent: true })
    if (!read.current() || epoch !== sessionEpoch() || !allowed.value) return
    options.value = result
  } catch (cause) {
    if (!read.current() || epoch !== sessionEpoch()) return
    error.value = tagError(cause)
  } finally { if (read.current() && epoch === sessionEpoch()) loading.value = false }
}
function schedule(value: string) {
  keyword = value; request.cancel(); loading.value = false; clearTimeout(timer)
  timer = setTimeout(() => { void search() }, 250)
}
function visible(open: boolean) { if (open) void search(); else { clearTimeout(timer); request.cancel(); loading.value = false } }
</script>

<template>
  <FilterItem v-if="allowed || selected.length" label="公共标签（同时包含）" :text="text" @clear="selected = []">
    <el-select v-model="selected" multiple clearable filterable remote :remote-method="schedule" :loading="loading"
      :multiple-limit="TAG_LIMIT" :disabled="!allowed" placeholder="选择标签，可查归档历史" aria-label="公共标签筛选"
      @visible-change="visible">
      <el-option v-for="tag in options" :key="tag.id" :value="tag.id" :label="label(tag)" />
    </el-select>
    <span class="hint">同时包含所选标签，最多 {{ TAG_LIMIT }} 个。</span>
    <span v-if="!allowed" class="error" role="alert">当前没有标签查询权限；请清空此条件。</span>
    <span v-else-if="error" class="error" role="alert">{{ error }} <el-button link size="small" @click="search">重试</el-button></span>
  </FilterItem>
</template>

<style scoped>
.hint { color: var(--lm-muted); font-size: 11.5px; }
.error { color: var(--lm-danger-text); font-size: 12px; }
</style>
