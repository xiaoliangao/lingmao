<script setup lang="ts">
import './sample.css'
import { computed, onActivated, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sampleApi } from '@/api/sample'
import type { ConvertResult, SampleActionResult, SampleDetail, SampleImagesResult, SampleOrder, SampleOrderFilesResult, SampleRemoved } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useTabsStore } from '@/stores/tabs'
import { useFieldAccess } from '@/composables/useFieldAccess'
import { useDict } from '@/composables/useDict'
import { routeId } from '@/utils/entityId'
import { ApiError } from '@/utils/request'
import { formatDateTime, formatMoney, formatPrice } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import BlankState from '@/components/BlankState.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import SampleReferences from './SampleReferences.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import ObjectTags from '@/components/tags/ObjectTags.vue'
import TechPackTab from '@/views/style/style/TechPackTab.vue'
import SampleFeedback from './SampleFeedback.vue'
import SampleFormDialog from './SampleFormDialog.vue'
import SampleOrderDialog from './SampleOrderDialog.vue'
import SampleDispatchDialog from './SampleDispatchDialog.vue'
import SampleNodeDialog from './SampleNodeDialog.vue'
import SampleReviewDialog from './SampleReviewDialog.vue'
import SampleReasonDialog from './SampleReasonDialog.vue'
import SampleConvertDialog from './SampleConvertDialog.vue'
import SampleFilesDialog from './SampleFilesDialog.vue'
import SampleVersionCard from './SampleVersionCard.vue'
import SampleLogs from './SampleLogs.vue'
import { SAMPLE_STATUS, orderScope, sampleListPath } from './common'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'
import { useSampleAction } from './useSampleAction'
import { takeSampleCreated } from './sampleCreatedReceipt'

const route = useRoute(), router = useRouter(), user = useUserStore(), tabs = useTabsStore()
const id = routeId(route.params.id), path = route.path
const companyId = ref<string | null>(typeof route.query.companyId === 'string' ? route.query.companyId : null)
const canRead = computed(() => user.hasPerm('dev:sample:list')), { canSeeCost } = useFieldAccess()
const seasons = useDict('season'), sources = useDict('style_source'), brands = useDict('brand')
const detail = shallowRef<SampleDetail | null>(null), loading = ref(false), error = ref<string | null>(null), hidden = ref(false)
const tab = ref(route.query.tab === 'techpack' ? 'techpack' : 'versions'), success = ref<string | null>(null), warning = ref<string | null>(null)
const versions = shallowRef<SampleOrder[]>([]), historyLoading = ref(false), historyError = ref<string | null>(null)
const beforeVersion = ref<number | undefined>(), backCursors = ref<(number | undefined)[]>([]), nextBefore = ref<number | null>(null), hasMore = ref(false)
const refresh = ref(0), editOpen = ref(false), dispatchOpen = ref(false), reviewOpen = ref(false), convertOpen = ref(false)
const reasonKind = ref<'stop' | 'reopen' | null>(null), reasonOpen = ref(false), filesOpen = ref(false)
const filesOrder = shallowRef<SampleOrder | undefined>(), editOrder = shallowRef<SampleOrder | null>(null), orderOpen = ref(false)
const nodeOrder = shallowRef<SampleOrder | null>(null), nodeOpen = ref(false), nodeMode = ref<'done' | 'undo' | 'plans'>('done'), nodeId = ref<string>()
const currentOrder = computed(() => detail.value?.currentOrder)
const listPath = computed(() => sampleListPath(companyId.value))
const detailRead = useSampleRead(), historyRead = useSampleRead()
function closeForms() { editOpen.value = false; dispatchOpen.value = false; reviewOpen.value = false; convertOpen.value = false; reasonOpen.value = false; filesOpen.value = false; orderOpen.value = false; nodeOpen.value = false }
function clear() { detailRead.cancel(); historyRead.cancel(); closeForms(); detail.value = null; versions.value = []; loading.value = false; historyLoading.value = false; error.value = null; historyError.value = null; hidden.value = false; beforeVersion.value = undefined; backCursors.value = []; nextBefore.value = null; hasMore.value = false }
const context = useSampleContext(() => { clear(); companyId.value = null; success.value = null; warning.value = null })
const permission: Record<keyof SampleDetail['actions'], string> = { edit: 'edit', remove: 'remove', stop: 'review', reopen: 'review', convert: 'convert' }
const can = (key: keyof SampleDetail['actions']) => !!detail.value?.actions[key] && canRead.value && user.hasPerm('dev:sample:' + permission[key]) && !loading.value && !error.value
const orderCan = (key: keyof SampleOrder['actions']) => !!currentOrder.value?.current && !!currentOrder.value.actions[key] && canRead.value && !loading.value && !error.value && user.hasPerm('dev:sample:' + (key === 'node' || key === 'dispatch' ? 'make' : key))
async function load(): Promise<'ok' | 'hidden' | 'failed' | 'cancelled'> {
  const read = detailRead.begin(), company = companyId.value
  loading.value = false; error.value = null; hidden.value = false
  if (!company || !canRead.value) { detail.value = null; return 'cancelled' }
  loading.value = true
  try {
    const result = await sampleApi.get(id, company, { signal: read.signal, silent: true })
    if (!read.current()) return 'cancelled'
    if (result.companyId !== company) throw new ApiError(409, '样衣主体不一致，请重新选择主体')
    detail.value = result; tabs.rename(path, `样衣 ${result.code}`)
    return 'ok'
  } catch (cause) {
    if (!read.current()) return 'cancelled'
    detail.value = null; hidden.value = cause instanceof ApiError && [403, 404].includes(cause.code); error.value = sampleError(cause)
    return hidden.value ? 'hidden' : 'failed'
  } finally { if (read.current()) loading.value = false }
}
async function loadVersions() {
  const read = historyRead.begin(), company = companyId.value
  historyError.value = null; historyLoading.value = false
  if (!company || !detail.value || !canRead.value) { versions.value = []; return }
  historyLoading.value = true
  try {
    const result = await sampleApi.versions(id, company, { beforeVersion: beforeVersion.value, size: 20 }, { signal: read.signal, silent: true })
    if (!read.current()) return
    versions.value = result.list; hasMore.value = result.hasMore; nextBefore.value = result.nextBeforeVersion
  } catch (cause) { if (read.current()) { versions.value = []; historyError.value = sampleError(cause); hasMore.value = false } }
  finally { if (read.current()) historyLoading.value = false }
}
async function reload() {
  const current = context.capture(), created = takeSampleCreated(id, companyId.value)
  if (created) success.value = `样衣已创建：${created.code}`
  const outcome = await load()
  if (!current()) return
  if (created && outcome === 'hidden') {
    ElMessage.warning('创建已成功；当前无权查看详情，请在列表核对。')
    const to = sampleListPath(created.companyId); await router.push(to); tabs.close(path, to)
  } else if (created && outcome === 'failed') success.value += '；详情暂时未能刷新，请只重试读取。'
  else if (outcome === 'ok') { refresh.value++; await loadVersions() }
}
function historyNext() { if (historyLoading.value || !hasMore.value || nextBefore.value == null) return; backCursors.value.push(beforeVersion.value); beforeVersion.value = nextBefore.value; void loadVersions() }
function historyBack() { if (historyLoading.value || !backCursors.value.length) return; beforeVersion.value = backCursors.value.pop(); void loadVersions() }
let applyingReceipt = false
watch(companyId, () => { clear(); success.value = null; warning.value = null; if (!applyingReceipt) void reload() }, { flush: 'sync' })
watch(canRead, () => { clear(); if (canRead.value) void reload() })
watch(() => route.query, (query) => { if (route.path !== path) return; if (typeof query.companyId === 'string') companyId.value = query.companyId; if (query.tab === 'techpack') tab.value = 'techpack' })
onMounted(reload)
let activated = false
onActivated(() => { if (activated && !hidden.value) void reload(); activated = true })

type Receipt = SampleActionResult | SampleImagesResult | SampleOrderFilesResult
async function saved(receipt: Receipt) {
  const current = context.capture(), company = receipt.companyId
  // 主体变更是成功动作的一部分：先清理旧页，再显示成功收据，后续只重读。
  applyingReceipt = true
  companyId.value = company
  applyingReceipt = false
  success.value = '操作已成功保存'; warning.value = 'warning' in receipt ? receipt.warning : null
  ElMessage.success(success.value)
  if (warning.value) ElMessage.warning(warning.value)
  closeForms()
  if (route.query.companyId !== company) await router.replace({ path, query: { ...route.query, companyId: company } })
  if (!current()) return
  beforeVersion.value = undefined; backCursors.value = []
  const outcome = await load()
  if (!current()) return
  if (outcome === 'hidden') {
    ElMessage.warning('操作已成功；改派或主体变更后，你已无法查看这份样衣。')
    const to = sampleListPath(company); await router.push(to); tabs.close(path, to); return
  }
  if (outcome === 'ok') { refresh.value++; await loadVersions() }
  else if (outcome === 'failed') success.value = '操作已成功保存，详情暂时未能刷新；请只重试读取。'
}
async function converted(receipt: ConvertResult) {
  const current = context.capture()
  closeForms(); success.value = `已生成款式 ${receipt.styleCode} · ${receipt.skuCount} 个 SKU${receipt.prodPackId ? '，生产 V1 草稿待核对' : ''}`
  ElMessage.success(success.value)
  if (user.hasPerm('style:style:list')) { await router.push(`/styles/${receipt.styleId}${receipt.prodPackId ? `?tab=techpack&techPackId=${receipt.prodPackId}` : ''}`); return }
  const outcome = await load()
  if (!current()) return
  if (outcome === 'hidden') {
    ElMessage.warning('转大货已成功；当前已无法查看这份样衣，请回列表核对。')
    const to = sampleListPath(receipt.companyId); await router.push(to); tabs.close(path, to)
  } else if (outcome === 'ok') { refresh.value++; await loadVersions() }
  else if (outcome === 'failed') success.value += '；详情暂时未能刷新，请只重试读取。'
}
function openFiles(order?: SampleOrder) { filesOrder.value = order; filesOpen.value = true }
function openOrder(order: SampleOrder) { editOrder.value = order; orderOpen.value = true }
function openNode(order: SampleOrder, mode: 'done' | 'undo' | 'plans', identifier?: string) { nodeOrder.value = order; nodeMode.value = mode; nodeId.value = identifier; nodeOpen.value = true }
function openReason(kind: 'stop' | 'reopen') { reasonKind.value = kind; reasonOpen.value = true }
const removeAction = useSampleAction<{ id: string; companyId: string; version: number }, SampleRemoved>(
  () => detail.value ? `${detail.value.companyId}:${detail.value.id}:${detail.value.version}:remove` : null, () => can('remove'),
  (body, options) => sampleApi.remove(body.id, body.companyId, body.version, options))
async function remove() {
  const value = detail.value, current = context.capture()
  if (!value || !can('remove') || removeAction.busy.value) return
  try { await confirmRemove(`删除 ${value.code}`, '仅首版未派单且没有资料包或下游的样衣可删除；操作记录仍保留。') }
  catch { return }
  if (!current() || detail.value !== value) return
  const result = await removeAction.execute({ id: value.id, companyId: value.companyId, version: value.version }, value.companyId)
  if (result && current()) { ElMessage.success('样衣已删除'); const to = sampleListPath(result.companyId); await router.push(to); tabs.close(path, to) }
}
</script>
<template>
  <div>
    <PageHeader :title="detail?.code ?? '样衣'" :back="{ label: '样衣', to: listPath }">
      <template v-if="detail" #title><StatusTag :tone="SAMPLE_STATUS[detail.showStatus].tone">{{ SAMPLE_STATUS[detail.showStatus].label }}</StatusTag></template>
      <template v-if="detail" #actions>
        <el-button :loading="loading" @click="reload">重新读取</el-button>
        <el-button v-if="can('remove')" :loading="removeAction.busy.value" @click="remove">删除</el-button>
        <el-button v-if="can('stop')" @click="openReason('stop')">终止</el-button><el-button v-if="can('reopen')" @click="openReason('reopen')">恢复开发</el-button>
        <el-button v-if="can('edit')" @click="editOpen = true">编辑</el-button>
        <el-button v-if="orderCan('dispatch')" type="primary" @click="dispatchOpen = true">派单</el-button>
        <el-button v-if="orderCan('node') && currentOrder?.status === 'MAKING'" type="primary" @click="openNode(currentOrder!, 'done')">报节点</el-button>
        <el-button v-if="orderCan('review')" type="primary" @click="reviewOpen = true">评审</el-button>
        <el-button v-if="can('convert')" type="primary" @click="convertOpen = true">转大货</el-button>
        <el-button v-if="detail.convertedStyleId && user.hasPerm('style:style:list')" @click="router.push(`/styles/${detail.convertedStyleId}`)">款式 {{ detail.convertedStyleCode }}</el-button>
      </template>
    </PageHeader>
    <div v-if="success" class="result-notice" role="status">{{ success }}<p v-if="warning" class="warning">{{ warning }}</p></div>
    <div v-if="!detail" class="page"><CompanySelect v-model="companyId" aria-label="样衣主体" placeholder="选择这份样衣的主体" style="width: 220px" /><p v-if="!companyId" class="dim">先选择主体，再读取样衣。</p><p v-if="!canRead" class="dim">当前没有样衣读取权限。</p><SampleFeedback :loading="loading" :error="error" label="样衣" @retry="reload" /></div>
    <BlankState v-if="hidden" title="无权限或样衣已不可见"><el-button @click="reload">重试</el-button><el-button @click="router.push(listPath)">回列表</el-button></BlankState>
    <div v-if="detail" class="page sample-detail" :aria-busy="loading">
      <main class="body">
        <h2>{{ detail.name }}</h2><p class="sub dim">{{ detail.categoryName }} · {{ detail.year }} {{ seasons.labelOf(detail.season) }}<span v-if="detail.wave"> · {{ detail.wave }}</span></p>
        <SampleFeedback :loading="loading" :error="error" label="样衣" @retry="reload" /><SampleFeedback :error="removeAction.error.value" :uncertain="removeAction.uncertain.value" @retry="remove" />
        <el-tabs v-model="tab">
          <el-tab-pane label="打样记录" name="versions">
            <SampleFeedback :loading="historyLoading" :error="historyError" label="历史版本" @retry="loadVersions" />
            <div v-if="!historyLoading && !historyError && !versions.length" class="dim">没有打样记录</div>
            <SampleVersionCard v-for="order in versions" :key="`${order.id}:${order.version}:${order.sampleVersionLock}`" :order="order" :disabled="loading || !!error || historyLoading" @files="openFiles" @edit="openOrder" @node="openNode" />
            <div v-if="backCursors.length || hasMore" class="history-pager"><el-button :disabled="historyLoading || !backCursors.length" @click="historyBack">上一页</el-button><span class="dim">第 {{ backCursors.length + 1 }} 页 · 每页最多 20 版</span><el-button :disabled="historyLoading || !hasMore" @click="historyNext">更早版本</el-button></div>
          </el-tab-pane>
          <el-tab-pane v-if="user.hasPerm('techpack:pack:list')" label="研发资料包" name="techpack" lazy>
            <p v-if="detail.status === 'CONVERTED'" class="dim">研发资料包已移交款式 {{ detail.convertedStyleCode }}。<router-link v-if="detail.convertedStyleId && user.hasPerm('style:style:list')" :to="`/styles/${detail.convertedStyleId}?tab=techpack`">到款式查看</router-link></p>
            <TechPackTab v-else :key="`${detail.companyId}:${detail.id}`" :owner="{ kind: 'SAMPLE', sampleId: detail.id, companyId: detail.companyId }" :style-code="detail.code" :style-name="detail.name" :style-enabled="detail.status !== 'ELIMINATED'" />
          </el-tab-pane>
          <el-tab-pane label="下游引用" name="references" lazy><SampleReferences :key="`${detail.companyId}:${detail.id}`" :sample-id="detail.id" :company-id="detail.companyId" /></el-tab-pane>
        </el-tabs>
      </main>
      <aside class="aside">
        <ObjectTags type="SAMPLE" title="样衣标签" :object-hint="`${detail.code} · ${detail.name}`" :object-id="detail.id" :company-id="detail.companyId" :editable="can('edit')" @saved="refresh++" />
        <section class="panel"><div class="panel-head">图片<el-button v-if="can('edit')" link @click="openFiles()">编辑图片</el-button></div><div class="panel-body"><ImageUpload :model-value="detail.images" :max="20" disabled /></div></section>
        <section class="panel"><div class="panel-head">样衣属性</div><div class="panel-body"><dl class="attrs">
          <dt>品牌</dt><dd>{{ brands.labelOf(detail.brand) }}</dd><dt>来源</dt><dd>{{ sources.labelOf(detail.source) }}</dd>
          <dt>设计师</dt><dd>{{ detail.designerName ?? '—' }}</dd><dt>版师</dt><dd>{{ detail.patternMakerName ?? '—' }}</dd>
          <dt>尺码组</dt><dd>{{ detail.sizeGroupName }} · {{ detail.sizes.join(' / ') }}</dd><dt>颜色</dt><dd>{{ detail.colors.map((color) => `${color.code} ${color.name}`).join('、') }}</dd>
          <dt>打样工厂</dt><dd>{{ detail.sampleFactoryName ?? '—' }}</dd><dt>面料供应商</dt><dd>{{ detail.fabricSupplierName ?? '—' }}</dd><dt>面料</dt><dd>{{ detail.fabricDesc || '—' }}</dd>
          <dt>预估吊牌价</dt><dd>{{ detail.estTagPrice == null ? '—' : formatMoney(detail.estTagPrice) }}</dd><template v-if="canSeeCost"><dt>预估成本</dt><dd>{{ detail.estCost == null ? '—' : formatPrice(detail.estCost) }}</dd></template>
          <dt>预计完成</dt><dd>{{ detail.expectFinishDate ?? '—' }}</dd><dt>建档</dt><dd>{{ formatDateTime(detail.createTime) }}</dd><dt>备注</dt><dd class="pre">{{ detail.remark || '—' }}</dd>
        </dl></div></section>
        <section class="panel"><div class="panel-head">操作记录</div><div class="panel-body"><SampleLogs :key="`${detail.companyId}:${detail.id}:${refresh}`" :sample-id="detail.id" :company-id="detail.companyId" /></div></section>
      </aside>
    </div>
    <template v-if="detail">
      <SampleFormDialog v-if="editOpen" :key="`form:${detail.version}`" v-model="editOpen" :initial-company-id="detail.companyId" :sample="detail" @saved="saved" />
      <SampleOrderDialog v-if="orderOpen && editOrder" :key="orderScope(editOrder)" v-model="orderOpen" :sample="detail" :order="editOrder" @saved="saved" />
      <SampleDispatchDialog v-if="dispatchOpen && currentOrder" :key="`dispatch:${orderScope(currentOrder)}`" v-model="dispatchOpen" :sample="detail" :order="currentOrder" @saved="saved" />
      <SampleNodeDialog v-if="nodeOpen && nodeOrder" :key="`node:${orderScope(nodeOrder)}`" v-model="nodeOpen" :order="nodeOrder" :initial-mode="nodeMode" :initial-node-id="nodeId" @saved="saved" />
      <SampleReviewDialog v-if="reviewOpen && currentOrder" :key="`review:${orderScope(currentOrder)}`" v-model="reviewOpen" :order="currentOrder" @saved="saved" />
      <SampleReasonDialog v-if="reasonOpen && reasonKind" :key="`reason:${detail.version}:${reasonKind}`" v-model="reasonOpen" :sample="detail" :kind="reasonKind" @saved="saved" />
      <SampleConvertDialog v-if="convertOpen" :key="`convert:${detail.version}`" v-model="convertOpen" :sample="detail" @saved="converted" />
      <SampleFilesDialog v-if="filesOpen" :key="`files:${detail.version}:${filesOrder?.id}`" v-model="filesOpen" :sample="detail" :order="filesOrder" @saved="saved" />
    </template>
  </div>
</template>
<style scoped>
.sample-detail { display: grid; grid-template-columns: minmax(0, 1fr) 300px; align-items: start; gap: 20px; }
.body, .aside { min-width: 0; }.body h2 { margin: 0; font-size: 18px; }.sub { margin: 8px 0 14px; font-size: 12.5px; }
.aside { display: flex; flex-direction: column; gap: 14px; }.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.attrs { display: grid; grid-template-columns: 78px minmax(0, 1fr); gap: 8px; margin: 0; font-size: 12px; }.attrs dt { color: var(--lm-muted); }.attrs dd { margin: 0; overflow-wrap: anywhere; }.pre { white-space: pre-wrap; }
.result-notice { padding: 10px 24px; background: var(--lm-done-bg); color: var(--lm-done-text); font-size: 12px; }.warning { color: var(--lm-pending-text); margin: 5px 0 0; }
.history-pager { display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin: 16px 0; }
@media (max-width: 1200px) { .sample-detail { grid-template-columns: minmax(0, 1fr); } }
</style>
