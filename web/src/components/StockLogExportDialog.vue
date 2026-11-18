<script setup lang="ts">
import { computed, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { exportJobApi } from '@/api/exportJob'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { EXPORT_JOB_STATUS_LABEL, canRetryExport, exportJobErrorMessage, isExportRunning, type ExportJobView } from '@/types/exportJob'
import type { StockLogQuery } from '@/types/inventory'
import { routeId } from '@/utils/entityId'
import { unknownActionResult } from '@/utils/clientAction'
import { readExportJobHistory, rememberExportJob, type ExportJobReceipt } from '@/utils/exportJobHistory'
import { clearExportCreate, newExportCreate, readExportCreate, storeExportCreate, type ExportCreateDraft } from '@/utils/exportJobRecovery'
import { formatDateTime } from '@/utils/format'
import { isComposing } from '@/utils/ime'
import { ApiError } from '@/utils/request'
import { onSessionChanged, sessionChanged, sessionEpoch, sessionIdentity } from '@/utils/token'

const visible = ref(false)
const creating = ref(false)
const working = ref<'cancel' | 'retry' | null>(null)
const loading = ref(false)
const downloading = ref(false)
const job = ref<ExportJobView | null>(null)
const history = ref<ExportJobReceipt[]>([])
const selectedId = ref('')
const lookupId = ref('')
const error = ref('')
const errorTrace = ref<string | null>(null)
const storageWarning = ref(false)
const cancelRequested = ref(false)
const createDraft = ref<ExportCreateDraft | null>(null)
const busy = computed(() => creating.value || working.value !== null)
const latest = useLatestRequest()
const fileRequest = useLatestRequest()
let timer: ReturnType<typeof setTimeout> | undefined
let selection = 0
let alive = true

function stopReads() {
  clearTimeout(timer)
  timer = undefined
  latest.cancel()
  fileRequest.cancel()
  loading.value = false
  downloading.value = false
}
function resetError() { error.value = ''; errorTrace.value = null }
function report(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '暂时无法读取导出任务，请稍后重试'
  errorTrace.value = cause instanceof ApiError ? cause.traceId : null
}
function sameSession(epoch: string) { return !sessionChanged() && epoch === sessionEpoch() }
function remember(value: ExportJobView, owner: string) {
  storageWarning.value = !rememberExportJob(value, owner)
  history.value = readExportJobHistory(owner)
}
function poll() {
  clearTimeout(timer)
  if (alive && visible.value && job.value && isExportRunning(job.value.status)) timer = setTimeout(() => { void loadJob(job.value!.id) }, 2000)
}
function apply(value: ExportJobView) {
  job.value = value
  selectedId.value = value.id
  lookupId.value = value.id
  if (!isExportRunning(value.status)) cancelRequested.value = false
  poll()
}
async function loadJob(id: string) {
  if (busy.value) return
  const epoch = sessionEpoch()
  const owner = sessionIdentity()
  const read = latest.begin()
  clearTimeout(timer)
  loading.value = true
  resetError()
  try {
    const value = await read.run(() => exportJobApi.get(routeId(id), { silent: true }))
    if (!read.current() || !sameSession(epoch) || !visible.value) return
    remember(value, owner)
    apply(value)
  } catch (cause) {
    if (read.current() && sameSession(epoch) && visible.value) report(cause)
  } finally { if (read.current()) loading.value = false }
}
async function selectJob(id: string) {
  if (busy.value) return
  stopReads()
  ++selection
  job.value = null
  selectedId.value = id
  cancelRequested.value = false
  await loadJob(id)
}
async function create() {
  if (busy.value || !createDraft.value) return
  const draft = createDraft.value
  if (draft.identity !== sessionIdentity()) { report(new Error('登录身份已变化，请重新打开本页')); return }
  stopReads()
  resetError()
  const epoch = sessionEpoch()
  const owner = sessionIdentity()
  const current = ++selection
  creating.value = true
  job.value = null
  selectedId.value = ''
  cancelRequested.value = false
  try {
    const value = await exportJobApi.createStockLogs(draft.filters, {
      silent: true,
      idempotencyKey: draft.key,
      // 别的页签里内容相同的收据可能已经占用了原来的编号。
      recordWriteKey: (key) => { draft.key = key; return storeExportCreate(draft) },
    })
    if (!sameSession(epoch)) return
    remember(value, owner)
    if (clearExportCreate(owner)) createDraft.value = null
    else report(new Error('任务已创建，但未能清除待确认记录；请恢复浏览器存储后核对原提交'))
    if (alive && visible.value && selection === current) apply(value)
  } catch (cause) {
    if (sameSession(epoch) && cause instanceof ApiError && !cause.transient && !unknownActionResult(cause.code) && clearExportCreate(owner)) createDraft.value = null
    if (alive && visible.value && selection === current && sameSession(epoch)) report(cause)
  } finally { creating.value = false }
}
async function open(filters?: StockLogQuery) {
  visible.value = true
  if (busy.value) return
  history.value = readExportJobHistory(sessionIdentity())
  try { createDraft.value = readExportCreate(sessionIdentity()) } catch (cause) { report(cause); return }
  if (createDraft.value) {
    stopReads()
    job.value = null
    selectedId.value = ''
    report(new Error('前次创建结果尚未确认，请先重试原提交核对；确认后再按新的筛选条件创建'))
    return
  }
  if (filters) {
    try { createDraft.value = newExportCreate(sessionIdentity(), filters) } catch (cause) { report(cause); return }
    await create()
  } else {
    const id = selectedId.value || history.value[0]?.id
    if (id) await selectJob(id)
  }
}
async function mutate(action: 'cancel' | 'retry') {
  if (busy.value || !job.value) return
  const value = job.value
  if (action === 'cancel' ? !isExportRunning(value.status) || cancelRequested.value : !canRetryExport(value.status)) return
  stopReads()
  resetError()
  const epoch = sessionEpoch()
  const owner = sessionIdentity()
  const current = selection
  working.value = action
  try {
    const updated = await exportJobApi[action](value.id, { silent: true })
    if (!sameSession(epoch)) return
    remember(updated, owner)
    if (alive && visible.value && selection === current) {
      cancelRequested.value = action === 'cancel' && isExportRunning(updated.status)
      apply(updated)
    }
  } catch (cause) {
    if (alive && visible.value && selection === current && sameSession(epoch)) report(cause)
  } finally { working.value = null }
}
async function downloadFile() {
  if (downloading.value || busy.value || job.value?.status !== 'SUCCEEDED') return
  const epoch = sessionEpoch()
  const read = fileRequest.begin()
  downloading.value = true
  resetError()
  try { await read.run(() => exportJobApi.download(job.value!.id, { silent: true })) }
  catch (cause) { if (read.current() && sameSession(epoch) && visible.value) report(cause) }
  finally { if (read.current()) downloading.value = false }
}
async function copy(value: string) {
  try { await navigator.clipboard.writeText(value); ElMessage.success('已复制') }
  catch { ElMessage.info('请选中编号后复制') }
}
const trace = computed(() => errorTrace.value || job.value?.requestId)
watch(visible, (on) => { if (!on) stopReads() })
const unlisten = onSessionChanged(() => { visible.value = false; job.value = null; history.value = []; selectedId.value = ''; createDraft.value = null; stopReads() })
onDeactivated(() => { visible.value = false; stopReads() })
onBeforeUnmount(() => { alive = false; stopReads(); unlisten() })
defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="库存日志导出" width="640px" class="stock-log-export-dialog" :close-on-click-modal="false">
    <p class="dim export-intro">按上次成功查询的条件生成全部日志，最多 100 万行。关闭窗口后任务继续，文件生成后保留 24 小时。</p>
    <el-form label-position="top" @submit.prevent>
      <el-form-item v-if="history.length" label="本机最近的任务（当前账号，保留 7 天）">
        <el-select :model-value="selectedId" :disabled="busy" placeholder="选择任务" style="width: 100%" @change="selectJob">
          <el-option v-for="item in history" :key="item.id" :value="item.id" :label="`${formatDateTime(item.createdAt)} · ${item.id}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="按任务编号查询">
        <div class="export-lookup">
          <el-input v-model="lookupId" :disabled="busy" placeholder="输入本人创建的任务编号" inputmode="numeric" @keyup.enter="!isComposing($event) && selectJob(lookupId.trim())" />
          <el-button :disabled="busy || !lookupId.trim()" @click="selectJob(lookupId.trim())">查询任务</el-button>
        </div>
      </el-form-item>
    </el-form>
    <p v-if="creating" role="status">正在创建导出任务…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="storageWarning" title="本机无法记录任务，请复制任务编号，以便下次查询。" type="warning" :closable="false" />
    <section v-if="job" class="export-status" :aria-busy="loading" aria-live="polite">
      <div class="export-status-heading"><strong>{{ EXPORT_JOB_STATUS_LABEL[job.status] }}</strong><span v-if="loading" class="dim">正在更新…</span></div>
      <dl>
        <dt>任务编号</dt><dd class="code">{{ job.id }} <el-button link type="primary" @click="copy(job.id)">复制</el-button></dd>
        <dt>已处理行数</dt><dd class="num">{{ job.rows.toLocaleString('zh-CN') }}</dd>
        <dt>创建时间</dt><dd class="num">{{ formatDateTime(job.createdAt) }}</dd>
        <template v-if="job.expiresAt"><dt>文件有效期</dt><dd class="num">{{ formatDateTime(job.expiresAt) }}</dd></template>
        <template v-if="job.errorCode"><dt>失败原因</dt><dd>{{ exportJobErrorMessage(job.errorCode, job.requestId) }}</dd></template>
      </dl>
      <p v-if="cancelRequested" class="dim" role="status">取消请求已收到，正在等待后台确认…</p>
      <p v-else-if="isExportRunning(job.status)" class="dim">任务处理中，将自动更新状态。每个账号最多同时处理 2 个任务。</p>
      <p v-if="canRetryExport(job.status)" class="dim">可按原筛选条件重新生成，届时会重新检查当前权限。</p>
    </section>
    <p v-else-if="!creating && !error" class="dim">暂无任务。关闭窗口后，点击库存日志页的“导出”即可创建。</p>
    <p v-if="trace" class="export-trace dim">追踪码 <span class="code">{{ trace }}</span><el-button link type="primary" @click="copy(trace)">复制</el-button></p>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button v-if="createDraft" :loading="creating" :disabled="busy" @click="create">重试创建</el-button>
      <template v-if="job">
        <el-button :loading="loading" :disabled="busy" @click="loadJob(job.id)">刷新状态</el-button>
        <el-button v-if="isExportRunning(job.status)" :loading="working === 'cancel'" :disabled="busy || cancelRequested" @click="mutate('cancel')">取消任务</el-button>
        <el-button v-if="canRetryExport(job.status)" type="primary" :loading="working === 'retry'" :disabled="busy" @click="mutate('retry')">重新生成</el-button>
        <el-button v-if="job.status === 'SUCCEEDED'" type="primary" :loading="downloading" :disabled="busy" @click="downloadFile">下载文件</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.export-intro { margin: 0 0 18px; line-height: 1.7; }
.export-lookup { display: flex; gap: 8px; width: 100%; }
.export-status { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--lm-line-soft); }
.export-status-heading { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.export-status-heading strong { font-size: 15px; }
dl { display: grid; grid-template-columns: 96px minmax(0, 1fr); gap: 10px; margin: 0; align-items: center; }
dt { color: var(--lm-muted); }
dd { margin: 0; overflow-wrap: anywhere; }
.export-trace { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 16px 0 0; overflow-wrap: anywhere; }
.export-trace .code { min-width: 0; }
</style>
