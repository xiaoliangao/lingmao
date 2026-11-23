<script setup lang="ts">
import { computed, onActivated, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import StatusTag from '@/components/StatusTag.vue'
import TagHistoryDialog from '@/components/tags/TagHistoryDialog.vue'
import { tagApi } from '@/api/tag'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTagContext } from '@/composables/useTagContext'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { PublicTag } from '@/types/tag'
import { ApiError } from '@/utils/request'
import { ErrorCode } from '@/utils/errorCodes'
import { clientActionUuid, unknownActionResult } from '@/utils/clientAction'
import { previewTagName, savedTagInput, tagError } from '@/utils/publicTag'
import { isComposing } from '@/utils/ime'

const user = useUserStore()
const canRead = computed(() => user.hasPerm('biz:tag:list'))
const canManage = computed(() => user.hasPerm('biz:tag:manage'))
const keyword = ref('')
const rows = ref<PublicTag[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const request = useLatestRequest({ cancelOnDeactivate: true })
const refreshRequest = useLatestRequest({ cancelOnDeactivate: true })
const actionVisible = ref(false)
const target = shallowRef<PublicTag | null>(null)
const operation = ref<'rename' | 'archive'>('rename')
const name = ref('')
const actionError = ref<string | null>(null)
const blocked = ref(false)
const busy = ref(false)
const pending = shallowRef<{ key: string; tag: PublicTag; operation: 'rename' | 'archive'; name: string } | null>(null)
const historyVisible = ref(false)
const historyTag = shallowRef<PublicTag | null>(null)
let writeController: AbortController | undefined
const context = useTagContext(() => {
  request.cancel(); refreshRequest.cancel(); writeController?.abort()
  rows.value = []; loading.value = false; error.value = null; actionVisible.value = false; target.value = null
  pending.value = null; busy.value = false; actionError.value = null; historyVisible.value = false; historyTag.value = null
})
const guard = useUnsavedChanges(() => ({ name: name.value }), actionVisible, { scope: () => null })
async function load() {
  request.cancel(); rows.value = []; error.value = null; loading.value = false
  if (!canRead.value) { error.value = '当前没有读取公共标签库的权限'; return }
  const read = request.begin(), current = context.capture()
  loading.value = true
  try {
    const result = await tagApi.options({ keyword: keyword.value, size: 100 }, { signal: read.signal, silent: true })
    if (!read.current() || !current() || !canRead.value) return
    rows.value = result
  } catch (cause) {
    if (read.current() && current()) error.value = tagError(cause)
  } finally { if (read.current() && current()) loading.value = false }
}
watch(canRead, load, { immediate: true })
let activated = false
onActivated(() => { if (activated) void load(); activated = true })
function showHistory(tag: PublicTag) { historyTag.value = tag; historyVisible.value = true }
function rename(tag: PublicTag) {
  if (!canManage.value || tag.archived) return
  target.value = { ...tag }; operation.value = 'rename'; name.value = tag.name
  actionError.value = null; blocked.value = false; pending.value = null; actionVisible.value = true
}
async function archive(tag: PublicTag) {
  if (!canManage.value || tag.archived || busy.value) return
  const current = context.capture()
  try {
    await ElMessageBox.confirm('归档后不能再添加到其他对象；已有标签关联和记录保留。归档后不能恢复。', '归档 #' + tag.name, {
      confirmButtonText: '归档标签', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  if (!current() || !canManage.value) return
  target.value = { ...tag }; operation.value = 'archive'; name.value = tag.name
  actionError.value = null; blocked.value = false; pending.value = null; actionVisible.value = true
  await save()
}
async function save() {
  if (busy.value || !canRead.value || !canManage.value || !target.value || target.value.archived || blocked.value) return
  if (operation.value === 'rename') {
    try { previewTagName(savedTagInput(name.value)) } catch (cause) {
      actionError.value = cause instanceof Error ? cause.message : '名称不合法'; return
    }
  }
  const current = context.capture()
  const action = pending.value ?? { key: clientActionUuid(), tag: { ...target.value }, operation: operation.value, name: savedTagInput(name.value) }
  pending.value = action; busy.value = true; actionError.value = null
  writeController = new AbortController()
  try {
    const options = { idempotencyKey: action.key, signal: writeController.signal, silent: true }
    const result = action.operation === 'rename'
      ? await tagApi.rename(action.tag.id, { version: action.tag.version, name: action.name }, options)
      : await tagApi.archive(action.tag.id, action.tag.version, options)
    if (!current()) return
    target.value = result; pending.value = null; guard.markSaved(); actionVisible.value = false
    historyTag.value = result
    ElMessage.success(action.operation === 'archive' ? '标签已归档，历史关联保留' : '标签已重命名')
    await load()
  } catch (cause) {
    if (!current()) return
    actionError.value = tagError(cause)
    const uncertain = cause instanceof ApiError ? cause.transient || unknownActionResult(cause.code) : true
    if (!uncertain) pending.value = null
    blocked.value = cause instanceof ApiError && [403, 404, ErrorCode.STATE_CHANGED].includes(cause.code)
  } finally { if (current()) busy.value = false }
}
async function reloadTarget() {
  if (!target.value || busy.value || !canRead.value) return
  const read = refreshRequest.begin(), current = context.capture(), id = target.value.id
  busy.value = true
  try {
    const result = await tagApi.options({ size: 1, includeIds: [id] }, { signal: read.signal, silent: true })
    if (!read.current() || !current() || !actionVisible.value || target.value?.id !== id) return
    const latest = result.find((tag) => tag.id === id)
    if (!latest) throw new ApiError(404, '标签已不可见')
    target.value = latest; pending.value = null; blocked.value = latest.archived
    actionError.value = latest.archived ? '此标签已归档，当前输入保留，不能继续修改'
      : '已读取最新版本：#' + latest.name + '。当前输入仍保留，请核对后再提交'
  } catch (cause) {
    if (read.current() && current()) actionError.value = tagError(cause)
  } finally { if (read.current() && current()) busy.value = false }
}
watch(actionVisible, (visible) => { if (!visible) refreshRequest.cancel() })
</script>

<template>
  <div>
    <PageHeader title="公共标签库" sub="标签在业务详情中添加；新名称保存后自动进入公共库。" />
    <FilterBar @search="load">
      <template #left><span class="dim">当前可用标签</span></template>
      <template #right>
        <el-input v-model="keyword" class="tag-search" placeholder="按标签名前缀搜索" aria-label="搜索公共标签" clearable
          @keyup.enter="!isComposing($event) && load()" @clear="load" />
        <el-button :disabled="loading || !canRead" @click="load">查询</el-button>
      </template>
    </FilterBar>
    <p class="bound-note dim">每次最多显示 100 个当前可用标签；{{ rows.length === 100 ? '当前已达显示上限，请缩小名称前缀。' : '可输入名称前缀继续查找。' }}归档标签在原对象上保留，可从原对象查看记录。</p>
    <div v-if="error" class="page">
      <el-alert :title="error" type="error" :closable="false"><el-button v-if="canRead" size="small" @click="load">重试</el-button></el-alert>
    </div>
    <el-table v-else v-loading="loading" :data="rows" row-key="id" empty-text="没有符合此前缀的可用标签" scrollbar-always-on>
      <el-table-column label="标签" min-width="280"><template #default="{ row }"><span class="tag-name">#{{ row.name }}</span></template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }"><StatusTag :tone="row.archived ? 'off' : 'active'">{{ row.archived ? '已归档' : '可用' }}</StatusTag></template></el-table-column>
      <el-table-column label="操作" width="250" fixed="right" align="right" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <el-button link @click="showHistory(row as PublicTag)">记录</el-button>
          <el-button v-if="canManage && !row.archived" link @click="rename(row as PublicTag)">重命名</el-button>
          <el-button v-if="canManage && !row.archived" link :disabled="busy" @click="archive(row as PublicTag)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="actionVisible" :title="operation === 'rename' ? '重命名公共标签' : '归档公共标签'" width="480px" :close-on-click-modal="false" :before-close="guard.beforeClose">
      <p v-if="target" class="dim">当前记录：#{{ target.name }}</p>
      <el-alert v-if="!canManage || !canRead" title="当前没有管理权限，输入已保留" type="warning" :closable="false" />
      <el-alert v-if="actionError" :title="actionError" :type="blocked ? 'error' : 'warning'" :closable="false">
        <span v-if="pending">结果还未确认，重试沿用同一笔提交。</span>
        <el-button v-if="!pending && canRead" size="small" :disabled="busy" @click="reloadTarget">重新读取并核对</el-button>
      </el-alert>
      <el-form v-if="operation === 'rename'" label-position="top" class="rename-form">
        <el-form-item label="标签名称">
          <el-input v-model="name" aria-label="标签新名称" maxlength="1023" :disabled="busy || !!pending || !canManage || !!target?.archived"
            @keyup.enter="!isComposing($event) && save()" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="actionVisible = false">取消</el-button>
        <el-button :type="operation === 'archive' ? 'danger' : 'primary'" :loading="busy" :disabled="!canManage || !canRead || blocked || !!target?.archived" @click="save">
          {{ pending ? '重试原提交' : operation === 'archive' ? '归档标签' : '保存名称' }}
        </el-button>
      </template>
    </el-dialog>
    <TagHistoryDialog v-model="historyVisible" :tag="historyTag" />
  </div>
</template>

<style scoped>
.tag-search { width: min(300px, 50vw); }
.tag-name { color: var(--lm-accent-2); }
.bound-note { padding: 0 24px; font-size: 12px; line-height: 1.7; }
.rename-form { margin-top: 12px; }
</style>
