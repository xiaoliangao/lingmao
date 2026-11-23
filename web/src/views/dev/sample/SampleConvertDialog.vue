<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { sampleApi } from '@/api/sample'
import type { ConvertForm, ConvertPreview, ConvertResult, SampleDetail } from '@/types/sample'
import { useDict } from '@/composables/useDict'
import { useUserStore } from '@/stores/user'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import SampleFeedback from './SampleFeedback.vue'
import { useSampleAction } from './useSampleAction'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'

const props = defineProps<{ sample: SampleDetail }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [result: ConvertResult] }>()
const original = props.sample, user = useUserStore(), classes = useDict('style_class')
const passedFactory = original.currentOrder.makeWay === 'EXTERNAL' && original.currentOrder.factoryId ? original.currentOrder : null
const form = reactive<ConvertForm>({ version: original.version, codeMode: 'AUTO', code: '', styleClass: '', name: original.name,
  aliasName: '', defaultFactoryId: passedFactory?.factoryId ?? original.sampleFactoryId })
const factoryName = ref(passedFactory?.factoryName ?? original.sampleFactoryName)
const scope = () => visible.value ? `${props.sample.companyId}:${props.sample.id}:${props.sample.version}` : null
const allowed = computed(() => props.sample.actions.convert && user.hasPerm('dev:sample:convert'))
const guard = useUnsavedChanges(form, visible, { scope })
useSampleContext(() => { visible.value = false })
const action = useSampleAction<ConvertForm, ConvertResult>(scope, () => allowed.value,
  (body, options) => sampleApi.convert(original.id, original.companyId, body, options))
const result = shallowRef<ConvertPreview | null>(null), loading = ref(false), error = ref<string | null>(null), inputError = ref<string | null>(null)
const readPreview = useSampleRead(); let timer: ReturnType<typeof setTimeout> | undefined
async function preview() {
  clearTimeout(timer); const read = readPreview.begin(); result.value = null; error.value = null; loading.value = false
  if (!form.styleClass || !allowed.value) return
  loading.value = true
  try { const next = await sampleApi.convertPreview(original.id, original.companyId, form.styleClass, { signal: read.signal, silent: true }); if (read.current()) result.value = next }
  catch (cause) { if (read.current()) error.value = sampleError(cause) }
  finally { if (read.current()) loading.value = false }
}
watch(() => classes.enabled.value, (choices) => { if (!form.styleClass && choices[0]) { form.styleClass = choices[0].value; guard.markSaved() } }, { immediate: true })
watch(() => form.styleClass, () => { readPreview.cancel(); result.value = null; clearTimeout(timer); timer = setTimeout(preview, 200) }, { immediate: true })
onBeforeUnmount(() => clearTimeout(timer))
async function save() {
  inputError.value = null
  if (!form.name.trim() || !form.styleClass || !result.value || loading.value || error.value) { inputError.value = '填写名称、大类并成功读取转换预览'; return }
  if (form.codeMode === 'AUTO' && (!result.value.code || result.value.codeError)) { inputError.value = '无法自动编款号，请先修改配置或选择手填款号'; return }
  if (form.codeMode === 'MANUAL' && !form.code?.trim()) { inputError.value = '请填写款号'; return }
  const receipt = await action.execute({ ...form, name: form.name.trim(), code: form.codeMode === 'MANUAL' ? form.code!.trim() : null }, original.companyId)
  if (receipt) { guard.discardDraft(); visible.value = false; emit('saved', receipt) }
}
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" title="转大货" width="560px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p class="dim">{{ original.code }} · {{ original.name }}</p>
    <el-alert v-if="!allowed" title="当前状态或权限不允许转大货" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="!allowed || action.locked.value">
      <el-form-item label="大类" required><DictSelect v-model="form.styleClass" type="style_class" /></el-form-item>
      <el-form-item label="款号"><el-segmented v-model="form.codeMode" :options="[{ label: '自动编号', value: 'AUTO' }, { label: '手填款号', value: 'MANUAL' }]" /></el-form-item>
      <el-input v-if="form.codeMode === 'MANUAL'" v-model="form.code" maxlength="32" placeholder="填写款号" aria-label="转换款号" />
      <p v-else-if="result?.code" class="code">预览 {{ result.code }}</p>
      <p v-if="result?.codeError" class="warning" role="status">{{ result.codeError }}；可切换到手填款号。</p>
      <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" /></el-form-item>
      <el-form-item label="别名"><el-input v-model="form.aliasName" maxlength="100" /></el-form-item>
      <el-form-item label="默认加工厂"><SupplierSelect v-model="form.defaultFactoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" usage="SAMPLE" clearable /></el-form-item>
    </el-form>
    <SampleFeedback :loading="loading" :error="error" label="转大货预览" @retry="preview" />
    <p v-if="result" class="dim">{{ original.colors.length }} 色 × {{ original.sizes.length }} 码，将生成 {{ result.skuCount }} 个 SKU。<br>{{ result.devPack ? `研发 V${result.devPack.version} 移交，并复制为生产 V1 草稿，核对后再生效。` : '当前没有生效的研发资料包，转款后仍需建立生产资料包。' }}</p>
    <SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" />
    <template #footer><el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :disabled="!allowed || !result || loading || !!error" :loading="action.busy.value" @click="save">{{ action.uncertain.value ? '重试原提交' : '生成款式' }}</el-button></template>
  </el-dialog>
</template>
<style scoped>.warning { color: var(--lm-pending-text); font-size: 12px; }</style>
