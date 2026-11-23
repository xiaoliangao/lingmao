<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { attachmentBody, categoryApi, colorApi, sizeGroupApi } from '@/api/basic'
import { sampleApi } from '@/api/sample'
import type { ColorOption, SizeGroup, TreeNode } from '@/types/basic'
import type { FileVO } from '@/types/system'
import type { SampleActionResult, SampleCreateForm, SampleCreated, SampleDetail, SampleFields, SampleUpdateForm } from '@/types/sample'
import { useUserStore } from '@/stores/user'
import { useDict } from '@/composables/useDict'
import { useFieldAccess } from '@/composables/useFieldAccess'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { businessToday, addBusinessDays } from '@/utils/businessDate'
import { ApiError } from '@/utils/request'
import DictSelect from '@/components/DictSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import SampleFeedback from './SampleFeedback.vue'
import { useSampleAction } from './useSampleAction'
import { sampleError, useSampleContext, useSampleRead } from './sampleScope'

const props = defineProps<{ initialCompanyId: string; sample?: SampleDetail }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ created: [value: SampleCreated]; saved: [value: SampleActionResult] }>()
const user = useUserStore(), { canSeeCost } = useFieldAccess()
const sourceOptions = useDict('style_source')
const original = props.sample
const form = reactive({ companyId: original?.companyId ?? props.initialCompanyId as string | null,
  name: original?.name ?? '', brand: original?.brand ?? '', categoryId: original?.categoryId ?? null as string | null,
  year: original?.year ?? Number(businessToday().slice(0, 4)) + 1, season: original?.season ?? '', source: original?.source ?? 'OWN',
  wave: original?.wave ?? '', designerId: original ? original.designerId : user.info?.id ?? null,
  patternMakerId: original?.patternMakerId ?? null, sizeGroupId: original?.sizeGroupId ?? null as string | null,
  colorIds: original ? [...original.colorIds] : [] as string[], customerId: original?.customerId ?? null,
  customerStyleNo: original?.customerStyleNo ?? '', sampleFactoryId: original?.sampleFactoryId ?? null,
  fabricSupplierId: original?.fabricSupplierId ?? null, fabricDesc: original?.fabricDesc ?? '',
  expectFinishDate: original?.expectFinishDate ?? '', estTagPrice: original?.estTagPrice ?? '',
  estCost: canSeeCost.value ? original?.estCost ?? '' : '', remark: original?.remark ?? '',
  needDate: addBusinessDays(businessToday(), 10), images: [] as FileVO[] })
const allowedSources = computed(() => sourceOptions.options.value.filter((item) => item.value !== 'CUSTOMER_SAMPLE'
  && (item.status === 1 || item.value === original?.source)))
watch(sourceOptions.options, (options) => {
  if (!original && options.length && !options.some((item) => item.value === form.source && item.status === 1 && item.value !== 'CUSTOMER_SAMPLE')) {
    form.source = options.find((item) => item.status === 1 && item.value !== 'CUSTOMER_SAMPLE')?.value ?? ''
  }
})
const names = reactive({ designer: original ? original.designerName : user.info?.nickname ?? null, patternMaker: original?.patternMakerName ?? null,
  factory: original?.sampleFactoryName ?? null, fabric: original?.fabricSupplierName ?? null })
const allowed = computed(() => user.hasPerm(original ? 'dev:sample:edit' : 'dev:sample:add') && (!props.sample || props.sample.actions.edit))
const contextKey = () => visible.value ? `${props.sample?.companyId ?? form.companyId}:${props.sample?.id ?? 'new'}:${props.sample?.version ?? 0}` : null
const categories = shallowRef<TreeNode[]>([]), sizes = shallowRef<SizeGroup[]>([]), colors = shallowRef<ColorOption[]>([])
const ready = ref(false), loading = ref(false), loadError = ref<string | null>(null), inputError = ref<string | null>(null), keyword = ref('')
const readOptions = useSampleRead(() => { ready.value = false; visible.value = false })
const context = useSampleContext(() => { visible.value = false })
const guard = useUnsavedChanges({ form, names }, visible, { scope: contextKey, ready: () => ready.value })
const action = useSampleAction<SampleCreateForm | SampleUpdateForm, SampleCreated | SampleActionResult>(contextKey, () => allowed.value,
  (body, options) => original
    ? sampleApi.update(original.id, original.companyId, body as SampleUpdateForm, options)
    : sampleApi.create(body as SampleCreateForm, options))
const selectedSize = computed(() => sizes.value.find((item) => item.id === form.sizeGroupId))
const shownColors = computed(() => {
  const q = keyword.value.trim().toLocaleUpperCase()
  return colors.value.filter((color) => !q || form.colorIds.includes(color.id) || `${color.code} ${color.name}`.toLocaleUpperCase().includes(q))
})
const fixedStructure = computed(() => !!original && (original.currentOrder.sampleVersion !== 1 || original.currentOrder.status !== 'WAIT_ASSIGN'))
async function loadOptions() {
  const read = readOptions.begin(); loading.value = true; loadError.value = null; ready.value = false
  try {
    const [tree, groups, choices] = await read.run(() => Promise.all([categoryApi.enabledTree(), sizeGroupApi.options(), colorApi.options()]))
    if (!read.current()) return
    categories.value = tree; sizes.value = groups; colors.value = choices
    if (original) {
      const contains = (nodes: TreeNode[]): boolean => nodes.some((node) => node.id === original.categoryId || contains(node.children ?? []))
      if (!contains(tree)) categories.value = [...tree, { id: original.categoryId, name: `${original.categoryName}（原品类）`, level: 1, sort: 0, status: 0 }]
      if (!sizes.value.some((group) => group.id === original.sizeGroupId)) sizes.value = [...groups, { id: original.sizeGroupId, name: `${original.sizeGroupName}（原尺码组）`, sizes: original.sizes, baseSize: original.baseSize, status: 0 }]
      const currentIds = new Set(choices.map((color) => color.id))
      colors.value = [...choices, ...original.colors.filter((color) => !currentIds.has(color.id))]
    }
    ready.value = true; guard.markSaved()
  } catch (cause) { if (read.current()) loadError.value = sampleError(cause) }
  finally { if (read.current()) loading.value = false }
}
function toggleColor(id: string) {
  if (action.locked.value || !allowed.value || !ready.value) return
  if (form.colorIds.includes(id)) form.colorIds = form.colorIds.filter((current) => current !== id)
  else if (form.colorIds.length < 100) form.colorIds.push(id)
  else inputError.value = '最多选 100 个颜色'
}
function body(): SampleCreateForm | SampleUpdateForm {
  if (form.source === 'CUSTOMER_SAMPLE') throw new ApiError(400, '当前未启用客户来样')
  if (!form.companyId || !form.categoryId || !form.sizeGroupId || !form.name.trim() || !form.brand || !form.season || !form.source) throw new ApiError(400, '请填写主体、名称、品牌、品类、季节、来源和尺码组')
  if (!Number.isInteger(form.year) || form.year < 2000 || form.year > 2099) throw new ApiError(400, '年份须在 2000–2099')
  if (!form.colorIds.length || form.colorIds.length > 100 || form.colorIds.some((id) => !colors.value.some((color) => color.id === id))) throw new ApiError(400, '请选择 1–100 个有效颜色')
  const group = selectedSize.value
  if (!group || !group.sizes.length || group.sizes.length > 100 || group.sizes.length * form.colorIds.length > 10000) throw new ApiError(400, '尺码须为 1–100 个，颜色 × 尺码不能超过 10000')
  if (form.estTagPrice && !/^(0|[1-9]\d{0,11})(\.\d{1,2})?$/.test(form.estTagPrice)) throw new ApiError(400, '预估吊牌价须为非负金额，最多 12 位整数、2 位小数')
  if (canSeeCost.value && form.estCost && !/^(0|[1-9]\d{0,9})(\.\d{1,4})?$/.test(form.estCost)) throw new ApiError(400, '预估成本须为非负金额，最多 10 位整数、4 位小数')
  const fields: SampleFields = { companyId: form.companyId, name: form.name.trim(), brand: form.brand, categoryId: form.categoryId,
    year: form.year, season: form.season, source: form.source, sizeGroupId: form.sizeGroupId, colorIds: [...form.colorIds],
    wave: form.wave || null, designerId: form.designerId, patternMakerId: form.patternMakerId, customerId: null,
    customerStyleNo: null, sampleFactoryId: form.sampleFactoryId, fabricSupplierId: form.fabricSupplierId,
    fabricDesc: form.fabricDesc || null, expectFinishDate: form.expectFinishDate || null, estTagPrice: form.estTagPrice || null,
    remark: form.remark || null }
  if (canSeeCost.value) fields.estCost = form.estCost || null
  if (original) return { ...fields, version: original.version }
  if (!form.needDate) throw new ApiError(400, '请选择首版要样日期')
  return { ...fields, needDate: form.needDate, images: attachmentBody(form.images) }
}
async function save() {
  inputError.value = null
  if (!ready.value || action.busy.value || !allowed.value) return
  const current = context.capture()
  if (!await guard.waitForUploads() || !current()) return
  try {
    const result = await action.execute(body(), original?.companyId ?? form.companyId!)
    if (!result || !current()) return
    guard.discardDraft(); visible.value = false
    if ('currentOrderId' in result) emit('created', result)
    else emit('saved', result)
  } catch (cause) { if (current()) inputError.value = sampleError(cause) }
}
watch(canSeeCost, (value) => { if (!value) form.estCost = '' })
onMounted(loadOptions)
</script>
<template>
  <el-dialog v-model="visible" class="sample-dialog" :title="original ? `编辑样衣 ${original.code}` : '新建样衣'" width="760px" append-to-body :close-on-click-modal="false" :before-close="guard.beforeClose">
    <SampleFeedback :loading="loading" :error="loadError" label="基础资料" @retry="loadOptions" />
    <el-alert v-if="!allowed" title="当前状态或权限不允许修改；输入已保留" type="warning" :closable="false" />
    <el-form label-position="top" class="sample-form" :disabled="!ready || !allowed || action.locked.value">
      <el-form-item label="主体公司" required><CompanySelect v-model="form.companyId" :disabled="fixedStructure" aria-label="样衣主体" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.name" maxlength="100" aria-label="样衣名称" /></el-form-item>
      <el-form-item label="品牌" required><DictSelect v-model="form.brand" type="brand" /></el-form-item>
      <el-form-item label="品类" required><el-tree-select v-model="form.categoryId" :data="categories" node-key="id" :props="{ label: 'name', children: 'children' }" filterable aria-label="样衣品类" /></el-form-item>
      <el-form-item label="年份" required><el-input-number v-model="form.year" :min="2000" :max="2099" :precision="0" :controls="false" /></el-form-item>
      <el-form-item label="季节" required><DictSelect v-model="form.season" type="season" /></el-form-item>
      <el-form-item label="来源" required><el-select v-model="form.source"><el-option v-for="item in allowedSources" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
      <el-form-item label="波段"><DictSelect v-model="form.wave" type="wave" clearable /></el-form-item>
      <el-form-item label="设计师"><UserSelect v-model="form.designerId" v-model:label="names.designer" clearable /></el-form-item>
      <el-form-item label="版师"><UserSelect v-model="form.patternMakerId" v-model:label="names.patternMaker" clearable /></el-form-item>
      <el-form-item label="尺码组" required><el-select v-model="form.sizeGroupId" :disabled="fixedStructure" aria-label="尺码组"><el-option v-for="group in sizes" :key="group.id" :label="group.name" :value="group.id" /></el-select><span v-if="selectedSize" class="dim small">{{ selectedSize.sizes.join(' / ') }} · 基码 {{ selectedSize.baseSize }}</span></el-form-item>
      <el-form-item v-if="!original" label="首版要样日期" required><el-date-picker v-model="form.needDate" value-format="YYYY-MM-DD" type="date" aria-label="首版要样日期" /></el-form-item>
      <el-form-item class="full" label="颜色" required><div class="colors"><el-input v-model="keyword" clearable placeholder="搜色号、名称" aria-label="样衣颜色搜索" /><div class="chips"><el-check-tag v-for="color in shownColors" :key="color.id" :checked="form.colorIds.includes(color.id)" :disabled="action.locked.value" @change="toggleColor(color.id)"><i class="swatch" :style="{ background: color.hex ?? undefined }" />{{ color.code }} {{ color.name }}</el-check-tag></div><span class="dim small">已选 {{ form.colorIds.length }} 色 · {{ selectedSize?.sizes.length ?? 0 }} 码；打样件数在每版单据填写，不分配到色码。</span></div></el-form-item>
      <el-form-item label="打样工厂"><SupplierSelect v-model="form.sampleFactoryId" v-model:label="names.factory" :category="['GARMENT', 'PROCESSOR']" usage="SAMPLE" clearable /></el-form-item>
      <el-form-item label="面料供应商"><SupplierSelect v-model="form.fabricSupplierId" v-model:label="names.fabric" category="FABRIC" usage="SAMPLE" clearable /></el-form-item>
      <el-form-item label="预估吊牌价"><el-input v-model="form.estTagPrice" inputmode="decimal" placeholder="金额，可留空" aria-label="预估吊牌价" /></el-form-item>
      <el-form-item v-if="canSeeCost" label="预估成本"><el-input v-model="form.estCost" inputmode="decimal" placeholder="金额，可留空" aria-label="预估成本" /></el-form-item>
      <el-form-item label="预计完成日期"><el-date-picker v-model="form.expectFinishDate" value-format="YYYY-MM-DD" type="date" clearable /></el-form-item>
      <el-form-item class="full" label="面料描述"><el-input v-model="form.fabricDesc" type="textarea" maxlength="255" /></el-form-item>
      <el-form-item class="full" label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      <el-form-item v-if="!original" class="full" label="设计稿、参考图"><ImageUpload v-model="form.images" :max="20" :disabled="action.locked.value || !allowed" /></el-form-item>
    </el-form>
    <p v-if="original" class="dim small">已派单或已有研发资料包时不可换主体、尺码组。旧版选色保留快照，当前版选择由服务器校验。</p>
    <template #footer>
      <div v-if="inputError || action.error.value" class="submit-feedback"><SampleFeedback :error="inputError || action.error.value" :uncertain="action.uncertain.value" @retry="save" /></div>
      <el-button :disabled="action.busy.value" @click="visible = false">取消</el-button><el-button type="primary" :loading="action.busy.value" :disabled="!ready || !allowed" @click="save">{{ action.uncertain.value ? '重试原提交' : original ? '保存' : '建样衣' }}</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.sample-form { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0 18px; }
.sample-form .full { grid-column: 1 / -1; }
.sample-form :deep(.el-select), .sample-form :deep(.el-date-editor), .sample-form :deep(.el-input-number) { width: 100%; }
.small { font-size: 11.5px; }
.submit-feedback { flex: 1; min-width: 0; text-align: left; }
.submit-feedback :deep(.sample-feedback) { margin: 0; }
.colors { width: 100%; }
.colors > .el-input { width: 220px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; max-height: 168px; overflow-y: auto; margin: 8px 0; }
.swatch { display: inline-block; width: 12px; height: 12px; border: 1px solid var(--lm-line); border-radius: 3px; margin-right: 4px; }
@media (max-width: 680px) { .sample-form { grid-template-columns: minmax(0, 1fr); } }
</style>
