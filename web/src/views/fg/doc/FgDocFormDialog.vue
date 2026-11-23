<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { fgDocApi, fgStockApi } from '@/api/fg'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import SkuMatrix, { type MatrixCell } from '@/components/SkuMatrix.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges, type DraftRestoreContext } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { WarehouseOption, MaterialPickRow } from '@/types/basic'
import { FG_DOC_TYPE, type FgDocDetail, type FgDocItemForm, type FgDocType, type FgShipOrderOption, type FgShipPrefill, type FgStyleOption } from '@/types/fg'
import type { StylePickRow } from '@/types/style'
import { isFutureBusinessDay } from '@/utils/businessDate'
import { formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'
import { fgCells, fgColors, fgSizes } from './fgMatrix'
import { fgReadError, onFgIdentityChange, readFgBatches, useFgRead } from '../fgRead'
import FgReadFeedback from '../FgReadFeedback.vue'
import { assertDraftSkus, assertFgMatrixBudget, readFgStyles, readManualDraft } from '../fgDraft'

const visible = defineModel<boolean>({ required: true })
const user = useUserStore()
const props = defineProps<{
  docType: Exclude<FgDocType, 'RECEIPT_IN'>
  initialCompanyId?: string | null
  editingDoc?: FgDocDetail | null
}>()
const emit = defineEmits<{ saved: [id: string, companyId: string] }>()
interface Block { style: FgStyleOption; cells: MatrixCell[]; qtys: Record<string, number> }
const head = reactive({ companyId: null as string | null, warehouseId: null as string | null, orderId: null as string | null,
  docDate: todayStr(), reason: null as string | null, shipTo: '', logisticsNo: '', remark: '' })
const blocks = ref<Block[]>([])
const activeType = computed(() => (props.editingDoc?.docType ?? props.docType) as Exclude<FgDocType, 'RECEIPT_IN'>)
const meta = computed(() => FG_DOC_TYPE[activeType.value])
const isShip = computed(() => activeType.value === 'SHIP_OUT')
const isOut = computed(() => meta.value.kind === 'OUT')
const title = computed(() => props.editingDoc ? `编辑 ${props.editingDoc.no}（${meta.value.label}）` : `新建${meta.value.label}`)
const warehouses = ref<WarehouseOption[]>([])
const loading = ref(false)
const saving = ref(false)
const picking = ref(false)
const pickLoading = ref(false)
const loadError = ref<string | null>(null)
const draftError = ref<string | null>(null)
const restoring = ref(false)
const pickError = ref<string | null>(null)
const stockError = ref<string | null>(null)
const stockLoading = ref(false)
const shipError = ref<string | null>(null)
const shipLoading = ref(false)
const orderError = ref<string | null>(null)
const orderOptions = ref<FgShipOrderOption[]>([])
const orderSearching = ref(false)
const stocks = ref(new Map<string, number>())
const shipLines = ref(new Map<string, FgShipPrefill['lines'][number]>())
const remarks = new Map<string, string | null>()
const initialRead = useFgRead()
const stockRead = useFgRead()
const orderRead = useFgRead()
const shipRead = useFgRead()
const pickRead = useFgRead()
const restoreRead = useFgRead()
let presentation = 0
let orderTimer: ReturnType<typeof setTimeout> | undefined
let lastDefaultShipTo = ''
let pickedIds: string[] = []
const guard = useUnsavedChanges(() => ({ docType: activeType.value, head, blocks: blocks.value.map((block) => ({ styleId: block.style.styleId, qtys: block.qtys })) }), visible, {
  scope: () => head.companyId ? JSON.stringify([head.companyId, activeType.value, props.editingDoc?.id ?? 'new', props.editingDoc?.version ?? 0, props.editingDoc?.order?.id ?? null]) : null,
  ready: () => !loading.value && !shipLoading.value && !restoring.value && !loadError.value && !!head.companyId,
  restore: restoreLocalDraft,
})
function discardLocalDraft() { guard.discardDraft(); draftError.value = null }
function cancelReads() {
  initialRead.cancel(); stockRead.cancel(); orderRead.cancel(); shipRead.cancel(); pickRead.cancel(); restoreRead.cancel()
  clearTimeout(orderTimer)
  loading.value = false; stockLoading.value = false; orderSearching.value = false; shipLoading.value = false; pickLoading.value = false; restoring.value = false
}
onBeforeUnmount(() => clearTimeout(orderTimer))
onFgIdentityChange(() => {
  presentation++; cancelReads(); visible.value = false; blocks.value = []; stocks.value.clear(); shipLines.value.clear(); orderOptions.value = []
})
const blockTotal = (b: Block) => Object.values(b.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0)
const total = computed(() => blocks.value.reduce((sum, b) => sum + blockTotal(b), 0))
function maxOf(id: string): number | null {
  const line = shipLines.value.get(id)
  return isShip.value ? (line ? Math.min(line.maxQty, line.stockQty) : null) : isOut.value ? stocks.value.get(id) ?? null : null
}
function subOf(id: string): string | null {
  const line = shipLines.value.get(id)
  if (isShip.value) return line ? `订单 ${formatQty(line.orderQty)} · 已发 ${formatQty(line.shippedQty)} · 库存 ${formatQty(line.stockQty)}` : null
  const qty = stocks.value.get(id)
  return isOut.value && qty != null ? `库存 ${formatQty(qty)}` : null
}
watch(visible, (on) => { if (on) void init(); else { presentation++; cancelReads(); picking.value = false } })
watch(() => [props.editingDoc?.id, props.editingDoc?.version, props.initialCompanyId, props.docType], () => {
  presentation++; cancelReads()
  if (visible.value) void init()
}, { flush: 'sync' })
async function init() {
  cancelReads()
  const read = initialRead.begin()
  loading.value = true
  loadError.value = null; draftError.value = null; pickError.value = null; stockError.value = null; orderError.value = null; shipError.value = null
  blocks.value = []; stocks.value.clear(); shipLines.value.clear(); remarks.clear(); orderOptions.value = []; pickedIds = []; lastDefaultShipTo = ''
  const d = props.editingDoc
  Object.assign(head, { companyId: d?.companyId ?? props.initialCompanyId ?? user.defaultCompanyId,
    warehouseId: d?.warehouseId ?? null, orderId: d?.order?.id ?? null, docDate: d?.docDate ?? todayStr(),
    reason: d?.reason ?? null, shipTo: d?.shipTo ?? '', logisticsNo: d?.logisticsNo ?? '', remark: d?.remark ?? '' })
  try {
    const options = await read.run(() => warehouseApi.options('PRODUCT', { signal: read.signal, silent: true }))
    if (!read.current()) return
    warehouses.value = options
    if (!d) head.warehouseId = options[0]?.id ?? null
    else {
      const qtys = new Map(d.items.map((item) => [item.styleSkuId, item.qty]))
      for (const item of d.items) remarks.set(item.styleSkuId, item.remark)
      if (d.order) {
        orderOptions.value = [{ id: d.order.id, no: d.order.no, companyId: d.companyId, orderType: '', status: '', statusLabel: '', styleCodes: '', totalQty: 0, shippedQty: 0, stockQty: null }]
        await loadShip(qtys)
      } else {
        const styles = await readFgStyles(d.styles.map((style) => style.styleId), (ids) => fgDocApi.styleOptions(ids, { companyId: d.companyId, signal: read.signal, silent: true }), read.signal)
        if (!read.current()) return
        blocks.value = styles.map((style) => {
          const cells = fgCells(style)
          return { style, cells, qtys: Object.fromEntries(cells.filter((cell) => qtys.has(cell.id)).map((cell) => [cell.id, qtys.get(cell.id) ?? 0])) }
        })
        await refreshStocks()
      }
    }
    if (!read.current()) return
    if (isShip.value && !d && head.companyId) void fetchOrders('')
    guard.markSaved()
  } catch (cause) {
    if (read.current()) loadError.value = fgReadError(cause)
  } finally { if (read.current()) loading.value = false }
}
async function restoreLocalDraft(value: unknown, context: DraftRestoreContext) {
  const read = restoreRead.begin(), company = head.companyId
  draftError.value = null; restoring.value = true
  try {
    if (!company) throw new ApiError(400, '请先选择主体')
    const saved = readManualDraft(value, company, activeType.value)
    if (saved.head.warehouseId && !warehouses.value.some((warehouse) => warehouse.id === saved.head.warehouseId)) throw new ApiError(409, '草稿仓库已不可用，未恢复任何内容')
    if (props.editingDoc && saved.head.orderId !== (props.editingDoc.order?.id ?? null)) throw new ApiError(409, '草稿来源不一致，未恢复任何内容')
    let styles: FgStyleOption[] = [], source: FgShipPrefill | undefined
    if (isShip.value) {
      if (saved.head.orderId) {
        source = await fgDocApi.shipPrefill({ companyId: company, orderId: saved.head.orderId, warehouseId: saved.head.warehouseId, docId: props.editingDoc?.id }, { signal: read.signal, silent: true })
        if (source.companyId !== company || source.orderId !== saved.head.orderId) throw new ApiError(409, '草稿来源已变化，未恢复任何内容')
        styles = source.styles
      } else if (saved.blocks.length) throw new ApiError(400, '草稿缺少需求订单，未恢复任何明细')
    } else {
      if (saved.head.orderId) throw new ApiError(400, '本地草稿来源无效，未恢复任何内容')
      styles = await readFgStyles(saved.blocks.map((block) => block.styleId), (ids) => fgDocApi.styleOptions(ids, { companyId: company, signal: read.signal, silent: true }), read.signal)
    }
    assertFgMatrixBudget(styles)
    const byStyle = new Map(styles.map((style) => [style.styleId, style]))
    const sourceIds = source ? new Set(source.lines.map((line) => line.styleSkuId)) : undefined
    const savedQtys = new Map(saved.blocks.map((block) => [block.styleId, block.qtys]))
    for (const block of saved.blocks) {
      const style = byStyle.get(block.styleId)
      if (!style) throw new ApiError(409, '草稿包含已不可用的款式，未恢复任何明细，请核对原草稿')
      const cells = fgCells(style, sourceIds ? (id) => sourceIds.has(id) : undefined)
      assertDraftSkus(block.qtys, new Set(cells.map((cell) => cell.id)))
    }
    const nextStyles = source ? styles : saved.blocks.map((block) => byStyle.get(block.styleId)!)
    const nextBlocks = nextStyles.map((style) => ({ style, cells: fgCells(style, sourceIds ? (id) => sourceIds.has(id) : undefined), qtys: savedQtys.get(style.styleId) ?? {} }))
    let nextStocks = new Map<string, number>()
    if (isOut.value && !isShip.value && saved.head.warehouseId) {
      const ids = nextBlocks.flatMap((block) => block.cells.map((cell) => cell.id)), warehouse = saved.head.warehouseId
      const rows = await readFgBatches(ids, (batch) => fgStockApi.qty(warehouse, batch, { companyId: company, signal: read.signal, silent: true }), read.signal)
      if (rows.some((row) => row.companyId !== company || row.warehouseId !== warehouse)) throw new ApiError(409, '库存主体或仓库不一致，未恢复任何内容')
      nextStocks = new Map(ids.map((id) => [id, 0]))
      for (const row of rows) nextStocks.set(row.styleSkuId, row.qty)
    }
    if (!read.current() || !context.current() || !visible.value || head.companyId !== company) return
    Object.assign(head, saved.head)
    blocks.value = nextBlocks; stocks.value = nextStocks
    shipLines.value = new Map((source?.lines ?? []).map((line) => [line.styleSkuId, line]))
    if (source) {
      orderOptions.value = [{ id: source.orderId, no: source.orderNo, companyId: company, orderType: '', status: '', statusLabel: '', styleCodes: '', totalQty: 0, shippedQty: 0, stockQty: null }]
      lastDefaultShipTo = source.deliveryReq ?? ''
    }
  } catch (cause) { if (read.current() && context.current()) draftError.value = fgReadError(cause) }
  finally { if (read.current()) restoring.value = false }
}
async function onPicked(rows: MaterialPickRow[] | StylePickRow[]) {
  pickedIds = rows.filter((row): row is StylePickRow => 'id' in row).map((row) => row.id)
  await loadPicked()
}
async function loadPicked() {
  const read = pickRead.begin()
  const company = head.companyId
  pickError.value = null; pickLoading.value = true
  try {
    if (!company) throw new ApiError(400, '请先选择主体')
    const options = await readFgStyles(pickedIds, (ids) => fgDocApi.styleOptions(ids, { companyId: company, signal: read.signal, silent: true }), read.signal)
    if (!read.current() || !visible.value || head.companyId !== company) return
    assertFgMatrixBudget([...blocks.value.map((block) => block.style), ...options.filter((style) => !blocks.value.some((block) => block.style.styleId === style.styleId))])
    for (const style of options) if (!blocks.value.some((block) => block.style.styleId === style.styleId)) blocks.value.push({ style, cells: fgCells(style), qtys: {} })
    pickedIds = []
    await refreshStocks()
  } catch (cause) { if (read.current()) pickError.value = fgReadError(cause) }
  finally { if (read.current()) pickLoading.value = false }
}
function removeBlock(index: number) { blocks.value.splice(index, 1); void refreshStocks() }
async function refreshStocks() {
  const read = stockRead.begin()
  const company = head.companyId, warehouse = head.warehouseId
  stocks.value = new Map(); stockError.value = null; stockLoading.value = false
  if (!isOut.value || isShip.value || !company || !warehouse || !blocks.value.length) return
  const ids = blocks.value.flatMap((block) => block.cells.map((cell) => cell.id))
  stockLoading.value = true
  try {
    const rows = await readFgBatches(ids, (batch) => fgStockApi.qty(warehouse, batch, { companyId: company, signal: read.signal, silent: true }), read.signal)
    if (!read.current() || head.companyId !== company || head.warehouseId !== warehouse) return
    if (rows.some((row) => row.companyId !== company || row.warehouseId !== warehouse)) throw new ApiError(409, '库存主体或仓库不一致，请重新查询')
    const quantities = new Map(ids.map((id) => [id, 0]))
    for (const row of rows) quantities.set(row.styleSkuId, row.qty)
    stocks.value = quantities
  } catch (cause) { if (read.current()) stockError.value = fgReadError(cause) }
  finally { if (read.current()) stockLoading.value = false }
}
function searchOrders(keyword: string) {
  clearTimeout(orderTimer); orderRead.cancel(); orderSearching.value = false
  orderTimer = setTimeout(() => { void fetchOrders(keyword) }, 250)
}
async function fetchOrders(keyword: string) {
  const read = orderRead.begin()
  const company = head.companyId
  orderError.value = null
  if (!company) { orderOptions.value = []; return }
  orderSearching.value = true
  try {
    const rows = await fgDocApi.shipOrders({ companyId: company, keyword: keyword.trim() || undefined, warehouseId: head.warehouseId }, { signal: read.signal, silent: true })
    if (read.current() && company === head.companyId) orderOptions.value = rows
  } catch (cause) { if (read.current()) orderError.value = fgReadError(cause) }
  finally { if (read.current()) orderSearching.value = false }
}
async function loadShip(keep?: Map<string, number>) {
  const read = shipRead.begin()
  const order = head.orderId, company = head.companyId, warehouse = head.warehouseId
  shipError.value = null; shipLines.value = new Map(); shipLoading.value = false
  if (!keep) blocks.value = []
  if (!order || !company) return
  shipLoading.value = true
  try {
    const result = await fgDocApi.shipPrefill({ companyId: company, orderId: order, warehouseId: warehouse, docId: props.editingDoc?.id }, { signal: read.signal, silent: true })
    if (!read.current() || head.companyId !== company || head.orderId !== order || head.warehouseId !== warehouse) return
    if (result.companyId !== company || result.orderId !== order) throw new ApiError(409, '订单主体不一致，请重新选择')
    const ids = new Set(result.lines.map((line) => line.styleSkuId))
    shipLines.value = new Map(result.lines.map((line) => [line.styleSkuId, line]))
    blocks.value = result.styles.map((style) => {
      const cells = fgCells(style, (id) => ids.has(id))
      return { style, cells, qtys: keep ? Object.fromEntries(cells.filter((cell) => keep.has(cell.id)).map((cell) => [cell.id, keep.get(cell.id) ?? 0])) : {} }
    })
    return result
  } catch (cause) { if (read.current()) shipError.value = fgReadError(cause) }
  finally { if (read.current()) shipLoading.value = false }
}
async function onOrderChange() {
  const useDefault = !head.shipTo || head.shipTo === lastDefaultShipTo
  if (useDefault) head.shipTo = ''
  const source = await loadShip()
  if (source) { if (useDefault) head.shipTo = source.deliveryReq ?? ''; lastDefaultShipTo = source.deliveryReq ?? '' }
}
function currentQtys() { return new Map(blocks.value.flatMap((block) => Object.entries(block.qtys))) }
watch(() => head.companyId, (company, old) => {
  if (!visible.value || loading.value || restoring.value || company === old) return
  presentation++; stockRead.cancel(); orderRead.cancel(); shipRead.cancel(); pickRead.cancel(); restoreRead.cancel(); clearTimeout(orderTimer)
  blocks.value = []; stocks.value = new Map(); shipLines.value = new Map(); orderOptions.value = []; head.orderId = null; head.shipTo = ''; lastDefaultShipTo = ''; pickedIds = []
  stockError.value = null; shipError.value = null; pickError.value = null; orderError.value = null; draftError.value = null
  if (isShip.value && company) void fetchOrders('')
  guard.markSaved()
}, { flush: 'sync' })
watch(() => head.warehouseId, (warehouse, old) => {
  if (!visible.value || loading.value || restoring.value || warehouse === old) return
  if (isShip.value) { if (head.orderId) void loadShip(currentQtys()); void fetchOrders('') }
  else void refreshStocks()
}, { flush: 'sync' })
function items(): FgDocItemForm[] {
  return blocks.value.flatMap((block) => block.cells.filter((cell) => (block.qtys[cell.id] ?? 0) > 0)
    .map((cell) => ({ styleSkuId: cell.id, qty: block.qtys[cell.id] ?? 0, remark: remarks.get(cell.id) ?? null })))
}
async function save() {
  if (saving.value || loading.value || restoring.value || pickLoading.value || shipLoading.value || loadError.value || draftError.value || (isShip.value && shipError.value)) return
  const company = head.companyId, warehouse = head.warehouseId
  if (!company) return void ElMessage.warning('先选主体')
  if (!warehouse) return void ElMessage.warning(isOut.value ? '先选出仓' : '先选入仓')
  if (isShip.value && !head.orderId) return void ElMessage.warning('先选需求订单')
  if (meta.value.reasonDict && !head.reason) return void ElMessage.warning('先选原因')
  if (blocks.value.some((block) => Object.values(block.qtys).some((qty) => !Number.isSafeInteger(qty) || qty < 0 || qty > 99_999_999))) return void ElMessage.warning('每格件数必须是 0 至 99999999 的整数')
  const rows = items()
  if (!rows.length) return void ElMessage.warning('一件都没填')
  if (rows.length > 2000) return void ElMessage.warning('一张单最多录 2000 条正数色码，请分开录单')
  const body = { companyId: company, warehouseId: warehouse, docDate: head.docDate, reason: meta.value.reasonDict ? head.reason : null,
    shipTo: isOut.value ? orNull(head.shipTo) : null, logisticsNo: isOut.value ? orNull(head.logisticsNo) : null, remark: orNull(head.remark), items: rows }
  const own = presentation
  saving.value = true
  try {
    const d = props.editingDoc
    const result = d ? (await fgDocApi.update(d.id, { ...body, version: d.version }), { id: d.id, no: d.no })
      : await fgDocApi.create({ ...body, docType: activeType.value, orderId: isShip.value ? head.orderId : null })
    if (own !== presentation || !visible.value) return
    ElMessage.success(d ? `${result.no} 已保存` : `${result.no} 草稿存好了，由别人审核过账`)
    guard.markSaved(); visible.value = false; emit('saved', result.id, company)
  } finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="900px" :close-on-click-modal="false" :before-close="guard.beforeClose">
    <p v-if="loadError"><FgReadFeedback :error="loadError" :loading="loading" label="表单" @retry="init" /></p>
    <p v-if="draftError"><FgReadFeedback :error="draftError" :loading="restoring" label="草稿恢复" @retry="guard.retryDraft" /> <el-button link @click="discardLocalDraft">放弃本地草稿</el-button></p>
    <div v-loading="loading || restoring" :inert="loading || restoring || saving || !!loadError">
      <el-form label-position="top" class="head-grid">
        <el-form-item label="主体" required>
          <CompanySelect v-model="head.companyId" :disabled="!!editingDoc" placeholder="选主体" />
        </el-form-item>
        <template v-if="isShip">
          <el-form-item label="需求订单" required class="span-2">
            <el-select
              v-model="head.orderId"
              filterable
              remote
              :remote-method="searchOrders"
              :loading="orderSearching"
              :disabled="!!editingDoc || !head.companyId"
              placeholder="先选主体，再搜订单号、款号"
              @change="onOrderChange"
            >
              <el-option v-for="o in orderOptions" :key="o.id" :label="o.no" :value="o.id">
                <span class="code">{{ o.no }}</span>
                <span v-if="o.styleCodes" class="dim opt-sub">{{ o.styleCodes }}</span>
                <span v-if="o.totalQty != null" class="dim opt-sub">
                  已发 {{ formatQty(o.shippedQty) }} / {{ formatQty(o.totalQty) }}<template v-if="o.stockQty != null"> · 仓里 {{ formatQty(o.stockQty) }}</template>
                </span>
              </el-option>
            </el-select>
          </el-form-item>
        </template>

        <el-form-item :label="isOut ? '出仓' : '入仓'" required>
          <el-select v-model="head.warehouseId" filterable placeholder="选成品仓">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="meta.reasonDict" label="原因" required>
          <DictSelect v-model="head.reason" :type="meta.reasonDict" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="head.docDate" type="date" value-format="YYYY-MM-DD" :disabled-date="isFutureBusinessDay" :clearable="false" />
        </el-form-item>

        <template v-if="isOut">
          <el-form-item label="发到" class="span-2">
            <el-input v-model="head.shipTo" maxlength="200" :placeholder="isShip ? '默认订单的送货要求' : '寄给谁、地址'" />
          </el-form-item>
          <el-form-item label="物流单号">
            <el-input v-model="head.logisticsNo" maxlength="60" />
          </el-form-item>
        </template>
        <el-form-item label="备注" :class="isOut ? '' : 'span-2'">
          <el-input v-model="head.remark" maxlength="500" />
        </el-form-item>
      </el-form>

      <p v-if="orderError"><FgReadFeedback :error="orderError" :loading="orderSearching" label="订单选项" @retry="fetchOrders('')" /></p>
      <p v-if="shipLoading || shipError"><FgReadFeedback :error="shipError" :loading="shipLoading" label="发货带出" @retry="loadShip(currentQtys())" /></p>
      <p v-if="stockLoading || stockError"><FgReadFeedback :error="stockError" :loading="stockLoading" label="库存参考" @retry="refreshStocks" /></p>
      <p v-if="pickError"><FgReadFeedback :error="pickError" :loading="pickLoading" label="款式矩阵" @retry="loadPicked" /></p>
      <div class="line-bar">
        <span class="dim">
          共 {{ formatQty(total) }} 件
          <template v-if="isShip">；最多发订单数的 103%，也不能超过仓里的库存</template>
        </span>
        <el-button v-if="!isShip" size="small" icon="Plus" :disabled="!head.companyId" :loading="pickLoading" @click="picking = true">选款式</el-button>
      </div>

      <div v-for="(b, i) in blocks" :key="b.style.styleId" class="block">
        <div class="block-head">
          <ThumbImage v-if="b.style.image" :src="b.style.image.thumbUrl" alt="" class="thumb" loading="lazy" />
          <span class="code">{{ b.style.code }}</span>
          <span class="dim">{{ b.style.name }}</span>
          <span class="dim">{{ formatQty(blockTotal(b)) }} 件</span>
          <el-button v-if="!isShip" link type="danger" size="small" class="remove" @click="removeBlock(i)">去掉这款</el-button>
        </div>
        <SkuMatrix v-model="b.qtys" :sizes="fgSizes(b.style, b.cells)" :colors="fgColors(b.style, b.cells)" :skus="b.cells" :max-digits="8" :max="maxOf" :sub="subOf" />
      </div>
      <div v-if="!blocks.length && !loading && !shipLoading && !shipError && !pickError && !loadError" class="empty dim">
        {{ isShip ? (head.orderId ? '这张订单没有能发的款' : '先选需求订单，订单上的款会列在这里') : '还没有款式：点「选款式」' }}
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || restoring || pickLoading || shipLoading || !!loadError || !!draftError || (isShip && !!shipError)" @click="save">{{ editingDoc ? '保存' : '存草稿' }}</el-button>
    </template>
  </el-dialog>

  <DataPick v-if="!isShip" v-model="picking" kind="style" :have="blocks.map((b) => b.style.styleId)" @pick="onPicked" />
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 14px;
}

.head-grid .span-2 {
  grid-column: span 2;
}

.head-grid :deep(.el-select),
.head-grid :deep(.el-date-editor) {
  width: 100%;
}

.opt-sub {
  margin-left: 10px;
  font-size: 12px;
}

.line-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 4px 0 10px;
  font-size: 12.5px;
}

.block {
  margin-bottom: 16px;
}

.block-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 13px;
}

.block-head .remove {
  margin-left: auto;
}

.thumb {
  flex: none;
  width: 22px;
  height: 22px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.empty {
  padding: 28px 0;
  text-align: center;
  font-size: 13px;
}

@media (max-width: 820px) {
  .head-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
