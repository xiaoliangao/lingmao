<script setup lang="ts">
import ObjectTags from '@/components/tags/ObjectTags.vue'
import { useUserStore } from '@/stores/user'
import ThumbImage from '@/components/ThumbImage.vue'
import { isFutureBusinessDay } from '@/utils/businessDate'
import { computed, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { productionApi as productionApiBase } from '@/api/production'
import ImageUpload from '@/components/ImageUpload.vue'
import SkuMatrix, { type MatrixCell, type MatrixColor } from '@/components/SkuMatrix.vue'
import StatusTag from '@/components/StatusTag.vue'
import { promptReason, todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { WarehouseOption } from '@/types/basic'
import {
  PRODUCTION_KIND,
  RECEIPT_STATUS,
  type ProductionDetail,
  type ProductionReceipt,
  type ReceiptItem,
  type ReceiptItemForm,
  type ReceiptForm,
} from '@/types/production'
import type { FileVO } from '@/types/system'
import { formatDate, formatDateTime, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { newClientAction, unknownActionResult } from '@/utils/clientAction'
import { orNull } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
const productionApi = bindCompanyApi(productionApiBase, () => props.doc.companyId)

/**
 * 登记回货、看回货单（docs/api/05 §1.4、§3.6、§4）：
 *
 *   <ProdReceiveDialog v-model:create-visible="a" v-model:detail-visible="b" :doc="detail" :viewing-receipt="r" @updated="refresh" />
 *
 * 登记：
 * - 回货数矩阵（含次品）每格默认未回数，下面小字"加工 120 · 已回 0"；超过"最多还能回"的格子标红、悬停写上限，不拦，以后端 1004 为准
 * - 次品矩阵：比同一格的回货多就标红，不让提交
 * - 分色分码备注收在折叠里；回货日期不能选以后；收货仓默认第一个成品仓（一期只记录）
 * 回货单：明细带合计、照片；作废的显示红条；actions 有 void 时能作废（先写原因）
 */
const createVisible = defineModel<boolean>('createVisible', { required: true })
const detailVisible = defineModel<boolean>('detailVisible', { required: true })

const props = defineProps<{
  doc: ProductionDetail
  viewingReceipt: ProductionReceipt | null
}>()

const emit = defineEmits<{
  updated: []
}>()

// ---------- 登记回货 ----------
const warehouses = ref<WarehouseOption[]>([])
const submitting = ref(false)
const pendingAction = shallowRef<ReceiptForm | null>(null)
const uncertain = ref(false)

const form = reactive({
  receiptDate: todayStr(),
  warehouseId: null as string | null,
  logisticsNo: '',
  remark: '',
  photos: [] as FileVO[],
  /** 生产单明细 id → 这次回货（含次品） */
  qtys: {} as Record<string, number>,
  /** 生产单明细 id → 其中次品 */
  defects: {} as Record<string, number>,
  /** 生产单明细 id → 分色分码备注 */
  remarks: {} as Record<string, string>,
})
const guard = useUnsavedChanges(form, createVisible)

const itemMap = computed(() => new Map(props.doc.items.map((it) => [it.id, it])))
const matrixColors = computed<MatrixColor[]>(() => {
  const seen = new Map<string, MatrixColor>()
  for (const it of props.doc.items) {
    if (!seen.has(it.colorId)) seen.set(it.colorId, { id: it.colorId, name: it.colorName, code: it.colorCode, hex: it.colorHex })
  }
  return [...seen.values()]
})
const matrixCells = computed<MatrixCell[]>(() => props.doc.items.map((it) => ({ id: it.id, colorId: it.colorId, sizeCode: it.sizeCode })))

const maxOf = (id: string) => itemMap.value.get(id)?.maxReceiveQty
const qtySub = (id: string) => {
  const it = itemMap.value.get(id)
  return it ? `加工 ${it.qty} · 已回 ${it.returnedQty}` : null
}
const defectError = (id: string, defect: number) => (defect > (form.qtys[id] ?? 0) ? '次品比这一格的回货还多' : null)

const sum = (m: Record<string, number>) => Object.values(m).reduce((s, q) => s + (q > 0 ? q : 0), 0)
const totalQty = computed(() => sum(form.qtys))
const totalDefect = computed(() => sum(form.defects))

watch(createVisible, async (on) => {
  if (!on || pendingAction.value) return
  form.receiptDate = todayStr()
  form.logisticsNo = ''
  form.remark = ''
  form.photos = []
  form.qtys = Object.fromEntries(props.doc.items.map((it) => [it.id, it.remainQty]))
  form.defects = {}
  form.remarks = {}
  form.warehouseId = null
  guard.markSaved()
  try {
    warehouses.value = await warehouseApi.options('PRODUCT')
  } catch {
    warehouses.value = []
  }
  // 收货仓的默认值不算改动
  if (form.warehouseId === null) {
    form.warehouseId = warehouses.value[0]?.id ?? null
    guard.markSaved()
  }
})

const disableFuture = (d: Date) => isFutureBusinessDay(d)

async function submitReceipt() {
  if (pendingAction.value) { await sendReceipt(pendingAction.value); return }
  if (!(await guard.waitForUploads())) return
  const items: ReceiptItemForm[] = []
  for (const it of props.doc.items) {
    const qty = form.qtys[it.id] ?? 0
    const defectQty = form.defects[it.id] ?? 0
    if (defectQty > qty) {
      ElMessage.warning(`${it.colorName} ${it.sizeCode}：次品 ${defectQty} 比回货 ${qty} 还多`)
      return
    }
    if (qty <= 0) continue
    items.push({ prdOrderItemId: it.id, qty, defectQty, remark: orNull(form.remarks[it.id] ?? '') })
  }
  if (!items.length) {
    ElMessage.warning('一个数都没填')
    return
  }
  const request = newClientAction({
    receiptDate: form.receiptDate,
    warehouseId: form.warehouseId,
    logisticsNo: orNull(form.logisticsNo),
    remark: orNull(form.remark),
    photos: structuredClone(toRaw(form.photos)),
    items,
  })
  pendingAction.value = request
  await sendReceipt(request)
}

async function sendReceipt(request: ReceiptForm) {
  submitting.value = true
  try {
    const res = await productionApi.receive(props.doc.id, request)
    pendingAction.value = null
    uncertain.value = false
    const done = res.status === 'DONE' ? `，${PRODUCTION_KIND[props.doc.kind].label}完成` : ''
    ElMessage.success(`${res.no} 已登记：${formatQty(request.items.reduce((sum, row) => sum + row.qty, 0))} 件${done}`)
    guard.markSaved()
    createVisible.value = false
    emit('updated')
  } catch (e) {
    uncertain.value = unknownActionResult(e instanceof ApiError ? e.code : undefined)
    if (!uncertain.value) pendingAction.value = null
    if (!(e instanceof ApiError)) throw e
  } finally {
    submitting.value = false
  }
}

async function restartReceipt() {
  try {
    await ElMessageBox.confirm('上一笔结果尚未确认。请先核对回货记录；只有确实还要登记另一笔时才继续，新的登记可能再次增加回货数。', '重新登记一笔', { confirmButtonText: '确认新登记', cancelButtonText: '保留原登记' })
    pendingAction.value = null
    uncertain.value = false
    guard.discardUploads()
    emit('updated')
  } catch { /* 保留原请求 */ }
}

// ---------- 回货单 ----------
const tagUser = useUserStore()
const canEditReceiptTags = computed(() => !!tagUser.info && props.viewingReceipt?.status === 'APPROVED'
  && ['APPROVED', 'RUNNING', 'DONE'].includes(props.doc.status)
  && (tagUser.hasPerm('data:all') || props.doc.createBy === tagUser.info.id || props.doc.merchId === tagUser.info.id))

const voiding = ref(false)
const receiptTotal = computed(() => {
  const items = props.viewingReceipt?.items ?? []
  return {
    qty: items.reduce((s, i) => s + i.qty, 0),
    defectQty: items.reduce((s, i) => s + i.defectQty, 0),
    goodQty: items.reduce((s, i) => s + i.goodQty, 0),
  }
})
const summaryMethod = () => ['合计', '', formatQty(receiptTotal.value.qty), formatQty(receiptTotal.value.defectQty), formatQty(receiptTotal.value.goodQty), '']

async function voidCurrentReceipt() {
  const r = props.viewingReceipt
  if (!r) return
  const reason = await promptReason(`作废 ${r.no}`, '写一句原因：已回、次品会减回去，生产单状态跟着重算', '作废', true)
  if (!reason) return
  voiding.value = true
  try {
    await productionApi.voidReceipt(props.doc.id, r.id, reason)
    ElMessage.success(`${r.no} 已作废`)
    detailVisible.value = false
    emit('updated')
  } finally {
    voiding.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="createVisible"
    class="receipt-entry-dialog"
    :title="`登记回货 · ${doc.no}`"
    width="820px"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
  >
    <el-alert v-if="uncertain" type="warning" :closable="false" title="登记结果尚未确认。重试会复用原登记内容，不会重复记数；关窗后重开仍保留原登记。"><el-button link @click="restartReceipt">重新登记一笔</el-button></el-alert>
    <div :inert="!!pendingAction || submitting">
    <el-form label-position="top" class="head-grid">
      <el-form-item label="回货日期" required>
        <el-date-picker v-model="form.receiptDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disableFuture" :clearable="false" />
      </el-form-item>
      <el-form-item label="收货仓">
        <el-select v-model="form.warehouseId" filterable clearable placeholder="选成品仓">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="物流单号">
        <el-input v-model="form.logisticsNo" maxlength="60" />
      </el-form-item>
    </el-form>

    <div class="sec-title num">
      <strong>回货数（含次品）</strong>
      <span class="dim">这次 {{ formatQty(totalQty) }} 件，其中次品 {{ formatQty(totalDefect) }}</span>
    </div>
    <SkuMatrix v-model="form.qtys" :sizes="doc.sizes" :colors="matrixColors" :skus="matrixCells" :max="maxOf" :sub="qtySub" />

    <div class="sec-title mt-12"><strong>其中次品</strong></div>
    <SkuMatrix v-model="form.defects" :sizes="doc.sizes" :colors="matrixColors" :skus="matrixCells" :error="defectError" />

    <el-collapse class="mt-12">
      <el-collapse-item title="分色分码备注" name="remarks">
        <div class="remark-grid">
          <template v-for="it in doc.items" :key="it.id">
            <span class="dim">{{ it.colorName }} · {{ it.sizeCode }}</span>
            <el-input v-model="form.remarks[it.id]" size="small" maxlength="100" />
          </template>
        </div>
      </el-collapse-item>
    </el-collapse>

    <el-form label-position="top" class="mt-12">
      <el-form-item label="照片">
        <ImageUpload v-model="form.photos" :max="9" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" maxlength="160" />
      </el-form-item>
    </el-form>

    </div>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitReceipt">{{ uncertain ? '重试原登记' : '登记' }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="detailVisible" :title="viewingReceipt?.no ?? '回货单'" width="680px">
    <div v-if="viewingReceipt">
      <div v-if="viewingReceipt.status === 'VOIDED'" class="void-box">
        已作废：{{ viewingReceipt.voidReason ?? '—' }}（{{ viewingReceipt.voidByName ?? '—' }} · {{ formatDateTime(viewingReceipt.voidTime) }}）
      </div>

      <div class="rcp-head">
        <StatusTag :tone="RECEIPT_STATUS[viewingReceipt.status].tone">{{ RECEIPT_STATUS[viewingReceipt.status].label }}</StatusTag>
        <span class="num">{{ formatDate(viewingReceipt.receiptDate) }}</span>
        <span>收货仓 {{ viewingReceipt.warehouseName ?? '—' }}</span>
        <span>物流 <span class="code">{{ viewingReceipt.logisticsNo ?? '—' }}</span></span>
        <span class="dim">{{ viewingReceipt.createByName ?? '—' }} 登记于 {{ formatDateTime(viewingReceipt.createTime) }}</span>
      </div>

      <ObjectTags v-if="detailVisible" type="PRODUCTION_RECEIPT" :object-id="viewingReceipt.id" :company-id="doc.companyId"
        :editable="canEditReceiptTags" @saved="emit('updated')" />

      <el-table :data="viewingReceipt.items" size="small" row-key="id" show-summary :summary-method="summaryMethod">
        <el-table-column label="颜色" min-width="110">
          <template #default="{ row }">{{ (row as ReceiptItem).colorName }}</template>
        </el-table-column>
        <el-table-column label="尺码" prop="sizeCode" width="70" />
        <el-table-column label="回货" width="80" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.qty) }}</template>
        </el-table-column>
        <el-table-column label="次品" width="80" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="{ danger: row.defectQty > 0 }">{{ formatQty(row.defectQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="良品" width="80" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.goodQty) }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="120">
          <template #default="{ row }">{{ row.remark ?? '' }}</template>
        </el-table-column>
      </el-table>

      <p v-if="viewingReceipt.remark" class="rcp-remark">备注：{{ viewingReceipt.remark }}</p>
      <div v-if="viewingReceipt.photos.length" class="pics">
        <a v-for="p in viewingReceipt.photos" :key="p.id" :href="p.url" target="_blank" rel="noopener">
          <ThumbImage :src="p.thumbUrl" alt="" />
        </a>
      </div>
    </div>

    <template #footer>
      <div class="foot">
        <el-button v-if="viewingReceipt?.actions.includes('void')" type="danger" plain :loading="voiding" @click="voidCurrentReceipt">
          作废这张回货单
        </el-button>
        <span class="gap" />
        <el-button @click="detailVisible = false">关闭</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 14px;
}

.head-grid :deep(.el-select),
.head-grid :deep(.el-date-editor) {
  width: 100%;
}

.sec-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 12.5px;
}

.mt-12 {
  margin-top: 12px;
}

.remark-grid {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) 120px minmax(0, 1fr);
  gap: 6px 10px;
  align-items: center;
  font-size: 12.5px;
}

.rcp-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 12.5px;
}

.rcp-remark {
  margin: 10px 0 0;
  font-size: 12.5px;
}

.pics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.pics img {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.void-box {
  padding: 8px 10px;
  margin-bottom: 10px;
  font-size: 12.5px;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
  border-radius: var(--lm-radius-sm);
}

.danger {
  color: var(--lm-danger-text);
}

.foot {
  display: flex;
  align-items: center;
}

.gap {
  flex: 1;
}
</style>
