<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { qcApi } from '@/api/quality'
import DictSelect from '@/components/DictSelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import StatusTag from '@/components/StatusTag.vue'
import { reloadOnConflict, todayStr } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { QC_KIND, QC_RESULT, qcResultLabel, type QcKind, type QcOrderOption, type QcResult, type QcRow, type QcSource } from '@/types/quality'
import type { FileVO } from '@/types/system'
import { formatDate, formatMoney, formatQty } from '@/utils/format'

/**
 * 验货对话框（RFC 019 §6.1）：记一条质检、改一条。
 *
 *   <QcInspectDialog v-model="open" :target="{ kind: 'FINAL', sourceId }" @saved="reload" />   待检里点「验货」
 *   <QcInspectDialog v-model="open" :target="{ kind: 'MIDDLE' }" @saved="reload" />            页头「记产前样、中检」，先选生产单
 *   <QcInspectDialog v-model="open" :record="row" @saved="reload" />                           改
 *
 * 新建先取来源单（批量、AQL 建议、前几次的检验）；现在不能验的后端报 1000（说先做什么），对话框关掉。
 * 抽检数默认 AQL 建议；改不合格数时结论跟着建议（超过允收数判不合格），选了让步接收就不再跟。
 * 质量扣款要扣款登记的权限；扣款进了对账单的金额不能改
 */
const props = defineProps<{
  /** 新建：kind + 来源单 id；产前样、中检不带 sourceId，在对话框里选生产单 */
  target?: { kind: QcKind; sourceId?: string } | null
  /** 改 */
  record?: QcRow | null
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [id: string] }>()

const user = useUserStore()
const canDeduct = computed(() => user.hasPerm('settle:deduction:edit'))
const unitLabel = useDict('unit').labelOf

const source = ref<QcSource | null>(null)
const loadingSource = ref(false)
const saving = ref(false)
const orderKind = ref<'PRE_PRODUCTION' | 'MIDDLE'>('MIDDLE')
const orderOptions = ref<QcOrderOption[]>([])
const orderSearching = ref(false)
const pickedOrder = ref<string | null>(null)

const form = reactive({
  inspectDate: todayStr(),
  sampleQty: null as number | null,
  defectQty: null as number | null,
  result: 'PASS' as QcResult,
  defectTypes: [] as string[],
  note: '',
  photos: [] as FileVO[],
  deductAmount: null as number | null,
})
const guard = useUnsavedChanges(form, visible)
/** 结论是不是人自己选的：选了就不再按不合格数自动改 */
let resultTouched = false

const editing = computed(() => !!props.record)
const kind = computed<QcKind | null>(() => props.record?.kind ?? source.value?.kind ?? props.target?.kind ?? null)
const pickOrder = computed(() => !props.record && !!props.target && !props.target.sourceId)
const accept = computed(() => (editing.value ? props.record?.acceptQty : source.value?.aqlAccept) ?? null)
const locked = computed(() => !!props.record?.deductStatementNo)
/** 改的时候：已经安排返修的结论不能改 */
const resultLocked = computed(() => !!props.record?.reworkTime)
const title = computed(() => {
  if (props.record) return `改 ${props.record.no}`
  if (pickOrder.value) return '记产前样、中检'
  const s = source.value
  return s ? `${QC_KIND[s.kind]} · ${s.sourceNo}${s.attemptNo > 1 ? ` · 第 ${s.attemptNo} 次` : ''}` : '验货'
})
const resultOptions = computed(() => {
  const k = kind.value ?? 'FINAL'
  const values: QcResult[] = k === 'PRE_PRODUCTION' ? ['PASS', 'FAIL'] : ['PASS', 'FAIL', 'CONCESSION']
  return values.map((value) => ({ value, label: qcResultLabel(k, value), disabled: resultLocked.value && value !== 'FAIL' }))
})

watch(visible, (open) => {
  if (!open) return
  source.value = null
  pickedOrder.value = null
  orderOptions.value = []
  resultTouched = false
  const r = props.record
  if (r) {
    Object.assign(form, {
      inspectDate: r.inspectDate, sampleQty: r.sampleQty, defectQty: r.defectQty, result: r.result, defectTypes: [...r.defectTypes],
      note: r.note ?? '', photos: r.photos.map(({ url, thumbUrl, name, size }) => ({ url, thumbUrl, name, size })),
      deductAmount: r.deductAmount ?? null,
    })
    resultTouched = true
  } else {
    Object.assign(form, { inspectDate: todayStr(), sampleQty: null, defectQty: null, result: 'PASS', defectTypes: [], note: '', photos: [], deductAmount: null })
    if (props.target?.kind === 'PRE_PRODUCTION' || props.target?.kind === 'MIDDLE') orderKind.value = props.target.kind
    if (props.target?.sourceId) void loadSource(props.target.kind, props.target.sourceId)
    else void searchOrders('')
  }
  guard.markSaved()
})

async function loadSource(k: QcKind, id: string) {
  loadingSource.value = true
  try {
    const s = await qcApi.source(k, id)
    source.value = s
    if (form.sampleQty == null && s.aqlSample != null && k !== 'PRE_PRODUCTION') form.sampleQty = s.aqlSample
    if (form.defectQty == null && k !== 'PRE_PRODUCTION') form.defectQty = 0
    guard.markSaved()
  } catch {
    // 现在不能验（已经验过、在返修……）：拦截器已经弹了原因
    if (!pickOrder.value) visible.value = false
    else pickedOrder.value = null
  } finally {
    loadingSource.value = false
  }
}

async function searchOrders(keyword: string) {
  orderSearching.value = true
  try {
    orderOptions.value = await qcApi.orderOptions(keyword)
  } finally {
    orderSearching.value = false
  }
}

function onPickOrder(id: string | null) {
  source.value = null
  if (id) void loadSource(orderKind.value, id)
}

watch(orderKind, (k) => {
  // 产前样只有合格、不合格，也不按 AQL 抽检：切过来把中检时选的让步接收、带出的抽检数清掉
  if (k === 'PRE_PRODUCTION') {
    if (form.result === 'CONCESSION') form.result = 'FAIL'
    form.sampleQty = null
    form.defectQty = null
  }
  if (pickedOrder.value) void loadSource(orderKind.value, pickedOrder.value)
})

// 不合格数超过允收数建议判不合格（人自己选过结论就不跟）
watch(() => form.defectQty, (bad) => {
  if (resultTouched || accept.value == null || bad == null || kind.value === 'PRE_PRODUCTION') return
  form.result = bad > accept.value ? 'FAIL' : 'PASS'
})

function onResult(value: QcResult) {
  resultTouched = true
  if (value === 'PASS') form.deductAmount = null
}

const afterToday = (d: Date) => {
  const s = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return s > todayStr()
}

function check(): string | null {
  const k = kind.value
  if (!form.inspectDate) return '请选检验日期'
  if (k !== 'PRE_PRODUCTION' && !(form.sampleQty && form.sampleQty > 0)) return '抽检数要填'
  if (form.sampleQty != null && form.defectQty != null && form.defectQty > form.sampleQty) return '不合格数不能大于抽检数'
  if (form.result !== 'PASS' && !form.note.trim()) return '不合格、让步接收要写问题'
  return null
}

async function save() {
  const problem = check()
  if (problem) return void ElMessage.warning(problem)
  // 照片还在传就点了保存：等传完，不然记录上少图（agy T-77e556 P1-4）
  if (!(await guard.waitForUploads())) return
  const body = {
    inspectDate: form.inspectDate,
    sampleQty: form.sampleQty,
    defectQty: form.defectQty,
    result: form.result,
    defectTypes: form.defectTypes,
    note: form.note.trim() || null,
    photos: form.photos,
    deductAmount: form.result === 'PASS' ? null : form.deductAmount,
  }
  saving.value = true
  try {
    if (props.record) {
      await qcApi.update(props.record.id, { ...body, version: props.record.version }, props.record.companyId)
      ElMessage.success('改好了')
      guard.markSaved()
      visible.value = false
      emit('saved', props.record.id)
    } else {
      const s = source.value
      if (!s) return void ElMessage.warning(pickOrder.value ? '先选生产单' : '来源单还没取到')
      const created = await qcApi.create({ ...body, kind: s.kind, sourceId: s.sourceId }, s.companyId)
      ElMessage.success(`记好了：${created.no} ${qcResultLabel(s.kind, form.result)}${body.deductAmount ? `，扣 ${formatMoney(body.deductAmount)} 元` : ''}`)
      guard.markSaved()
      visible.value = false
      emit('saved', created.id)
    }
  } catch (e) {
    if (reloadOnConflict(e, () => { visible.value = false; emit('saved', props.record?.id ?? '') })) return
    throw e
  } finally {
    saving.value = false
  }
}

/** 头上的来源信息：新建取 source，改取记录本身 */
const info = computed(() => {
  const r = props.record
  if (r) return { partner: r.partnerName, target: r.targetDesc, lot: r.lotQty, unit: r.unit, sourceNo: r.sourceNo, kind: r.kind, attempt: r.attemptNo }
  const s = source.value
  return s ? { partner: s.partnerName, target: s.targetDesc, lot: s.lotQty, unit: s.unit, sourceNo: s.sourceNo, kind: s.kind, attempt: s.attemptNo } : null
})
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="min(640px, 94vw)" :before-close="guard.beforeClose">
    <div v-if="pickOrder" class="form-grid pick">
      <el-form-item label="检验" class="top">
        <el-segmented v-model="orderKind" :options="[{ label: '中检', value: 'MIDDLE' }, { label: '产前样确认', value: 'PRE_PRODUCTION' }]" />
      </el-form-item>
      <el-form-item label="生产单" class="top">
        <el-select v-model="pickedOrder" filterable remote :remote-method="searchOrders" :loading="orderSearching" clearable
          placeholder="单号、款号、款名、加工厂" style="width: 100%" @change="onPickOrder">
          <el-option v-for="o in orderOptions" :key="o.id" :value="o.id" :label="`${o.no} ${o.styleCode} ${o.styleName}`">
            <span class="code">{{ o.no }}</span> {{ o.styleCode }} {{ o.styleName }} <span class="dim">· {{ o.partnerName }}</span>
          </el-option>
        </el-select>
      </el-form-item>
    </div>

    <div v-loading="loadingSource">
      <dl v-if="info" class="props">
        <dt>{{ info.kind === 'INCOMING' ? '供应商' : '加工厂' }}</dt>
        <dd>{{ info.partner }}</dd>
        <dt>货品</dt>
        <dd>{{ info.target || '—' }}</dd>
        <dt>批量</dt>
        <dd>
          <template v-if="info.lot != null">{{ formatQty(info.lot) }} {{ unitLabel(info.unit) }}</template>
          <span v-else class="dim">几种单位，没合计</span>
          <span v-if="!editing && source?.aqlSample != null" class="dim"> · AQL 2.5 建议抽 {{ formatQty(source.aqlSample) }}，允收 {{ formatQty(source.aqlAccept) }}</span>
          <span v-else-if="editing && accept != null" class="dim"> · 允收 {{ formatQty(accept) }}</span>
        </dd>
        <template v-for="h in source?.history ?? []" :key="h.id">
          <dt>第 {{ h.attemptNo }} 次</dt>
          <dd>
            {{ formatDate(h.inspectDate) }} <StatusTag :tone="QC_RESULT[h.result].tone">{{ qcResultLabel(h.kind, h.result) }}</StatusTag>
            {{ h.note || '' }}
            <div v-if="h.reworkNote" class="dim small">返修：{{ h.reworkNote }}</div>
          </dd>
        </template>
      </dl>

      <el-form v-if="info" class="form-grid" label-position="top" @submit.prevent>
        <el-form-item label="检验日期" required>
          <el-date-picker v-model="form.inspectDate" value-format="YYYY-MM-DD" :disabled-date="afterToday" :clearable="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结论" required>
          <el-segmented v-model="form.result" :options="resultOptions" @change="onResult" />
        </el-form-item>
        <el-form-item :label="kind === 'PRE_PRODUCTION' ? '抽检数（选填）' : '抽检数'" :required="kind !== 'PRE_PRODUCTION'">
          <el-input-number v-model="form.sampleQty" :min="0" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="不合格数">
          <el-input-number v-model="form.defectQty" :min="0" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="问题类型" class="full">
          <DictSelect v-model="form.defectTypes" type="qc_defect_type" multiple clearable placeholder="选填，可以多选" style="width: 100%" />
        </el-form-item>
        <el-form-item label="问题" class="full" :required="form.result !== 'PASS'">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="1000"
            :placeholder="form.result === 'PASS' ? '选填' : '哪里不合格、多少件'" />
        </el-form-item>
        <el-form-item label="照片" class="full">
          <ImageUpload v-model="form.photos" :max="9" />
        </el-form-item>
        <el-form-item v-if="canDeduct && form.result !== 'PASS'" label="质量扣款（元）" class="full">
          <el-input-number v-model="form.deductAmount" :min="0.01" :max="999999999999" :precision="2" controls-position="right"
            :disabled="locked" placeholder="不扣钱就空着" style="width: 240px" />
          <span class="dim hint">
            {{ locked ? `已经进了对账单 ${record?.deductStatementNo}，金额不能改` : '填了同时登记一笔扣款，按检验日期进那个月的对账单' }}
          </span>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!info" @click="save">{{ editing ? '保存' : '记下结果' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.props {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 6px 12px;
  margin: 0 0 16px;
}

.props dt {
  color: var(--lm-muted);
}

.props dd {
  margin: 0;
}

.small {
  font-size: 12px;
}

.hint {
  margin-left: 10px;
  font-size: 12px;
}

.pick {
  margin-bottom: 8px;
}

.top :deep(.el-form-item__label) {
  display: block;
}
</style>
