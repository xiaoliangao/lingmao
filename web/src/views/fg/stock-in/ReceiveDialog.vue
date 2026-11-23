<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { fgDocApi as fgDocApiBase } from '@/api/fg'
import FgReadFeedback from '../FgReadFeedback.vue'
import { fgReadError, useFgRead } from '../fgRead'
import { assertDraftSkus, readReceiptDraft } from '../fgDraft'
import SkuMatrix from '@/components/SkuMatrix.vue'
import { todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { WarehouseOption } from '@/types/basic'
import { PRODUCTION_KIND } from '@/types/production'
import type { FgDocDetail, FgFromReceiptReq, FgReceiptDraft, FgStyleOption } from '@/types/fg'
import { isFutureBusinessDay } from '@/utils/businessDate'
import { bindCompanyApi } from '@/utils/companyScope'
import { newClientAction, unknownActionResult } from '@/utils/clientAction'
import { formatDate, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { sessionChanged, sessionEpoch } from '@/utils/token'
import { orNull } from '@/views/basic/common'
import { fgCells, fgColors, fgSizes } from '../doc/fgMatrix'

/**
 * 回货入成品仓（docs/api/07 §1.3、§3.3）：
 *
 *   <ReceiveDialog v-model="visible" :receipt-id="row.receiptId" @created="onCreated" />      待入库点"入库"
 *   <ReceiveDialog v-model="visible" :editing-doc="detail" @saved="refresh" />                改回货入库的草稿
 *
 * - 一个颜色 × 尺码矩阵：格子默认还没入的良品、能改少（上限就是它，超了标红、后端 1004 拦），每格下面"良品 n · 已入 n"
 * - 只能"存草稿"：自己建的单不能自己审核过账，要别人审（甲方 11-21 答 Q10，后端 SelfAudit 也拦）；
 *   生成接口带 clientActionId，结果不确定时重试用原来的那一笔，不会入两次
 * - 改草稿通过 docId 取原来源全部色码；上限含本单占用，原来没录的格子以 0 初始化，可补入
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  companyId?: string | null
  receiptId?: string | null
  editingDoc?: FgDocDetail | null
}>()

const emit = defineEmits<{
  created: [id: string, companyId: string]
  saved: []
}>()

const companyId = computed(() => props.editingDoc?.companyId ?? props.companyId ?? draft.value?.companyId)
const fgDocApi = bindCompanyApi(fgDocApiBase, () => companyId.value)

const warehouses = ref<WarehouseOption[]>([])
const draft = ref<FgReceiptDraft | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)
const draftError = ref<string | null>(null)
let presentation = 0
onBeforeUnmount(() => { presentation++ })
const initialRead = useFgRead(() => {
  presentation++; visible.value = false; draft.value = null; pendingAction.value = null; form.qtys = {}
})
const submitting = ref(false)
const pendingAction = shallowRef<{ request: FgFromReceiptReq; receiptId: string } | null>(null)
const uncertain = ref(false)

const form = reactive({
  warehouseId: null as string | null,
  docDate: todayStr(),
  remark: '',
  /** 款式 SKU id → 这次入几件 */
  qtys: {} as Record<string, number>,
})
const guard = useUnsavedChanges(form, visible, {
  scope: () => {
    const d = props.editingDoc, company = d?.companyId ?? props.companyId, receipt = d?.receipt?.id ?? props.receiptId
    return company && receipt ? JSON.stringify([company, receipt, d?.id ?? 'new', d?.version ?? 0]) : null
  },
  ready: () => !loading.value && !loadError.value && !!draft.value,
  restore(value, context) {
    draftError.value = null
    try {
      const saved = readReceiptDraft(value), source = draft.value
      if (!source) throw new ApiError(409, '回货来源已变化，请重新读取')
      if (saved.warehouseId && !warehouses.value.some((warehouse) => warehouse.id === saved.warehouseId)) throw new ApiError(409, '草稿仓库已不可用，未恢复任何内容')
      assertDraftSkus(saved.qtys, new Set(source.items.map((item) => item.styleSkuId)))
      if (context.current()) Object.assign(form, saved)
    } catch (cause) { if (context.current()) draftError.value = fgReadError(cause) }
  },
})
function discardLocalDraft() { guard.discardDraft(); draftError.value = null }

/** 回货单上的 SKU：格子 id → 上限、小字 */
interface Line {
  /** 回货明细 id；改草稿时没有 */
  prdReceiptItemId: string | null
  max: number | null
  sub: string | null
}
const lines = computed(() => {
  const out = new Map<string, Line>()
  if (draft.value) {
    for (const it of draft.value.items) {
      out.set(it.styleSkuId, { prdReceiptItemId: it.prdReceiptItemId, max: it.maxQty, sub: `良品 ${formatQty(it.goodQty)} · 已过账 ${formatQty(it.postedQty)} · 草稿占用 ${formatQty(it.reservedQty)}` })
    }
  }
  return out
})

const style = computed<FgStyleOption | null>(() => draft.value?.style ?? null)
/** 只有回货单上的格子能填 */
const cells = computed(() => (style.value ? fgCells(style.value, (id) => lines.value.has(id)) : []))
const maxOf = (id: string) => lines.value.get(id)?.max ?? null
const subOf = (id: string) => lines.value.get(id)?.sub ?? null
const total = computed(() => Object.values(form.qtys).reduce((sum, q) => sum + (q > 0 ? q : 0), 0))
const title = computed(() => (props.editingDoc ? `编辑 ${props.editingDoc.no}（回货入库）` : draft.value ? `回货入库 · ${draft.value.receiptNo}` : '回货入库'))

watch(visible, (on) => {
  if (on && !pendingAction.value) void init()
  if (!on) { presentation++; initialRead.cancel(); loading.value = false }
})
watch(() => [props.receiptId, props.companyId, props.editingDoc?.id, props.editingDoc?.version], () => {
  presentation++; initialRead.cancel(); draft.value = null; form.qtys = {}; pendingAction.value = null; uncertain.value = false
  if (visible.value) void init()
}, { flush: 'sync' })

async function init() {
  const read = initialRead.begin()
  const d = props.editingDoc
  const receipt = d?.receipt?.id ?? props.receiptId
  const company = companyId.value
  loading.value = true
  loadError.value = null
  draftError.value = null
  draft.value = null
  form.qtys = {}
  try {
    if (!company) throw new ApiError(400, '请先选择主体')
    if (!receipt) throw new ApiError(400, '请选择回货单')
    const [options, source] = await read.run(() => Promise.all([
      warehouseApi.options('PRODUCT', { signal: read.signal, silent: true }),
      fgDocApiBase.prefillFromReceipt(receipt, { companyId: company, signal: read.signal, silent: true }, d?.id),
    ]))
    if (!read.current()) return
    warehouses.value = options
    if (source.companyId !== company || source.receiptId !== receipt) throw new ApiError(409, '来源已变化，请重新查询')
    draft.value = source
    const existingQty = new Map((d?.items ?? []).map((item) => [item.styleSkuId, item.qty]))
    Object.assign(form, { warehouseId: d?.warehouseId ?? source.warehouseId, docDate: d?.docDate ?? todayStr(), remark: d?.remark ?? '',
      qtys: Object.fromEntries(source.items.map((it) => [it.styleSkuId, d ? existingQty.get(it.styleSkuId) ?? 0 : it.maxQty])) })
    guard.markSaved()
  } catch (cause) {
    if (read.current()) loadError.value = fgReadError(cause)
  } finally {
    if (read.current()) loading.value = false
  }
}

function check(): boolean {
  if (Object.values(form.qtys).some((qty) => !Number.isSafeInteger(qty) || qty < 0 || qty > 99_999_999)) return void ElMessage.warning('每格件数必须是 0 至 99999999 的整数'), false
  if (Object.values(form.qtys).filter((qty) => qty > 0).length > 2000) return void ElMessage.warning('一张单最多录 2000 条正数色码，请分开录单'), false
  if (!form.warehouseId) return void ElMessage.warning('先选入仓'), false
  const over = cells.value.find((k) => (form.qtys[k.id] ?? 0) > (maxOf(k.id) ?? Infinity))
  if (over) {
    const color = style.value?.colors.find((c) => c.id === over.colorId)?.name ?? ''
    return void ElMessage.warning(`${color} ${over.sizeCode} 填多了：最多 ${formatQty(maxOf(over.id) ?? 0)} 件`), false
  }
  if (total.value <= 0) return void ElMessage.warning('一件都没填'), false
  return true
}

async function submit() {
  if (submitting.value || loading.value || loadError.value || draftError.value) return
  if (props.editingDoc) return save()
  if (pendingAction.value) return send(pendingAction.value.receiptId, pendingAction.value.request)
  const r = draft.value
  if (!r || !check()) return
  const request = newClientAction({
    companyId: r.companyId,
    post: false,
    docDate: form.docDate,
    warehouseId: form.warehouseId,
    remark: orNull(form.remark),
    items: r.items.map((it) => ({ prdReceiptItemId: it.prdReceiptItemId, qty: form.qtys[it.styleSkuId] ?? 0 })).filter((it) => it.qty > 0),
  })
  pendingAction.value = { request, receiptId: r.receiptId }
  await send(r.receiptId, request)
}

async function send(receiptId: string, request: FgFromReceiptReq) {
  submitting.value = true
  const ownPresentation = presentation
  try {
    const res = await fgDocApi.fromReceipt(receiptId, request)
    if (ownPresentation !== presentation || !visible.value) return
    pendingAction.value = null
    uncertain.value = false
    const qty = formatQty((request.items ?? []).reduce((sum, it) => sum + it.qty, 0))
    ElMessage.success(`${res.no} 草稿存好了（${qty} 件），由别人审核过账`)
    guard.markSaved()
    visible.value = false
    emit('created', res.id, request.companyId)
  } catch (e) {
    if (ownPresentation !== presentation) return
    uncertain.value = unknownActionResult(e instanceof ApiError ? e.code : undefined)
    if (!uncertain.value) pendingAction.value = null
    if (!(e instanceof ApiError)) throw e
  } finally {
    submitting.value = false
  }
}

async function save() {
  if (submitting.value || loading.value || loadError.value || draftError.value) return
  const d = props.editingDoc
  const source = draft.value
  if (!d || !source || !check()) return
  submitting.value = true
  const ownPresentation = presentation
  const remarks = new Map(d.items.map((item) => [item.styleSkuId, item.remark]))
  try {
    await fgDocApi.update(d.id, {
      companyId: d.companyId,
      version: d.version,
      warehouseId: form.warehouseId as string,
      docDate: form.docDate,
      reason: null,
      shipTo: null,
      logisticsNo: null,
      remark: orNull(form.remark),
      items: source.items.map((it) => ({ styleSkuId: it.styleSkuId, qty: form.qtys[it.styleSkuId] ?? 0, remark: remarks.get(it.styleSkuId) ?? null })).filter((it) => it.qty > 0),
    })
    if (ownPresentation !== presentation || !visible.value) return
    ElMessage.success(`${d.no} 已保存`)
    guard.markSaved()
    visible.value = false
    emit('saved')
  } finally {
    submitting.value = false
  }
}

async function restart() {
  const ownPresentation = presentation, action = pendingAction.value, epoch = sessionEpoch()
  if (!action || !uncertain.value || !visible.value) return
  try {
    await ElMessageBox.confirm('上一笔结果还没确认。先到「入库单」核对；确实还要再入一笔才继续，新的一笔可能把同一批货再入一次。', '重新入一笔', {
      confirmButtonText: '确认再入一笔',
      cancelButtonText: '保留原来那笔',
    })
    if (ownPresentation !== presentation || action !== pendingAction.value || !uncertain.value || !visible.value || epoch !== sessionEpoch() || sessionChanged()) return
    pendingAction.value = null
    uncertain.value = false
    void init()
  } catch {
    /* 保留原请求 */
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="760px" :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p v-if="loadError"><FgReadFeedback :error="loadError" :loading="loading" label="回货带出" @retry="init" /></p>
    <p v-if="draftError"><FgReadFeedback :error="draftError" label="草稿恢复" @retry="guard.retryDraft" /> <el-button link @click="discardLocalDraft">放弃本地草稿</el-button></p>
    <div v-loading="loading" :inert="loading || !!loadError">
      <el-alert v-if="uncertain" type="warning" :closable="false" title="入库结果还没确认。重试用的是原来那一笔，不会重复入库。">
        <el-button link @click="restart">重新入一笔</el-button>
      </el-alert>

      <p v-if="draft" class="sub">
        <span class="code">{{ draft.prdOrderNo }}</span>
        <span class="dim">{{ PRODUCTION_KIND[draft.prdOrderKind].label }}</span>
        <span>{{ draft.factoryName }}</span>
        <span class="dim">回货 {{ formatDate(draft.receiptDate) }}</span>
      </p>
      <p v-else-if="editingDoc?.receipt" class="sub">
        <span class="code">{{ editingDoc.receipt.no }}</span>
        <span class="code">{{ editingDoc.receipt.prdOrderNo }}</span>
        <span>{{ editingDoc.receipt.factoryName }}</span>
      </p>

      <div :inert="!!pendingAction || submitting">
        <el-form label-position="top" class="form-grid three">
          <el-form-item label="入仓" required>
            <el-select v-model="form.warehouseId" filterable placeholder="选成品仓">
              <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期">
            <el-date-picker v-model="form.docDate" type="date" value-format="YYYY-MM-DD" :disabled-date="isFutureBusinessDay" :clearable="false" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.remark" maxlength="500" />
          </el-form-item>
        </el-form>

        <div v-if="style" class="style-line">
          <span class="code">{{ style.code }}</span>
          <span>{{ style.name }}</span>
          <span class="dim">这次入 {{ formatQty(total) }} 件</span>
        </div>
        <SkuMatrix v-if="style" v-model="form.qtys" :sizes="fgSizes(style, cells)" :colors="fgColors(style, cells)" :skus="cells" :max-digits="8" :max="maxOf" :sub="subOf" />
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <template v-if="editingDoc">
        <el-button type="primary" :loading="submitting" :disabled="loading || !!loadError || !!draftError" @click="save">保存</el-button>
      </template>
      <template v-else-if="uncertain">
        <el-button type="primary" :loading="submitting" @click="submit()">重试原来那笔</el-button>
      </template>
      <template v-else>
        <el-button type="primary" :loading="submitting" :disabled="loading || !!loadError || !!draftError" @click="submit()">存草稿</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.sub {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0 0 12px;
  font-size: 13px;
}

.three {
  grid-template-columns: 1fr 1fr 2fr;
}

.three :deep(.el-select),
.three :deep(.el-date-editor) {
  width: 100%;
}

.style-line {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin: 4px 0 8px;
  font-size: 13px;
}
</style>
