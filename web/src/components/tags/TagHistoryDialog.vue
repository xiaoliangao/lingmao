<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTagContext } from '@/composables/useTagContext'
import { tagApi } from '@/api/tag'
import type { PublicTag } from '@/types/tag'
import type { DocLog } from '@/types/biz'
import { formatDateTime } from '@/utils/format'
import { tagError } from '@/utils/publicTag'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ tag: PublicTag | null }>()
const user = useUserStore()
const allowed = computed(() => user.hasPerm('biz:tag:list'))
const rows = ref<DocLog[]>([])
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const read = useLatestRequest({ cancelOnDeactivate: true })
const context = useTagContext(() => {
  read.cancel(); rows.value = []; total.value = 0; loading.value = false; visible.value = false
})
async function load() {
  read.cancel()
  rows.value = []; error.value = null; loading.value = false
  if (!visible.value || !props.tag) return
  if (!allowed.value) { error.value = '当前没有查看公共标签记录的权限'; return }
  const current = context.capture(), request = read.begin(), id = props.tag.id
  loading.value = true
  try {
    const result = await tagApi.logs(id, { page: page.value, size: 20 }, { signal: request.signal, silent: true })
    if (!current() || !request.current() || !allowed.value) return
    rows.value = result.list; total.value = result.total
  } catch (cause) {
    if (current() && request.current()) error.value = tagError(cause)
  } finally { if (current() && request.current()) loading.value = false }
}
watch(() => [visible.value, props.tag?.id], () => { page.value = 1; total.value = 0; void load() })
watch(page, load)
watch(allowed, () => { read.cancel(); void load() })
const actions: Record<string, string> = { rename: '重命名', archive: '归档', 'replace-tags': '修改标签', create: '创建' }
</script>

<template>
  <el-dialog v-model="visible" :title="'标签记录 · #' + (tag?.name ?? '')" width="760px" append-to-body>
    <p v-if="tag?.archived" class="dim">此标签已归档，已有对象的关联保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false">
      <el-button size="small" :disabled="loading" @click="load">重试</el-button>
    </el-alert>
    <el-table v-if="!error" v-loading="loading" :data="rows" empty-text="还没有操作记录" scrollbar-always-on>
      <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createTime) }}</template></el-table-column>
      <el-table-column label="操作人" width="110"><template #default="{ row }">{{ row.operatorName ?? '系统' }}</template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{ row }">{{ actions[row.action] ?? row.action }}</template></el-table-column>
      <el-table-column label="说明" prop="remark" min-width="240" class-name="history-remark" :show-overflow-tooltip="false" />
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" :disabled="loading || !!error" layout="total, prev, pager, next" class="pagination" />
    <template #footer><el-button @click="visible = false">关闭</el-button></template>
  </el-dialog>
</template>

<style scoped>
:deep(.history-remark .cell) {
  font-family: 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Noto Sans CJK SC', 'Noto Sans SC', sans-serif;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
