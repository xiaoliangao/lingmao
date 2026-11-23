<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTagContext } from '@/composables/useTagContext'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { tagApi } from '@/api/tag'
import { TAG_LIMIT, TAG_PERMISSIONS, type ObjectTagSnapshot, type PublicTag, type TagObjectType, type TagReplace } from '@/types/tag'
import { ApiError } from '@/utils/request'
import { ErrorCode } from '@/utils/errorCodes'
import { clientActionUuid, unknownActionResult } from '@/utils/clientAction'
import { isComposing } from '@/utils/ime'
import { choiceOf, previewTagName, tagChoiceName, tagError, tagReplace, type TagChoice } from '@/utils/publicTag'
import TagHistoryDialog from './TagHistoryDialog.vue'

/** 仅绑定已持久化对象。editable 来自所在领域的状态/普通范围，再与当前权限求交。 */
const props = withDefaults(defineProps<{
  type: TagObjectType
  objectId: string
  companyId?: string | null
  editable?: boolean
  active?: boolean
  title?: string
  objectHint?: string
}>(), { editable: false, active: true, title: '公共标签', objectHint: '' })
const emit = defineEmits<{ saved: [] }>()
const user = useUserStore()
const canRead = computed(() => user.hasPerm(TAG_PERMISSIONS[props.type].read))
const canWrite = computed(() => props.editable && canRead.value && user.hasPerm(TAG_PERMISSIONS[props.type].write))
const canSearch = computed(() => user.hasPerm('biz:tag:list'))
const value = shallowRef<ObjectTagSnapshot | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const open = ref(false)
const choices = ref<TagChoice[]>([])
const input = ref('')
const inputError = ref<string | null>(null)
const options = ref<PublicTag[]>([])
const optionsLoading = ref(false)
const optionsError = ref<string | null>(null)
const saving = ref(false)
const writeError = ref<string | null>(null)
const blocked = ref(false)
const reviewingLatest = ref(false)
const snapshot = shallowRef<ObjectTagSnapshot | null>(null)
const historyVisible = ref(false)
const historyTag = shallowRef<PublicTag | null>(null)
const pending = shallowRef<{ key: string; body: TagReplace } | null>(null)
const loadRequest = useLatestRequest({ cancelOnDeactivate: true })
const optionRequest = useLatestRequest({ cancelOnDeactivate: true })
let searchTimer: ReturnType<typeof setTimeout> | undefined
let writeController: AbortController | undefined
let inputSeq = 0
const context = useTagContext(resetContext)
const guard = useUnsavedChanges(() => ({ choices: choices.value, input: input.value }), open, {
  // 短标签表单不跨版本恢复旧 CAS 快照；关闭/路由/刷新仍有未保存提醒。
  scope: () => null,
})

function resetContext() {
  clearTimeout(searchTimer); loadRequest.cancel(); optionRequest.cancel(); writeController?.abort()
  value.value = null; snapshot.value = null; options.value = []; choices.value = []; input.value = ''
  loading.value = false; optionsLoading.value = false; saving.value = false
  open.value = false; historyVisible.value = false; historyTag.value = null
  pending.value = null; blocked.value = false; reviewingLatest.value = false
  loadError.value = null; inputError.value = null; writeError.value = null; optionsError.value = null
}
async function load(review = false) {
  loadRequest.cancel(); loadError.value = null; loading.value = false
  if (!props.active) return
  if (!canRead.value) { value.value = null; loadError.value = '当前没有读取此对象标签的权限'; return }
  const read = loadRequest.begin(), current = context.capture()
  loading.value = true
  try {
    const result = await tagApi.read(props.type, props.objectId, props.companyId, { signal: read.signal, silent: true })
    if (!read.current() || !current() || !canRead.value) return
    value.value = result
    if (review && open.value) {
      snapshot.value = result
      const latest = new Map(result.tags.map((tag) => [tag.id, tag]))
      choices.value = choices.value.map((choice) => choice.kind === 'saved' && latest.has(choice.tag.id)
        ? choiceOf(latest.get(choice.tag.id)!) : choice)
      pending.value = null; blocked.value = false; writeError.value = null; reviewingLatest.value = true
    }
  } catch (cause) {
    if (!read.current() || !current()) return
    loadError.value = tagError(cause)
    if (review) writeError.value = loadError.value
  } finally { if (read.current() && current()) loading.value = false }
}
watch(() => [props.type, props.objectId, props.companyId, props.active], () => {
  context.invalidate()
  if (props.active) void load()
}, { immediate: true, flush: 'sync' })
watch(canRead, () => { loadRequest.cancel(); if (props.active) void load() })
watch([canWrite, canSearch], () => { optionRequest.cancel(); optionsLoading.value = false; if (!canSearch.value) options.value = [] })
let activated = false
onActivated(() => { if (activated && props.active) void load(); activated = true })
onBeforeUnmount(() => { clearTimeout(searchTimer); writeController?.abort() })

function edit() {
  if (!canWrite.value || !value.value || loading.value || loadError.value) return
  snapshot.value = value.value
  choices.value = value.value.tags.map(choiceOf)
  input.value = ''; inputError.value = null; writeError.value = null; blocked.value = false; pending.value = null; reviewingLatest.value = false
  open.value = true
  void searchOptions()
}
async function searchOptions() {
  clearTimeout(searchTimer); optionRequest.cancel(); optionsError.value = null; optionsLoading.value = false
  if (!open.value || !canSearch.value || !canWrite.value || pending.value) { options.value = []; return }
  const read = optionRequest.begin(), current = context.capture()
  optionsLoading.value = true
  try {
    const result = await tagApi.options({ keyword: input.value, size: 50 }, { signal: read.signal, silent: true })
    if (!read.current() || !current() || !open.value || !canSearch.value) return
    options.value = result
  } catch (cause) {
    if (!read.current() || !current()) return
    options.value = []; optionsError.value = tagError(cause)
  } finally { if (read.current() && current()) optionsLoading.value = false }
}
function scheduleSearch() {
  inputError.value = null; optionRequest.cancel(); options.value = []; optionsLoading.value = false
  clearTimeout(searchTimer); searchTimer = setTimeout(() => { void searchOptions() }, 250)
}
watch(open, (on) => { if (!on) { clearTimeout(searchTimer); optionRequest.cancel(); options.value = [] } })
const locked = computed(() => saving.value || !!pending.value || !canWrite.value)
const selectedIds = computed(() => new Set(choices.value.flatMap((choice) => choice.kind === 'saved' ? [choice.tag.id] : [])))
const originalIds = computed(() => new Set(snapshot.value?.tags.map((tag) => tag.id) ?? []))
const orphanIds = ref(new Set<string>())
// 只有重新读取后才识别被他人移除的原关联；外部新选项本来就不在 snapshot。
const originallySelectedIds = ref(new Set<string>())
watch(open, (on) => { if (on) originallySelectedIds.value = new Set(snapshot.value?.tags.map((tag) => tag.id) ?? []) })
watch([choices, snapshot], () => {
  orphanIds.value = new Set(choices.value.flatMap((choice) =>
    reviewingLatest.value && choice.kind === 'saved' && originallySelectedIds.value.has(choice.tag.id) && !originalIds.value.has(choice.tag.id) ? [choice.tag.id] : []))
}, { deep: true })
function room() {
  if (locked.value) return false
  if (choices.value.length >= TAG_LIMIT) { inputError.value = '每个对象最多 30 个标签'; return false }
  return true
}
function addInput() {
  if (!room()) return
  try {
    const name = previewTagName(input.value)
    choices.value.push({ key: 'input:' + (++inputSeq), kind: 'input', raw: input.value, name })
    input.value = ''; inputError.value = null; void searchOptions()
  } catch (cause) { inputError.value = cause instanceof Error ? cause.message : '标签输入不合法' }
}
function addTag(tag: PublicTag) {
  if (!room() || selectedIds.value.has(tag.id) || (tag.archived && !originalIds.value.has(tag.id))) return
  choices.value.push(choiceOf(tag)); input.value = ''; inputError.value = null
}
function remove(index: number) { if (!locked.value) choices.value.splice(index, 1) }
function move(index: number, by: number) {
  if (locked.value || index + by < 0 || index + by >= choices.value.length) return
  const item = choices.value.splice(index, 1)[0]
  if (item) choices.value.splice(index + by, 0, item)
}
async function save() {
  if (saving.value || !canWrite.value || !snapshot.value || blocked.value || orphanIds.value.size) return
  if (input.value) { inputError.value = '先点“添加标签”，或清空未添加的输入'; return }
  const current = context.capture()
  const action = pending.value ?? { key: clientActionUuid(), body: tagReplace(snapshot.value, choices.value) }
  pending.value = action; saving.value = true; writeError.value = null
  writeController = new AbortController()
  try {
    const result = await tagApi.replace(props.type, props.objectId, action.body, props.companyId, {
      silent: true, idempotencyKey: action.key, signal: writeController.signal,
    })
    if (!current() || !open.value) return
    value.value = result; snapshot.value = result; pending.value = null
    guard.markSaved(); open.value = false; ElMessage.success('标签已保存'); emit('saved')
  } catch (cause) {
    if (!current()) return
    writeError.value = tagError(cause)
    const uncertain = cause instanceof ApiError ? cause.transient || unknownActionResult(cause.code) : true
    if (!uncertain) pending.value = null
    blocked.value = cause instanceof ApiError && [403, 404, ErrorCode.STATE_CHANGED].includes(cause.code)
  } finally { if (current()) saving.value = false }
}
function history(tag: PublicTag) {
  if (!canSearch.value) return
  historyTag.value = tag; historyVisible.value = true
}
</script>

<template>
  <section class="panel object-tags" :data-tag-type="type" :data-tag-object="objectId" :aria-label="title">
    <div class="panel-head tags-head">
      <span>{{ title }}</span>
      <el-button v-if="canWrite" link size="small" :disabled="!value || loading || !!loadError" @click="edit">编辑标签</el-button>
      <span v-else class="dim readonly-label">只读</span>
    </div>
    <div class="panel-body">
      <p v-if="objectHint" class="dim object-hint">{{ objectHint }}</p>
      <p v-if="loading" class="dim feedback" role="status">正在读取标签…</p>
      <p v-else-if="loadError" class="error feedback" role="alert">{{ loadError }} <el-button v-if="canRead" link size="small" @click="load()">重试</el-button></p>
      <div v-else-if="value?.tags.length" class="chips">
        <button v-for="tag in value.tags" :key="tag.id" type="button" class="tag-chip" :class="{ archived: tag.archived }" :disabled="!canSearch"
          :title="tag.archived ? '已归档，历史关联保留' : '查看标签记录'" @click="history(tag)">
          <span>#{{ tag.name }}</span><small v-if="tag.archived">已归档</small>
        </button>
      </div>
      <span v-else-if="value" class="dim">还没有公共标签</span>
      <span v-else class="dim">重新读取后显示标签 <el-button v-if="active && canRead" link @click="load()">读取</el-button></span>
    </div>
  </section>

  <el-dialog v-model="open" :title="'编辑' + title" width="660px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p v-if="objectHint" class="dim object-hint">{{ objectHint }}</p>
    <el-alert v-if="!canWrite" title="当前状态或权限不允许修改；输入已保留" type="warning" :closable="false" />
    <el-alert v-if="writeError" :title="writeError" type="error" :closable="false" class="tag-alert">
      <span v-if="pending">结果还未确认；重试会沿用原来的提交。</span>
      <el-button v-if="!pending && canRead" size="small" :disabled="loading || saving" @click="load(true)">重新读取并核对</el-button>
    </el-alert>
    <div v-if="reviewingLatest && snapshot" class="latest-tags">
      <p class="dim">已读取最新标签。下面的选择仍保留，请核对后再保存。</p>
      <span class="dim">当前记录：</span>
      <button v-for="tag in snapshot.tags" :key="tag.id" type="button" class="tag-chip" :disabled="selectedIds.has(tag.id) || locked" @click="addTag(tag)">#{{ tag.name }}{{ tag.archived ? ' · 已归档' : '' }}</button>
      <span v-if="!snapshot.tags.length" class="dim">无标签</span>
    </div>
    <p class="selection-label">当前选择 <span class="num">{{ choices.length }} / {{ TAG_LIMIT }}</span></p>
    <ul class="tag-selection" aria-label="当前选择的标签">
      <li v-for="(choice, index) in choices" :key="choice.key" :data-tag-id="choice.kind === 'saved' ? choice.tag.id : undefined">
        <span class="tag-chip" :class="{ archived: choice.kind === 'saved' && choice.tag.archived }">#{{ tagChoiceName(choice) }} <small v-if="choice.kind === 'saved' && choice.tag.archived">已归档</small></span>
        <span v-if="choice.kind === 'input'" class="dim small">保存时加入公共库</span>
        <span v-if="choice.kind === 'saved' && orphanIds.has(choice.tag.id)" class="error small">原关联已移除，请移除后重新选择</span>
        <span class="grow" />
        <el-button link :disabled="locked || index === 0" :aria-label="'上移标签 ' + tagChoiceName(choice)" @click="move(index, -1)">上移</el-button>
        <el-button link :disabled="locked || index === choices.length - 1" :aria-label="'下移标签 ' + tagChoiceName(choice)" @click="move(index, 1)">下移</el-button>
        <el-button link :disabled="locked" :aria-label="'移除标签 ' + tagChoiceName(choice)" @click="remove(index)">移除</el-button>
      </li>
      <li v-if="!choices.length" class="dim">未选择标签</li>
    </ul>
    <div class="tag-input">
      <el-input v-model="input" :disabled="locked" placeholder="#输入新标签或搜索已有标签" aria-label="标签输入" maxlength="1024"
        @input="scheduleSearch" @keyup.enter="!isComposing($event) && addInput()" />
      <el-button :disabled="locked || !input" @click="addInput">添加标签</el-button>
    </div>
    <p v-if="inputError" class="error" role="alert">{{ inputError }}</p>
    <p v-if="optionsLoading" class="dim" role="status">正在搜索标签…</p>
    <p v-else-if="optionsError" class="error" role="alert">{{ optionsError }} <el-button link @click="searchOptions">重试搜索</el-button></p>
    <div v-else-if="canSearch && options.length" class="tag-options" aria-label="可选公共标签">
      <button v-for="tag in options" :key="tag.id" type="button" class="tag-chip" :disabled="locked || selectedIds.has(tag.id) || tag.archived" @click="addTag(tag)">#{{ tag.name }}</button>
    </div>
    <p v-else-if="canSearch" class="dim">没有符合此前缀的可用标签，可以输入新名称。</p>
    <p class="dim small">每次显示最多 50 个可用标签；名称保留大小写，每个对象最多 30 个。</p>
    <template #footer>
      <el-button :disabled="saving" @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canWrite || loading || blocked || !!orphanIds.size" @click="save">
        {{ pending ? '重试原提交' : reviewingLatest ? '确认当前选择并保存' : '保存标签' }}
      </el-button>
    </template>
  </el-dialog>
  <TagHistoryDialog v-model="historyVisible" :tag="historyTag" />
</template>

<style scoped>
.tags-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.readonly-label, .small { font-size: 11.5px; }
.object-hint { margin: 0 0 8px; font-size: 12px; overflow-wrap: anywhere; }
.feedback { margin: 0; font-size: 12px; }
.chips, .tag-options { display: flex; flex-wrap: wrap; gap: 6px; }
.tag-chip { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 4px 9px; border: 1px solid var(--lm-accent-line); border-radius: 99px; color: var(--lm-accent-2); background: var(--lm-accent-bg); font: inherit; font-size: 12px; overflow-wrap: anywhere; text-align: left; }
button.tag-chip { cursor: pointer; }
button.tag-chip:disabled { cursor: default; opacity: 0.8; }
.tag-chip.archived { color: var(--lm-muted); border-color: var(--lm-line); background: var(--lm-greige-2); }
.tag-chip small { flex-shrink: 0; font-size: 10px; }
.tag-selection { max-height: 280px; overflow: auto; margin: 0 0 14px; padding: 0; list-style: none; }
.tag-selection li { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding: 5px 0; border-bottom: 1px solid var(--lm-line-soft); }
.grow { flex: 1; }
.tag-input { display: flex; gap: 8px; }
.tag-input .el-input { flex: 1; min-width: 0; }
.tag-options { max-height: 160px; overflow: auto; margin-top: 10px; }
.selection-label { display: flex; justify-content: space-between; margin: 12px 0 4px; }
.latest-tags { padding: 8px 0; }
.latest-tags p { margin: 6px 0; }
.latest-tags .tag-chip { margin: 3px; }
.error { color: var(--lm-danger-text); }
.tag-alert { margin-top: 8px; }
</style>
