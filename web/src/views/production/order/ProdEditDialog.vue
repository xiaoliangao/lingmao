<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '@/api/order'
import { productionApi as productionApiBase } from '@/api/production'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { reloadOnConflict } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { PROCESS_MODE_FULL, type ScheduleQuote } from '@/types/order'
import { PRODUCTION_KIND, type ProductionDetail } from '@/types/production'
import { formatPrice } from '@/utils/format'
import { orNull } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeProductionPrice } = useFieldAccess()
const productionApi = bindCompanyApi(productionApiBase, () => savedCompanyId.value ?? props.doc.companyId)

/**
 * 改生产单草稿（docs/api/05 §1.4"编辑"、§3.2.4、§4）。只改单头，件数不能改（要改就删草稿回订单重新排单）。
 *
 *   <ProdEditDialog v-model="editVisible" :doc="detail" @saved="refresh" />
 *
 * - 打开就按当前加工厂取一次现价（scheduleApi.quote，一次给齐每种加工方式），切加工方式不用再问；换加工厂再取，
 *   带出结算方式、税率，单价换成新加工方式的现价（档案里没有就留着原来的，让人自己看）
 * - 单价和档案现价按 4 位小数比，不一样才出"改价原因"（必填）；用现价时带 priceSourceId，手填的发 null
 * - 打开时的取价不算改动（现价不在 form 里），不会一打开就问"没保存"
 * - 1002（别人刚改过）：关掉对话框，详情重新取
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  doc: ProductionDetail
}>()

const emit = defineEmits<{
  saved: []
}>()

const modes = useDict('process_mode')
const prodTerms = useDict('prod_terms')
const saving = ref(false)
const savedCompanyId = ref<string>()
const quote = ref<ScheduleQuote | null>(null)
const quoting = ref(false)

const form = reactive({
  companyId: null as string | null,
  factoryId: null as string | null,
  factoryName: null as string | null,
  processMode: '',
  unitPrice: null as number | null,
  priceChangeReason: '',
  dueDate: '',
  merchId: null as string | null,
  merchName: null as string | null,
  settleMethod: null as string | null,
  taxPct: null as number | null,
  taxIncluded: true,
  terms: '',
  remark: '',
})

const guard = useUnsavedChanges(form, visible)
const kindMeta = computed(() => PRODUCTION_KIND[props.doc.kind])

/** 外发加工单：FULL 以外的；成品采购单固定包工包料、灰掉 */
const modeOptions = computed(() =>
  modes.enabled.value.filter((m) => (props.doc.kind === 'BUY' ? m.value === PROCESS_MODE_FULL : m.value !== PROCESS_MODE_FULL)),
)

/** 当前加工厂 × 加工方式的档案现价：取到 quote 用 quote；还没取到、而且没换厂没换方式，先用详情里的 */
const archive = computed<{ price?: number | null; priceSourceId: string | null } | null>(() => {
  if (quote.value) {
    const p = quote.value.prices?.find((x) => x.processMode === form.processMode)
    return { price: p?.price ?? null, priceSourceId: p?.priceSourceId ?? null }
  }
  if (form.factoryId === props.doc.factoryId && form.processMode === props.doc.processMode) {
    return { price: props.doc.archivePrice, priceSourceId: props.doc.priceSourceId }
  }
  return null
})

const priceChanged = computed(() => {
  const a = archive.value?.price
  return a !== null && a !== undefined && form.unitPrice !== null && Math.abs(form.unitPrice - a) > 0.00005
})

const priceTip = computed(() => {
  if (quoting.value) return '正在取档案现价…'
  const a = archive.value
  if (!a) return ''
  return a.price != null ? `档案现价 ${formatPrice(a.price)}` : '档案里没有这家的价，审核时记进去'
})

const factoryError = computed(() => (quote.value && !quote.value.usable ? (quote.value.reason ?? '不能下大货单') : ''))

let quoteSeq = 0
/** 取现价。applyPrice：换了加工厂时把单价换成现价、带出结算方式和税率；打开时只取不改 */
async function requote(applyPrice: boolean) {
  const seq = ++quoteSeq
  quote.value = null
  if (!form.factoryId) return
  quoting.value = true
  try {
    const q = await scheduleApi.quote(props.doc.orderId, props.doc.styleId, form.factoryId)
    if (seq !== quoteSeq) return
    quote.value = q
    if (applyPrice) {
      form.factoryName = q.factoryName
      form.settleMethod = q.settleMethod ?? form.settleMethod
      form.taxPct = q.taxPct ?? form.taxPct
      applyModePrice()
    }
  } catch {
    // 取不到现价：单价照填，后端审核时按档案校验
  } finally {
    if (seq === quoteSeq) quoting.value = false
  }
}

function applyModePrice() {
  const p = archive.value?.price
  if (p !== null && p !== undefined) {
    form.unitPrice = p
    form.priceChangeReason = ''
  }
}

function onFactoryChange(id: string | null | undefined) {
  form.factoryId = id ?? null
  void requote(true)
}

function init() {
  const d = props.doc
  savedCompanyId.value = d.companyId
  Object.assign(form, {
    companyId: d.companyId,
    factoryId: d.factoryId,
    factoryName: d.factoryShortName,
    processMode: d.processMode,
    unitPrice: d.unitPrice,
    priceChangeReason: d.priceChangeReason ?? '',
    dueDate: d.dueDate,
    merchId: d.merchId,
    merchName: d.merchName,
    settleMethod: d.settleMethod,
    taxPct: d.taxPct,
    taxIncluded: d.taxIncluded,
    terms: d.terms ?? '',
    remark: d.remark ?? '',
  })
  guard.markSaved()
  void requote(false)
}

watch(visible, (on) => {
  if (on) init()
})

function addTerm(value: string) {
  const text = prodTerms.options.value.find((o) => o.value === value)?.label
  if (text) form.terms = form.terms ? `${form.terms}\n${text}` : text
}

function warn(msg: string) {
  ElMessage.warning(msg)
  return false
}

function validate(): boolean {
  if (!form.companyId) return warn('选主体')
  if (!form.factoryId) return warn('选加工厂')
  if (factoryError.value) return warn(factoryError.value)
  if (!form.processMode) return warn('选加工方式')
  if (!canSeeProductionPrice.value && (form.factoryId !== props.doc.factoryId || form.processMode !== props.doc.processMode)) return warn('变更加工厂或加工方式需要确认加工价，请由有生产价格权限的人员办理')
  if (canSeeProductionPrice.value && (form.unitPrice == null || form.unitPrice <= 0)) return warn(`填${kindMeta.value.priceLabel}`)
  if (canSeeProductionPrice.value && priceChanged.value && !form.priceChangeReason.trim()) return warn('单价和档案现价不一样，写改价原因')
  if (!form.dueDate) return warn('填交期')
  if (!form.merchId) return warn('选生产跟单')
  if (!form.settleMethod) return warn('选结算方式')
  return true
}

async function save() {
  if (!validate() || !form.companyId || !form.factoryId) return
  saving.value = true
  try {
    await productionApi.update(props.doc.id, {
      version: props.doc.version,
      companyId: form.companyId,
      expectedCompanyId: savedCompanyId.value,
      factoryId: form.factoryId,
      processMode: form.processMode,
      unitPrice: form.unitPrice,
      priceSourceId: !priceChanged.value && archive.value?.price != null ? archive.value.priceSourceId : null,
      priceChangeReason: priceChanged.value ? form.priceChangeReason.trim() : null,
      dueDate: form.dueDate || null,
      merchId: form.merchId,
      settleMethod: form.settleMethod,
      taxPct: form.taxPct,
      taxIncluded: form.taxIncluded,
      terms: orNull(form.terms),
      remark: orNull(form.remark),
    })
    ElMessage.success(`${props.doc.no} 存好了`)
    guard.markSaved()
    visible.value = false
    emit('saved')
  } catch (e) {
    if (
      reloadOnConflict(e, () => {
        guard.markSaved()
        visible.value = false
        emit('saved')
      })
    )
      return
    throw e
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`编辑 ${doc.no}`"
    width="660px"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
  >
    <el-form label-position="top" class="form-grid">
      <el-form-item label="主体" required>
        <CompanySelect v-model="form.companyId" />
      </el-form-item>

      <el-form-item label="加工厂" required :error="factoryError">
        <SupplierSelect
          :model-value="form.factoryId"
          :label="form.factoryName"
          :category="['GARMENT', 'PROCESSOR']"
          usage="BULK"
          @update:model-value="onFactoryChange"
        />
      </el-form-item>

      <el-form-item label="加工方式" required>
        <el-select v-model="form.processMode" filterable :disabled="doc.kind === 'BUY'" @change="applyModePrice">
          <el-option v-for="m in modeOptions" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="canSeeProductionPrice" :label="`${kindMeta.priceLabel}（元/件）`" required>
        <el-input-number v-model="form.unitPrice" :min="0" :precision="4" :controls="false" :class="{ amber: priceChanged }" />
        <p v-if="priceTip" class="tip dim">{{ priceTip }}</p>
      </el-form-item>

      <el-form-item v-if="canSeeProductionPrice && priceChanged" label="改价原因" required class="span-all">
        <el-input v-model="form.priceChangeReason" maxlength="100" placeholder="单价和档案现价不一样：写为什么，审核时记进价格档案" />
      </el-form-item>

      <el-form-item label="交期" required>
        <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
      </el-form-item>

      <el-form-item label="生产跟单" required>
        <UserSelect v-model="form.merchId" :label="form.merchName" />
      </el-form-item>

      <el-form-item label="结算方式" required>
        <DictSelect v-model="form.settleMethod" type="settle_method" />
      </el-form-item>

      <el-form-item label="税率 %">
        <el-input-number v-model="form.taxPct" :min="0" :max="100" :precision="2" :controls="false" />
      </el-form-item>

      <el-form-item label="含税">
        <el-checkbox v-model="form.taxIncluded">单价含税</el-checkbox>
      </el-form-item>

      <el-form-item label="条款" class="span-all">
        <el-input v-model="form.terms" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" maxlength="500" />
        <el-select
          v-if="prodTerms.enabled.value.length"
          class="terms-pick"
          size="small"
          filterable
          placeholder="填常用条款"
          :model-value="''"
          @change="addTerm"
        >
          <el-option v-for="t in prodTerms.enabled.value" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>

      <el-form-item label="备注" class="span-all">
        <el-input v-model="form.remark" maxlength="200" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-grid :deep(.el-select),
.form-grid :deep(.el-input-number),
.form-grid :deep(.el-date-editor) {
  width: 100%;
}

.span-all {
  grid-column: 1 / -1;
}

.amber :deep(.el-input__wrapper) {
  background: var(--lm-pending-bg);
  box-shadow: 0 0 0 1px var(--lm-pending-text) inset;
}

.tip {
  margin: 3px 0 0;
  font-size: 11.5px;
}

.terms-pick {
  width: 180px !important;
  margin-top: 6px;
}
</style>
