<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { isFutureBusinessDay } from '@/utils/businessDate'
import { computed, h, reactive, ref, toRaw, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { invDocApi as invDocApiBase, stockApi } from '@/api/inventory'
import type { WarehouseOption } from '@/types/basic'
import {
  INV_DOC_TYPE,
  type InvDocDraft,
  type InvDocDraftItem,
  type InvDocFromSourceItem,
  type InvDocFromSourceReq,
  type InvDocType,
} from '@/types/inventory'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { formatPrice, formatQty } from '@/utils/format'
import { newClientAction, unknownActionResult } from '@/utils/clientAction'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'
import { showSilentError, todayStr } from './docUtils'
import { bindCompanyApi } from '@/utils/companyScope'
import { restoreDraftValue } from '@/utils/draft'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()
const invDocApi = bindCompanyApi(invDocApiBase, () => sourceCompanyId.value)

/**
 * 由源单生成物料入库单、出库单的对话框（docs/api/04 §1、§3.3、§4，原型 PurchaseReceive、ProdIssueMat）。
 * 采购单详情、待到料（采购入库、采购退货）、生产单详情、出库单页（领料出库、退料入库）共用：
 *
 *   <SourceInvDocDialog v-model="receiving" source-kind="PURCHASE" :source-id="id" doc-type="PURCHASE_IN" :item-ids="pickedIds" @created="refresh" />
 *   <SourceInvDocDialog v-model="issuing" source-kind="PRODUCTION" :source-id="id" doc-type="ISSUE_OUT" @created="refresh" />
 *
 * - 打开时调 prefill 带出单头默认值和明细，"本次"默认 suggestQty，旁边一列是可入 / 可退 / 还差
 * - 采购入库超过未入库、没超上限：琥珀色"超收 n"；超过 maxQty 标红、按钮灰掉（后端也会回 1004）
 * - 领料超过还差：琥珀色，超领原因必填。领料换了出仓重新带出（还差、出仓库存跟着出仓变）
 * - 有出仓的显示出仓库存（库存单位）。采购退货的"本次"是采购单位，乘换算系数再和库存比；不够标红但不拦（审核时会失败）
 * - 本次是 0 的行不建，全是 0 时按钮灰掉；只能存草稿：自己建的单不能自己审核过账，要别人审（甲方 11-21 答 Q10，后端 SelfAudit 也拦）
 * - 建好发 created（{ id, no, posted }），页面刷新自己
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  sourceKind: 'PURCHASE' | 'PRODUCTION'
  sourceId: string | null
  docType: Extract<InvDocType, 'PURCHASE_IN' | 'PURCHASE_RETURN' | 'ISSUE_OUT' | 'RETURN_IN'>
  /** 采购单上只处理这几行（待到料、采购单详情勾的）；不传 = 能处理的全部行 */
  itemIds?: string[]
}>()

const emit = defineEmits<{
  created: [payload: { id: string; no: string; posted: boolean }]
}>()

const router = useRouter()
const units = useDict('unit')

interface DraftRow extends InvDocDraftItem {
  thisQty: number
  dyeLotInput: string
  rollsInput: number | null
  overReasonInput: string
  remarkInput: string
  /** 出仓现库存（库存单位）；还没取到、取不到是 null */
  stockQty: number | null
}

const actionKey = computed(() => `${props.sourceKind}:${props.sourceId}:${props.docType}`)
const pendingActions = ref<Record<string, InvDocFromSourceReq>>({})
const uncertainKeys = ref<Record<string, boolean>>({})
const pendingAction = computed(() => pendingActions.value[actionKey.value])
const uncertain = computed(() => !!uncertainKeys.value[actionKey.value])
const loading = ref(false)
const loadError = ref<string | null>(null)
const submitting = ref(false)
const draft = ref<InvDocDraft | null>(null)
const sourceCompanyId = ref<string>()
const warehouses = ref<WarehouseOption[]>([])
const rows = ref<DraftRow[]>([])

const head = reactive({
  docDate: todayStr(),
  fromWarehouseId: null as string | null,
  toWarehouseId: null as string | null,
  supplierDeliveryNo: '',
  logisticsNo: '',
  reason: '',
  remark: '',
})

const guard = useUnsavedChanges(
  () => ({ head, rows: rows.value.map((r) => ({ materialSkuId: r.materialSkuId, sourceItemId: r.sourceItemId, thisQty: r.thisQty, dyeLotInput: r.dyeLotInput, rollsInput: r.rollsInput, overReasonInput: r.overReasonInput, remarkInput: r.remarkInput })) }),
  visible,
  { ready: () => !loading.value && !!draft.value, restore(value) {
    if (!value || typeof value !== 'object' || !('rows' in value) || !Array.isArray(value.rows)) return
    if ('head' in value) restoreDraftValue(head, value.head)
    for (const saved of value.rows) {
      if (!saved || typeof saved !== 'object') continue
      const row = rows.value.find((item) => item.materialSkuId === saved.materialSkuId && item.sourceItemId === saved.sourceItemId)
      if (!row) continue
      if (typeof saved.thisQty === 'number') row.thisQty = saved.thisQty
      if (saved.rollsInput === null || typeof saved.rollsInput === 'number') row.rollsInput = saved.rollsInput
      for (const key of ['dyeLotInput', 'overReasonInput', 'remarkInput'] as const) if (typeof saved[key] === 'string') row[key] = saved[key]
    }
  } },
)

const meta = computed(() => INV_DOC_TYPE[props.docType])
const isPurchase = computed(() => props.sourceKind === 'PURCHASE')

const fromOptions = computed(() => {
  const allowed = meta.value.from
  return allowed ? warehouses.value.filter((w) => allowed.includes(w.type)) : []
})

const toOptions = computed(() => {
  const allowed = meta.value.to
  return allowed ? warehouses.value.filter((w) => allowed.includes(w.type)) : []
})

const dialogTitle = computed(() => {
  const no = draft.value?.source.no ?? ''
  return no ? `${meta.value.label} · ${no}` : meta.value.label
})

/** 入库、退货、领料、退料：按钮、提示里的动词 */
const verb = computed(() => meta.value.label.slice(-2))

/** 可入 / 可退 / 还差这一列 */
const LIMIT_LABEL: Record<typeof props.docType, string> = { PURCHASE_IN: '可入', PURCHASE_RETURN: '可退', ISSUE_OUT: '还差', RETURN_IN: '可退' }
function limitOf(row: DraftRow): number | null {
  // 采购入库的"可入"是未入库、领料的"还差"是还没领的（都是默认的本次）；退货、退料是上限
  return props.docType === 'PURCHASE_IN' || props.docType === 'ISSUE_OUT' ? row.suggestQty : row.maxQty
}

/** 出仓库存：选了出仓、换了仓都重新取；取不到显示"—"，不影响建单 */
const stockRequest = useLatestRequest()
async function refreshFromStock() {
  const read = stockRequest.begin()
  const whId = head.fromWarehouseId
  if (!whId || !rows.value.length) return
  try {
    const skuIds = [...new Set(rows.value.map((r) => r.materialSkuId))]
    const list = await read.run(() => stockApi.qty(whId, skuIds, { silent: true }))
    if (!read.current()) return
    if (whId !== head.fromWarehouseId) return
    const map = new Map(list.map((x) => [x.materialSkuId, x.qty]))
    rows.value.forEach((r) => (r.stockQty = map.get(r.materialSkuId) ?? 0))
  } catch {
    if (!read.current()) return
    rows.value.forEach((r) => (r.stockQty = null))
  }
}

function prefill(sourceId: string, fromWarehouseId?: string): Promise<InvDocDraft> {
  if (props.sourceKind === 'PURCHASE') {
    return invDocApi.prefillFromPurchase(sourceId, { docType: props.docType as 'PURCHASE_IN' | 'PURCHASE_RETURN', itemIds: props.itemIds })
  }
  return invDocApi.prefillFromProduction(sourceId, { docType: props.docType as 'ISSUE_OUT' | 'RETURN_IN', fromWarehouseId })
}

function toRows(d: InvDocDraft): DraftRow[] {
  return d.items.map((item) => ({
    ...item,
    thisQty: item.suggestQty,
    dyeLotInput: item.dyeLot ?? '',
    rollsInput: null,
    overReasonInput: '',
    remarkInput: '',
    stockQty: null,
  }))
}

let initSeq = 0
const prefillRequest = useLatestRequest()
async function init() {
  const read = prefillRequest.begin()
  const seq = ++initSeq
  const key = actionKey.value
  const sourceId = props.sourceId
  if (!visible.value || !sourceId) return
  sourceCompanyId.value = undefined
  loading.value = true
  loadError.value = null
  try {
    const [whs, d] = await read.run(() => Promise.all([warehouseApi.options(), prefill(sourceId)]))
    if (!read.current()) return
    if (seq !== initSeq || key !== actionKey.value || !visible.value) return
    warehouses.value = whs
    sourceCompanyId.value = d.companyId
    draft.value = d
    Object.assign(head, {
      docDate: todayStr(),
      fromWarehouseId: d.fromWarehouseId,
      toWarehouseId: d.toWarehouseId,
      supplierDeliveryNo: '',
      logisticsNo: '',
      reason: '',
      remark: '',
    })
    rows.value = toRows(d)
    const original = pendingAction.value
    if (original) {
      Object.assign(head, { docDate: original.docDate ?? head.docDate, fromWarehouseId: original.fromWarehouseId, toWarehouseId: original.toWarehouseId,
        supplierDeliveryNo: original.supplierDeliveryNo ?? '', logisticsNo: original.logisticsNo ?? '', reason: original.reason ?? '', remark: original.remark ?? '' })
      for (const row of rows.value) {
        const item = original.items?.find((i) => i.sourceItemId === row.sourceItemId && i.materialSkuId === row.materialSkuId)
        row.thisQty = item?.qty ?? 0
        row.dyeLotInput = item?.dyeLot ?? ''
        row.rollsInput = item?.rolls ?? null
        row.overReasonInput = item?.overReason ?? ''
        row.remarkInput = item?.remark ?? ''
      }
    }
    await refreshFromStock()
    if (!read.current()) return
    guard.markSaved()
  } catch (e) {
    if (!read.current()) return
    if (seq === initSeq) loadError.value = e instanceof Error ? e.message : '来源明细加载失败'
  } finally {
    if (read.current() && seq === initSeq) loading.value = false
  }
}

watch(visible, (on) => {
  if (on) void init()
})

/** 换了出仓。领料的还差跟着出仓变（04 §3.3.2），重新带出，已填的本次、原因按 SKU 留着；别的只重取出仓库存 */
async function onFromWarehouseChange(whId: string | null) {
  if (!visible.value || !whId || !props.sourceId) return
  if (props.docType !== 'ISSUE_OUT') {
    await refreshFromStock()
    return
  }
  const read = prefillRequest.begin()
  const seq = ++initSeq
  const sourceId = props.sourceId
  const kept = new Map(rows.value.map((r) => [r.materialSkuId, r]))
  loading.value = true
  try {
    const d = await read.run(() => prefill(sourceId, whId))
    if (!read.current()) return
    if (d.companyId !== sourceCompanyId.value) throw new ApiError(409, '来源单主体已变化，请关闭后重新打开')
    draft.value = d
    rows.value = toRows(d).map((r) => {
      const old = kept.get(r.materialSkuId)
      return old ? { ...r, thisQty: old.thisQty, overReasonInput: old.overReasonInput, remarkInput: old.remarkInput } : r
    })
    await refreshFromStock()
  } finally {
    if (read.current() && seq === initSeq) loading.value = false
  }
}

function unitText(row: DraftRow): string {
  return units.labelOf(isPurchase.value ? (row.purchaseUnit ?? row.stockUnit) : row.stockUnit)
}

/** 本次换成库存单位：采购的两种按采购单位填，乘换算系数（04 §3.3）；领料、退料本来就是库存单位 */
function stockQtyOf(row: DraftRow): number {
  return isPurchase.value && row.conversionFactor ? row.thisQty * row.conversionFactor : row.thisQty
}

const EPS = 1e-6

function isOverMax(row: DraftRow): boolean {
  return row.maxQty != null && row.thisQty > row.maxQty + EPS
}

function isNeedOverReason(row: DraftRow): boolean {
  return props.docType === 'ISSUE_OUT' && row.thisQty > row.suggestQty + EPS
}

/** 采购入库超过未入库、没超上限的部分（溢收） */
function overReceive(row: DraftRow): number {
  return props.docType === 'PURCHASE_IN' && !isOverMax(row) ? Math.max(0, row.thisQty - row.suggestQty) : 0
}

/** 出仓库存不够：标红，不拦（库存可能审核前就到了） */
function isShort(row: DraftRow): boolean {
  return row.stockQty != null && stockQtyOf(row) > row.stockQty + EPS
}

function disableFutureDate(d: Date) {
  return isFutureBusinessDay(d)
}

const activeRows = computed(() => rows.value.filter((r) => r.thisQty > 0))
/** 全是 0、有超上限的：按钮灰掉，悬停说为什么 */
const blockedTip = computed(() => {
  if (!activeRows.value.length) return '本次都是 0，没有要建的行'
  const over = rows.value.find(isOverMax)
  return over ? `${over.skuCode} 超过上限 ${formatQty(over.maxQty)}` : ''
})

/** 草稿建好的提示带个链接，点了去单据详情 */
function notifyDraft(id: string, no: string) {
  const kindLabel = meta.value.kind === 'IN' ? '入库单' : '出库单'
  const base = meta.value.kind === 'IN' ? '/stock-in' : '/stock-out'
  const message = ElMessage.success({
    duration: 5000,
    message: h('span', [
      `建了${kindLabel}草稿 `,
      h(
        'a',
        {
          class: 'code',
          style: 'color: var(--lm-accent); cursor: pointer',
          onClick: () => {
            message.close()
            void router.push(`${base}/${id}`)
          },
        },
        no,
      ),
    ]),
  })
}

async function submit(post: boolean) {
  if (pendingAction.value) { await sendOriginal(toRaw(pendingAction.value)); return }
  if (loadError.value || loading.value || !props.sourceId || !draft.value || blockedTip.value) return
  if (meta.value.from && !head.fromWarehouseId) {
    ElMessage.warning('选一个出库仓库')
    return
  }
  if (meta.value.to && !head.toWarehouseId) {
    ElMessage.warning('选一个入库仓库')
    return
  }
  const missingOver = activeRows.value.find((r) => isNeedOverReason(r) && !r.overReasonInput.trim())
  if (missingOver) {
    ElMessage.warning(`${missingOver.skuCode} 领多了，要写超领原因`)
    return
  }

  const items: InvDocFromSourceItem[] = activeRows.value.map((r) => ({
    sourceItemId: r.sourceItemId,
    materialSkuId: r.materialSkuId,
    qty: r.thisQty,
    dyeLot: orNull(r.dyeLotInput),
    rolls: r.rollsInput ?? null,
    overReason: isNeedOverReason(r) ? orNull(r.overReasonInput) : null,
    remark: orNull(r.remarkInput),
  }))

  const req: InvDocFromSourceReq = newClientAction({
    docType: props.docType,
    docDate: head.docDate,
    fromWarehouseId: head.fromWarehouseId,
    toWarehouseId: head.toWarehouseId,
    supplierDeliveryNo: orNull(head.supplierDeliveryNo),
    logisticsNo: orNull(head.logisticsNo),
    reason: orNull(head.reason),
    remark: orNull(head.remark),
    items,
    itemIds: null,
    post,
  })
  pendingActions.value[actionKey.value] = req
  await sendOriginal(req)
}

async function sendOriginal(req: InvDocFromSourceReq) {
  const key = actionKey.value
  const sourceId = props.sourceId
  const kind = props.sourceKind
  if (!sourceId) return
  submitting.value = true
  try {
    const res =
      kind === 'PURCHASE'
        ? await invDocApi.fromPurchase(sourceId, req, { silent: true })
        : await invDocApi.fromProduction(sourceId, req, { silent: true })
    delete pendingActions.value[key]
    delete uncertainKeys.value[key]
    if (key === actionKey.value) { guard.markSaved(); visible.value = false }
    const posted = res.status === 'APPROVED'
    if (posted) ElMessage.success(`${verb.value}了，库存已更新（${res.no}）`)
    else notifyDraft(res.id, res.no)
    emit('created', { id: res.id, no: res.no, posted })
  } catch (e) {
    // 1001 库存不够、1004 超上限弹清单表格，别的一行消息；对话框留着改
    uncertainKeys.value[key] = unknownActionResult(e instanceof ApiError ? e.code : undefined)
    if (!uncertainKeys.value[key]) delete pendingActions.value[key]
    if (e instanceof ApiError) showSilentError(e)
    else throw e
  } finally {
    submitting.value = false
  }
}
async function restartAction() {
  try {
    await ElMessageBox.confirm('请先核对原单据是否已生成。只有明确要另建一张时才继续，新动作可能再次记账。', '重新登记', { confirmButtonText: '确认新登记', cancelButtonText: '保留原登记' })
    delete pendingActions.value[actionKey.value]
    delete uncertainKeys.value[actionKey.value]
    await init()
  } catch { /* 保留原动作 */ }
}
watch(visible, (on) => { if (!on) { stockRequest.cancel(); prefillRequest.cancel() } })
</script>

<template>
  <el-dialog v-model="visible" :title="dialogTitle" width="920px" :close-on-click-modal="false" :before-close="guard.beforeClose">
    <el-alert v-if="loadError" type="error" :closable="false" :title="loadError"><el-button @click="init">重试加载</el-button></el-alert>
    <el-alert v-if="uncertain" type="warning" :closable="false" title="登记结果尚未确认。重试使用原动作和原内容，关窗重开仍保留。">
      <el-button :loading="submitting" @click="submit(false)">重试原登记</el-button><el-button link @click="restartAction">重新登记</el-button>
    </el-alert>
    <div v-loading="loading" :inert="!!pendingAction || submitting">
      <el-form label-position="top" class="head-grid">
        <el-form-item label="日期">
          <el-date-picker v-model="head.docDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disableFutureDate" :clearable="false" />
        </el-form-item>
        <el-form-item v-if="meta.from" label="从哪个仓出">
          <el-select v-model="head.fromWarehouseId" filterable placeholder="选出库仓库" @change="onFromWarehouseChange">
            <el-option v-for="w in fromOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="meta.to" label="入哪个仓">
          <el-select v-model="head.toWarehouseId" filterable placeholder="选入库仓库">
            <el-option v-for="w in toOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="draft?.partnerName" label="往来单位">
          <el-input :model-value="draft.partnerName" disabled />
        </el-form-item>
        <el-form-item v-if="docType === 'PURCHASE_IN'" label="供应商送货单号">
          <el-input v-model="head.supplierDeliveryNo" maxlength="60" placeholder="选填" />
        </el-form-item>
        <el-form-item label="物流单号">
          <el-input v-model="head.logisticsNo" maxlength="60" placeholder="选填" />
        </el-form-item>
        <el-form-item v-if="docType === 'PURCHASE_RETURN' || docType === 'RETURN_IN'" label="原因">
          <el-input v-model="head.reason" maxlength="120" placeholder="选填" />
        </el-form-item>
        <el-form-item label="备注" class="span-2">
          <el-input v-model="head.remark" maxlength="200" placeholder="选填" />
        </el-form-item>
      </el-form>

      <el-table :data="rows" row-key="materialSkuId" :empty-text="`这张单上没有要${verb}的物料`">
        <el-table-column label="物料" min-width="190">
          <template #default="{ row }">
            <div class="sku-cell">
              <span class="code">{{ row.skuCode }}</span>
              <span class="sub">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template><template v-if="row.spec"> {{ row.spec }}</template></span>
            </div>
          </template>
        </el-table-column>

        <el-table-column v-if="isPurchase" label="采购 / 已入 / 已退" width="150" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.orderedQty) }} / {{ formatQty(row.receivedQty) }} / {{ formatQty(row.returnedQty) }}</template>
        </el-table-column>
        <el-table-column v-else label="应领 / 已领" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.needQty) }} / {{ formatQty(row.issuedQty) }}</template>
        </el-table-column>

        <el-table-column :label="LIMIT_LABEL[docType]" width="90" class-name="num" align="right">
          <template #default="{ row }">{{ limitOf(row as DraftRow) == null ? '不限' : formatQty(limitOf(row as DraftRow)) }}</template>
        </el-table-column>

        <el-table-column v-if="meta.from" label="出仓库存" width="110" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="{ danger: isShort(row as DraftRow) }" :title="isShort(row as DraftRow) ? '库存不够，审核会失败' : undefined">
              {{ row.stockQty == null ? '—' : formatQty(row.stockQty) }}
            </span>
            <span v-if="row.stockQty != null" class="unit dim">{{ units.labelOf(row.stockUnit) }}</span>
          </template>
        </el-table-column>

        <el-table-column v-if="canSeeCost && (isPurchase)" label="单价" width="80" class-name="num" align="right">
          <template #default="{ row }">{{ row.price == null ? '—' : formatPrice(row.price) }}</template>
        </el-table-column>

        <el-table-column label="本次" width="160" align="right">
          <template #default="{ row }">
            <div class="qty-box" :class="{ over: isOverMax(row as DraftRow), warn: overReceive(row as DraftRow) > 0 || isNeedOverReason(row as DraftRow) }">
              <el-input-number v-model="row.thisQty" size="small" :min="0" :precision="3" :controls="false" />
              <span class="unit dim">{{ unitText(row as DraftRow) }}</span>
            </div>
            <div v-if="isOverMax(row as DraftRow)" class="cell-tip danger">最多 {{ formatQty(row.maxQty) }}</div>
            <div v-else-if="overReceive(row as DraftRow) > 0" class="cell-tip warn-txt">超收 {{ formatQty(overReceive(row as DraftRow)) }}</div>
          </template>
        </el-table-column>

        <el-table-column v-if="docType === 'PURCHASE_IN'" label="缸号 / 卷数" width="165">
          <template #default="{ row }">
            <div class="lot-row">
              <el-input v-model="row.dyeLotInput" size="small" maxlength="40" placeholder="缸号" />
              <el-input-number v-model="row.rollsInput" size="small" :min="0" :precision="0" :controls="false" placeholder="卷" class="rolls" />
            </div>
          </template>
        </el-table-column>

        <el-table-column :label="docType === 'ISSUE_OUT' ? '超领原因 / 备注' : '备注'" min-width="150">
          <template #default="{ row }">
            <el-input v-if="isNeedOverReason(row as DraftRow)" v-model="row.overReasonInput" size="small" maxlength="100" placeholder="超领原因（必填）" class="over-input" />
            <el-input v-else v-model="row.remarkInput" size="small" maxlength="100" placeholder="选填" />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <template #footer>
      <span v-if="blockedTip && rows.length" class="foot-tip">{{ blockedTip }}</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!!blockedTip" @click="submit(false)">存草稿</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
  margin-bottom: 8px;
}

.head-grid .span-2 {
  grid-column: span 2;
}

.head-grid :deep(.el-select),
.head-grid :deep(.el-date-editor) {
  width: 100%;
}

.sku-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sku-cell .sub {
  font-size: 12px;
  color: var(--lm-muted);
}

.qty-box {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.qty-box :deep(.el-input-number) {
  width: 96px;
}

.qty-box.warn :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-pending-text) inset;
}

.qty-box.over :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-danger-text) inset;
}

.unit {
  margin-left: 2px;
  font-size: 12px;
}

.cell-tip {
  margin-top: 2px;
  font-size: 11px;
}

.danger {
  color: var(--lm-danger-text);
}

.warn-txt {
  color: var(--lm-pending-text);
}

.lot-row {
  display: flex;
  gap: 6px;
}

.lot-row .rolls {
  flex: none;
  width: 58px;
}

.over-input :deep(.el-input__wrapper) {
  background: var(--lm-pending-bg);
  box-shadow: 0 0 0 1px var(--lm-pending-text) inset;
}

.foot-tip {
  margin-right: 12px;
  font-size: 12px;
  color: var(--lm-danger-text);
}

@media (max-width: 768px) {
  .head-grid {
    grid-template-columns: 1fr;
  }

  .head-grid .span-2 {
    grid-column: auto;
  }
}
</style>
