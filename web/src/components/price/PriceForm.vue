<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormItemRule } from 'element-plus'
import { priceApi } from '@/api/price'
import { materialApi } from '@/api/basic'
import type { MaterialPickRow } from '@/types/basic'
import type { StylePickRow } from '@/types/style'
import { PRICE_TYPE_LABEL, type CurrentPrice, type PriceRow, type PriceType } from '@/types/price'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { formatPrice } from '@/utils/format'
import { recordableTypes, shortDay, skuLabel, styleLabel, today, type PriceSubject } from './price'

/**
 * 录价格 / 调价的对话框（RFC 006 §9，原型 5e-price.js 的 PriceForm）。
 * - 从哪一页打开就定住哪一项：物料页定物料（只能录物料价），供应商页定供应商，款式页定款式（只能录加工价）
 * - 调价：从现价那一行点进来，组合（供应商、物料 / SKU、款式 / 加工方式）全锁住，只改价、税、生效日期、原因
 * - SKU 不选 = 这个物料所有颜色同价（物料级）。默认不选：大多数面辅料各颜色同价，给某个颜色单独定价才选它
 * - 选好组合就去取那一天的现价：和现价同一层级（都是物料级，或都是这个 SKU）的，改价要写原因、价一样不用录；
 *   现价是物料级、这次给 SKU 单独定价的，原因可以不写（和后端 PriceServiceImpl.record 一样）
 * - 税率默认取供应商的（换供应商就换），调价时沿用那一条的
 *
 *   <PriceForm ref="form" @saved="reload" />
 *   form.value?.open({ kind: 'material', id, code, name })          录价格
 *   form.value?.open(subject, row)                                    调价（row 是现价那一行）
 *
 * 第 4c 步：款式 —— 款式那一格现在只有"定住"的样子（款式页打开、调价）；CAN_PICK_STYLE 改成 true 时，
 * 在下面 v-else 那里接 <DataPick kind="style" single>，选中后填 form.styleId 和 names.style
 */
const emit = defineEmits<{ saved: [] }>()
const user = useUserStore()
const units = useDict('unit')

const visible = ref(false)
const formRef = ref<FormInstance>()
const subject = ref<PriceSubject | null>(null)
/** 调价：现价那一行 */
const adjusting = ref<PriceRow | null>(null)
/** 这次能录哪几种（权限 + 从哪一页打开） */
const types = ref<PriceType[]>([])

const blank = () => ({
  type: 'MATERIAL' as PriceType,
  supplierId: null as number | null,
  materialId: null as number | null,
  materialSkuId: null as number | null,
  styleId: null as number | null,
  processMode: null as string | null,
  price: null as number | null,
  taxPct: null as number | null,
  taxIncluded: true,
  freightIncluded: true,
  effectiveDate: today(),
  reason: '',
  remark: '',
})
const form = reactive(blank())
const guard = useUnsavedChanges(form, visible)

/** 定住的那几项显示的名字 */
const names = reactive({ supplier: '', material: '', materialCode: '', sku: '', style: '' })

const isMaterial = computed(() => form.type === 'MATERIAL')
const supplierFixed = computed(() => !!adjusting.value || subject.value?.kind === 'supplier')
const materialFixed = computed(() => !!adjusting.value || subject.value?.kind === 'material')
const styleFixed = computed(() => !!adjusting.value || subject.value?.kind === 'style')

function open(from: PriceSubject, row?: PriceRow) {
  Object.assign(form, blank())
  Object.assign(names, { supplier: '', material: '', materialCode: '', sku: '', style: '' })
  subject.value = from
  adjusting.value = row ?? null
  current.value = null
  skus.value = []
  taxFor = null
  if (row) {
    types.value = [row.type]
    Object.assign(form, {
      type: row.type, supplierId: row.supplierId, materialId: row.materialId, materialSkuId: row.materialSkuId,
      styleId: row.styleId, processMode: row.processMode,
      taxPct: row.taxPct, taxIncluded: row.taxIncluded, freightIncluded: row.freightIncluded,
    })
    taxFor = row.supplierId
    names.supplier = row.supplierName ?? ''
    names.material = row.materialId ? `${row.materialCode} ${row.materialName}` : ''
    names.sku = row.materialSkuId ? `${skuLabel(row)}（${row.skuCode}）` : '所有颜色同价'
    names.style = row.styleId ? styleLabel(row) : ''
  } else {
    types.value = recordableTypes(from.kind, user.hasPerm)
    form.type = types.value[0] ?? 'MATERIAL'
    if (from.kind === 'supplier') {
      form.supplierId = from.id
      names.supplier = from.name
    } else if (from.kind === 'material') {
      form.materialId = from.id
      names.material = `${from.code} ${from.name}`
      names.materialCode = from.code
      void loadSkus()
    } else {
      form.styleId = from.id
      names.style = `${from.code} ${from.name}`
    }
  }
  visible.value = true
  void loadCurrent()
}

defineExpose({ open })

// ---------- 物料、SKU ----------
const picking = ref(false)
/** 这个物料启用的 SKU（从选物料的接口取，和 DataPick 一样只列启用的） */
const skus = ref<MaterialPickRow[]>([])
let skuSeq = 0

async function loadSkus() {
  const mine = ++skuSeq
  const id = form.materialId
  skus.value = []
  if (!id || !names.materialCode) return
  const result = await materialApi.pick({ keyword: names.materialCode, page: 1, size: 500 })
  if (mine === skuSeq) skus.value = result.list.filter((row) => row.materialId === id)
}

function onPick(rows: MaterialPickRow[] | StylePickRow[]) {
  const row = (rows as MaterialPickRow[])[0]
  if (!row || row.materialId === form.materialId) return
  form.materialId = row.materialId
  form.materialSkuId = null
  names.material = `${row.materialCode} ${row.materialName}`
  names.materialCode = row.materialCode
  void loadSkus()
}

const skuOptions = computed(() =>
  skus.value.map((row) => ({
    value: row.skuId,
    label: [row.colorName, row.spec].filter(Boolean).join(' · ') || row.skuCode,
    code: row.skuCode,
  })),
)

// ---------- 现价、单位、税率 ----------
const current = ref<CurrentPrice | null>(null)
let currentSeq = 0
/** 税率是按哪家供应商带的：换了供应商才重新带，不盖掉手改的 */
let taxFor: number | null = null

async function loadCurrent() {
  const mine = ++currentSeq
  if (!visible.value || !form.supplierId) {
    current.value = null
    return
  }
  const material = isMaterial.value
  try {
    const result = await priceApi.current({
      type: form.type,
      supplierId: form.supplierId,
      materialId: material ? form.materialId ?? undefined : undefined,
      materialSkuId: material ? form.materialSkuId ?? undefined : undefined,
      styleId: material ? undefined : form.styleId ?? undefined,
      processMode: material ? undefined : form.processMode ?? undefined,
      date: form.effectiveDate,
    }, { silent: true })
    if (mine !== currentSeq) return
    current.value = result
    if (taxFor !== form.supplierId) {
      // 打开时自动带出来的不算"改过"，关掉不用问
      const untouched = !guard.dirty.value
      taxFor = form.supplierId
      form.taxPct = result.supplierTaxPct
      if (untouched) guard.markSaved()
    }
  } catch {
    if (mine === currentSeq) current.value = null
  }
}

watch(
  () => [form.type, form.supplierId, form.materialId, form.materialSkuId, form.styleId, form.processMode, form.effectiveDate],
  () => void loadCurrent(),
)

const unitLabel = computed(() => {
  if (!isMaterial.value) return units.labelOf('PIECE') || '件'
  const unit = current.value?.unit ?? adjusting.value?.unit ?? skus.value[0]?.purchaseUnit
  return unit ? units.labelOf(unit) : '采购单位'
})

/** 那一天的现价，和这次要录的是不是同一层级（同一层级才算调价） */
const now = computed(() => current.value?.current ?? null)
const sameLevel = computed(() => !!now.value && (!isMaterial.value || (now.value.materialSkuId ?? null) === (form.materialSkuId ?? null)))
const samePrice = computed(() => sameLevel.value && form.price != null && Number(now.value!.price) === Number(form.price))

/** 对话框里的那一行提示 */
const hint = computed(() => {
  if (!form.supplierId || (isMaterial.value ? !form.materialId : !form.styleId || !form.processMode)) return null
  const day = shortDay(form.effectiveDate)
  const c = now.value
  if (!c) return { tone: 'dim', text: `${day} 还没有这家的价：第一次报价，原因可以不写` }
  const was = `现价 ${formatPrice(c.price)}（${shortDay(c.effectiveDate)} 起${c.materialSkuId ? '，这个颜色单独定的' : ''}）`
  if (!sameLevel.value) return { tone: 'dim', text: `${was}是所有颜色同价的：给这个颜色单独定价，原因可以不写` }
  if (samePrice.value) return { tone: 'danger', text: `${day} 的${was}，和这次一样，不用再录` }
  return { tone: 'pending', text: form.price != null ? `${was}→ ${formatPrice(form.price)}：调价要写原因` : `${was}：调价要写原因` }
})

// ---------- 校验、保存 ----------
const required = (message: string, when: () => boolean): FormItemRule => ({
  validator: (_rule, value, callback) => (when() && (value == null || value === '') ? callback(new Error(message)) : callback()),
  trigger: 'change',
})
const rules = computed(() => ({
  supplierId: [required(isMaterial.value ? '请选供应商' : '请选工厂', () => true)],
  materialId: [required('请选物料', () => isMaterial.value)],
  styleId: [required('请选款式', () => !isMaterial.value)],
  processMode: [required('请选加工方式', () => !isMaterial.value)],
  price: [
    required('请填单价', () => true),
    { validator: (_r: unknown, v: number | null, cb: (e?: Error) => void) => (v != null && !(v > 0) ? cb(new Error('单价要大于 0')) : cb()), trigger: 'change' },
  ],
  taxPct: [required('这家没设默认税率，请填', () => true)],
  reason: [{
    validator: (_r: unknown, v: string, cb: (e?: Error) => void) => (sameLevel.value && !v.trim() ? cb(new Error('和现价不一样，要写调价原因')) : cb()),
    trigger: 'blur',
  }],
}))

const saving = ref(false)

async function save() {
  // 没过的项旁边已经标红了，这里不再往外抛
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (samePrice.value) {
    ElMessage.warning('和现价一样，不用再录')
    return
  }
  const material = isMaterial.value
  saving.value = true
  try {
    await priceApi.record({
      type: form.type,
      supplierId: form.supplierId!,
      materialId: material ? form.materialId : null,
      materialSkuId: material ? form.materialSkuId : null,
      styleId: material ? null : form.styleId,
      processMode: material ? null : form.processMode,
      price: form.price!,
      taxPct: form.taxPct,
      taxIncluded: form.taxIncluded,
      freightIncluded: form.freightIncluded,
      effectiveDate: form.effectiveDate,
      reason: form.reason.trim() || null,
      remark: form.remark.trim() || null,
    })
    ElMessage.success(`记下了：${formatPrice(form.price)} 元 / ${unitLabel.value}，${shortDay(form.effectiveDate)} 起生效`)
    visible.value = false
    emit('saved')
  } finally {
    saving.value = false
  }
}

/** 换类型、换组合时，上一次的校验提示不对了 */
watch(() => form.type, () => formRef.value?.clearValidate())
</script>

<template>
  <el-dialog v-model="visible" :title="adjusting ? '调价' : '录价格'" width="min(600px, 96vw)" :before-close="guard.beforeClose" append-to-body>
    <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
      <el-form-item v-if="types.length > 1" label="类型" class="full">
        <el-radio-group v-model="form.type">
          <el-radio-button v-for="t in types" :key="t" :value="t">{{ PRICE_TYPE_LABEL[t] }}</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item :label="isMaterial ? '供应商' : '工厂'" prop="supplierId">
        <div v-if="supplierFixed" class="fixed">{{ names.supplier }}</div>
        <SupplierSelect v-else v-model="form.supplierId" />
      </el-form-item>

      <template v-if="isMaterial">
        <el-form-item label="物料" prop="materialId">
          <div v-if="materialFixed" class="fixed">{{ names.material }}</div>
          <el-button v-else class="pick" @click="picking = true">
            <span v-if="names.material">{{ names.material }}</span>
            <span v-else class="dim">从物料库选</span>
          </el-button>
        </el-form-item>
        <el-form-item label="颜色 / SKU">
          <div v-if="adjusting" class="fixed">{{ names.sku }}</div>
          <el-select v-else v-model="form.materialSkuId" filterable clearable :disabled="!form.materialId" placeholder="不选 = 所有颜色同价" no-data-text="这个物料没有启用的 SKU" :value-on-clear="null">
            <el-option v-for="o in skuOptions" :key="o.value" :label="o.label" :value="o.value">
              {{ o.label }} <span class="code dim">{{ o.code }}</span>
            </el-option>
          </el-select>
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item label="款式" prop="styleId">
          <div v-if="styleFixed" class="fixed">{{ names.style }}</div>
          <!-- 第 4c 步：款式 —— 这里接 <DataPick kind="style" single>（CAN_PICK_STYLE 为 false 时走不到这里） -->
        </el-form-item>
        <el-form-item label="加工方式" prop="processMode">
          <DictSelect v-model="form.processMode" type="process_mode" :disabled="!!adjusting" />
        </el-form-item>
      </template>

      <el-form-item :label="`单价（元 / ${unitLabel}）`" prop="price">
        <el-input-number v-model="form.price" :min="0" :controls="false" :value-on-clear="null" placeholder="如 18.5" />
      </el-form-item>
      <el-form-item label="税率（%）" prop="taxPct">
        <el-input-number v-model="form.taxPct" :min="0" :max="100" :precision="2" :controls="false" :value-on-clear="null" />
      </el-form-item>
      <el-form-item label="生效日期">
        <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
      </el-form-item>
      <el-form-item label="价格里含">
        <el-checkbox v-model="form.taxIncluded">含税</el-checkbox>
        <el-checkbox v-model="form.freightIncluded">含运费</el-checkbox>
      </el-form-item>

      <p v-if="hint" :class="['hint', 'full', hint.tone]">{{ hint.text }}</p>

      <el-form-item label="调价原因" prop="reason" class="full">
        <el-input v-model="form.reason" maxlength="255" :placeholder="sameLevel ? '必填，如：原料涨价' : '第一次报价可以不写'" />
      </el-form-item>
      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" maxlength="255" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">记下这条价格</el-button>
    </template>
  </el-dialog>

  <DataPick v-model="picking" kind="sku" single @pick="onPick" />
</template>

<style scoped>
.form-grid :deep(.el-input-number),
.form-grid :deep(.el-date-editor.el-input),
.form-grid :deep(.el-select) {
  width: 100%;
}

.form-grid :deep(.el-input-number .el-input__inner) {
  text-align: left;
}

/* 定住的项：和输入框一样高，看得出是这一项、但不能改 */
.fixed {
  display: flex;
  width: 100%;
  min-height: 32px;
  align-items: center;
  padding: 0 11px;
  border-radius: var(--lm-radius);
  background: var(--lm-subtle);
  color: var(--lm-ink);
}

.pick {
  width: 100%;
  justify-content: flex-start;
}

.hint {
  margin: -4px 0 12px;
  font-size: 13px;
}

.hint.dim {
  color: var(--lm-muted);
}

.hint.pending {
  color: var(--lm-pending-text);
}

.hint.danger {
  color: var(--lm-danger-text);
}
</style>
