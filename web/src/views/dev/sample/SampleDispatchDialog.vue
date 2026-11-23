<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { sampleOrderApi } from '@/api/sample'
import type { DispatchForm, PlannedNode, SampleActionResult, SampleDetail, SampleOrder } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import UserSelect from '@/components/UserSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import SampleFeedback from './SampleFeedback.vue'
import { orderScope } from './common'
import { useSampleAction } from './useSampleAction'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'

const props = defineProps<{ sample: SampleDetail; order: SampleOrder }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [result: SampleActionResult] }>()
const user = useUserStore(), original = props.order
const form = reactive<DispatchForm>({ version: original.version, sampleVersionLock: original.sampleVersionLock,
  makeWay: original.makeWay ?? 'EXTERNAL', factoryId: original.makeWay === 'INTERNAL' ? null : original.factoryId ?? props.sample.sampleFactoryId,
  makerId: original.makeWay === 'INTERNAL' ? original.makerId ?? props.sample.patternMakerId : null, needDate: original.needDate })
const names = reactive({ factory: original.factoryName ?? props.sample.sampleFactoryName, maker: original.makerName ?? props.sample.patternMakerName })
const scope = () => visible.value ? orderScope(props.order) : null
const allowed = computed(() => props.order.current && props.order.actions.dispatch && user.hasPerm('dev:sample:make'))
const guard = useUnsavedChanges({ form, names }, visible, { scope })
useSampleContext(() => { visible.value = false })
const action = useSampleAction<DispatchForm, SampleActionResult>(scope, () => allowed.value,
  (body, options) => sampleOrderApi.dispatch(original.id, original.companyId, body, options))
const previewRead = useSampleRead(), nodes = shallowRef<PlannedNode[]>([])
const previewLoading = ref(false), previewError = ref<string | null>(null), inputError = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
async function preview() {
  clearTimeout(timer); const read = previewRead.begin(); nodes.value = []; previewError.value = null; previewLoading.value = false
  if (!form.needDate || !allowed.value) return
  previewLoading.value = true
  try { const result = await sampleOrderApi.dispatchPreview(original.id, original.companyId, form.needDate, { signal: read.signal, silent: true }); if (read.current()) nodes.value = result.nodes }
  catch (cause) { if (read.current()) previewError.value = sampleError(cause) }
  finally { if (read.current()) previewLoading.value = false }
}
watch(() => form.needDate, () => { previewRead.cancel(); nodes.value = []; clearTimeout(timer); timer = setTimeout(preview, 200) }, { immediate: true })
onBeforeUnmount(() => clearTimeout(timer))
function wayChanged() { if (form.makeWay === 'INTERNAL') { form.factoryId = null; names.factory = null } else { form.makerId = null; names.maker = null } }
async function save() {
  inputError.value = null
  if (!form.needDate || (form.makeWay === 'INTERNAL' ? !form.makerId : !form.factoryId)) { inputError.value = '请选择打样方和要样日期'; return }
  if (!nodes.value.length || previewLoading.value || previewError.value) { inputError.value = '先成功读取节点倒排预览，再派单'; return }
  const result = await action.execute({ ...form, factoryId: form.makeWay === 'EXTERNAL' ? form.factoryId : null, makerId: form.makeWay === 'INTERNAL' ? form.makerId : null }, original.companyId)
  if (result) { guard.discardDraft(); visible.value = false; emit('saved', result) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" title="派单" width="520px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p class="dim">第 {{ original.sampleVersion }} 版 · {{ original.no }}</p>
    <el-alert v-if="!allowed" title="当前状态或权限不允许派单" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="!allowed || action.locked.value">
      <el-form-item label="方式"><el-segmented v-model="form.makeWay" :options="[{ label: '外部工厂', value: 'EXTERNAL' }, { label: '内部版房', value: 'INTERNAL' }]" @change="wayChanged" /></el-form-item>
      <el-form-item v-if="form.makeWay === 'EXTERNAL'" label="打样工厂" required><SupplierSelect v-model="form.factoryId" v-model:label="names.factory" :category="['GARMENT', 'PROCESSOR']" usage="SAMPLE" /></el-form-item>
      <el-form-item v-else label="版师" required><UserSelect v-model="form.makerId" v-model:label="names.maker" /></el-form-item>
      <el-form-item label="要样日期" required><el-date-picker v-model="form.needDate" value-format="YYYY-MM-DD" type="date" aria-label="派单要样日期" /></el-form-item>
    </el-form>
    <h4>节点计划预览</h4><SampleFeedback :loading="previewLoading" :error="previewError" label="倒排计划" @retry="preview" />
    <ol v-if="nodes.length" class="plans"><li v-for="node in nodes" :key="node.nodeCode"><span>{{ node.nodeName }}</span><span class="num">{{ node.planDate }}</span><small class="dim">{{ node.durationDays }} 天</small></li></ol>
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed || previewLoading || !!previewError || !nodes.length" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : '派单' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.plans { max-height: 260px; overflow-y: auto; margin: 0; padding: 0; list-style: none; }
.plans li { display: grid; grid-template-columns: minmax(0, 1fr) 110px 50px; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--lm-line-soft); }
</style>
