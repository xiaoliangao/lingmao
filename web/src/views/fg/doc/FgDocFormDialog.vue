<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { fgDocApi as fgDocApiBase, fgStockApi } from '@/api/fg'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import SkuMatrix, { type MatrixCell } from '@/components/SkuMatrix.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { WarehouseOption } from '@/types/basic'
import { FG_DOC_TYPE, type FgDocDetail, type FgDocItemForm, type FgDocType, type FgShipOrderOption, type FgShipPrefill, type FgStyleOption } from '@/types/fg'
import type { StylePickRow } from '@/types/style'
import type { MaterialPickRow } from '@/types/basic'
import { isFutureBusinessDay } from '@/utils/businessDate'
import { bindCompanyApi } from '@/utils/companyScope'
import { formatQty } from '@/utils/format'
import { orNull } from '@/views/basic/common'
import { fgCells, fgColors, fgSizes } from './fgMatrix'

/**
 * 手工新建、改草稿的成品单据对话框（docs/api/07 §1.3、§3.2）：其他入库、其他出库、发货出库。回货入库走 ReceiveDialog。
 *
 *   <FgDocFormDialog v-model="creating" doc-type="SHIP_OUT" @saved="(id) => router.push(`/fg-out/${id}`)" />
 *   <FgDocFormDialog v-model="editing" :doc-type="d.docType" :editing-doc="d" @saved="refresh" />
 *
 * - 其他入库、其他出库：主体、仓、原因、日期、备注，"选款式"挑款，每款一个矩阵；出库的格子下面"库存 n"，超了标红
 * - 发货出库：需求订单（能搜）、出仓、发到（默认订单的送货要求）、物流单号；订单上每款一个矩阵，格子默认空，
 *   下面"订单 n · 已发 n · 库存 n"，上限 = 最多还能发和库存里小的那个（标红不拦，以审核时的 1001、1004 为准）。
 *   主体跟订单走；改草稿时订单不能换
 * - 每格的备注（导入时带的）改草稿时原样带回
 */
const visible = defineModel<boolean>({ required: true })
const user = useUserStore()

const props = defineProps<{
  docType: Exclude<FgDocType, 'RECEIPT_IN'>
  editingDoc?: FgDocDetail | null
}>()

const emit = defineEmits<{
  saved: [id: string]
}>()

/** 一款一个矩阵 */
interface Block {
  style: FgStyleOption
  cells: MatrixCell[]
  qtys: Record<string, number>
}

const head = reactive({
  companyId: null as string | null,
  warehouseId: null as string | null,
  orderId: null as string | null,
  docDate: todayStr(),
  reason: null as string | null,
  shipTo: '',
  logisticsNo: '',
  remark: '',
})
const blocks = ref<Block[]>([])
const guard = useUnsavedChanges(() => ({ head, qtys: blocks.value.map((b) => b.qtys) }), visible)

const fgDocApi = bindCompanyApi(fgDocApiBase, () => head.companyId)

const activeType = computed(() => (props.editingDoc?.docType ?? props.docType) as Exclude<FgDocType, 'RECEIPT_IN'>)
const meta = computed(() => FG_DOC_TYPE[activeType.value])
const isShip = computed(() => activeType.value === 'SHIP_OUT')
const isOut = computed(() => meta.value.kind === 'OUT')
const title = computed(() => (props.editingDoc ? `编辑 ${props.editingDoc.no}（${meta.value.label}）` : `新建${meta.value.label}`))

const warehouses = ref<WarehouseOption[]>([])
const loading = ref(false)
const saving = ref(false)
const picking = ref(false)

/** 其他出库：出仓现库存；取不到是空的，格子下面不写 */
const stocks = ref(new Map<string, number>())
/** 发货：每格的订单数、已发、上限 */
const shipLines = ref(new Map<string, FgShipPrefill['lines'][number]>())
/** 改草稿：每格原来的备注 */
const remarks = new Map<string, string | null>()

const total = computed(() => blocks.value.reduce((sum, b) => sum + Object.values(b.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0), 0))

function maxOf(id: string): number | null {
  if (isShip.value) {
    const l = shipLines.value.get(id)
    return l ? Math.min(l.maxQty, l.stockQty) : null
  }
  return isOut.value ? (stocks.value.get(id) ?? null) : null
}

function subOf(id: string): string | null {
  if (isShip.value) {
    const l = shipLines.value.get(id)
    return l ? `订单 ${formatQty(l.orderQty)} · 已发 ${formatQty(l.shippedQty)} · 库存 ${formatQty(l.stockQty)}` : null
  }
  if (!isOut.value) return null
  const q = stocks.value.get(id)
  return q == null ? null : `库存 ${formatQty(q)}`
}

const blockTotal = (b: Block) => Object.values(b.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0)

// ---------- 打开 ----------
watch(visible, (on) => {
  if (on) void init()
})

async function init() {
  loading.value = true
  blocks.value = []
  stocks.value = new Map()
  shipLines.value = new Map()
  remarks.clear()
  orderOptions.value = []
  try {
    warehouses.value = await warehouseApi.options('PRODUCT')
    const d = props.editingDoc
    if (d) {
      Object.assign(head, {
        companyId: d.companyId,
        warehouseId: d.warehouseId,
        orderId: d.order?.id ?? null,
        docDate: d.docDate,
        reason: d.reason,
        shipTo: d.shipTo ?? '',
        logisticsNo: d.logisticsNo ?? '',
        remark: d.remark ?? '',
      })
      const qtys = new Map(d.items.map((it) => [it.styleSkuId, it.qty]))
      for (const it of d.items) remarks.set(it.styleSkuId, it.remark)
      if (d.order) {
        orderOptions.value = [{ id: d.order.id, no: d.order.no } as FgShipOrderOption]
        await loadShip(qtys)
      } else {
        blocks.value = d.styles.map((st) => {
          const cells = fgCells(st)
          return { style: st, cells, qtys: Object.fromEntries(cells.filter((c) => qtys.has(c.id)).map((c) => [c.id, qtys.get(c.id) ?? 0])) }
        })
        await refreshStocks()
      }
    } else {
      Object.assign(head, {
        companyId: isShip.value ? null : (user.defaultCompanyId ?? null),
        warehouseId: warehouses.value[0]?.id ?? null,
        orderId: null,
        docDate: todayStr(),
        reason: null,
        shipTo: '',
        logisticsNo: '',
        remark: '',
      })
      if (isShip.value) void searchOrders('')
    }
    guard.markSaved()
  } catch (e) {
    visible.value = false
    throw e
  } finally {
    loading.value = false
  }
}

// ---------- 其他入库、其他出库：选款 ----------
async function onPicked(rows: MaterialPickRow[] | StylePickRow[]) {
  const ids = (rows as StylePickRow[]).map((r) => r.id)
  if (!ids.length) return
  const options = await fgDocApi.styleOptions(ids)
  for (const st of options) {
    if (blocks.value.some((b) => b.style.styleId === st.styleId)) continue
    blocks.value.push({ style: st, cells: fgCells(st), qtys: {} })
  }
  await refreshStocks()
}

function removeBlock(index: number) {
  blocks.value.splice(index, 1)
}

async function refreshStocks() {
  if (!isOut.value || isShip.value || !head.warehouseId || !blocks.value.length) return
  const whId = head.warehouseId
  const skuIds = blocks.value.flatMap((b) => b.cells.map((c) => c.id))
  try {
    const list = await fgStockApi.qty(whId, skuIds, { silent: true })
    // 等返回的时候又换了仓：只认最后选的
    if (whId !== head.warehouseId) return
    // 没库存的 SKU 不返回，按 0 算
    stocks.value = new Map(skuIds.map((id) => [id, 0]))
    for (const x of list) stocks.value.set(x.styleSkuId, x.qty)
  } catch {
    // 只是参考：取不到不写，不影响填单
    stocks.value = new Map()
  }
}

// ---------- 发货出库 ----------
const orderOptions = ref<FgShipOrderOption[]>([])
const orderSearching = ref(false)
let orderSeq = 0

async function searchOrders(keyword: string) {
  const seq = ++orderSeq
  orderSearching.value = true
  try {
    const list = await fgDocApiBase.shipOrders({ keyword: keyword.trim() || undefined, warehouseId: head.warehouseId })
    if (seq === orderSeq) orderOptions.value = list
  } finally {
    if (seq === orderSeq) orderSearching.value = false
  }
}

let prefillSeq = 0
/** 取发货带出：换订单时格子清空，换仓时留着填的数、只换库存 */
async function loadShip(keep?: Map<string, number>) {
  if (!head.orderId) {
    blocks.value = []
    shipLines.value = new Map()
    return
  }
  const seq = ++prefillSeq
  const p = await fgDocApiBase.shipPrefill({ orderId: head.orderId, warehouseId: head.warehouseId, docId: props.editingDoc?.id ?? null })
  if (seq !== prefillSeq) return
  const onOrder = new Set(p.lines.map((l) => l.styleSkuId))
  shipLines.value = new Map(p.lines.map((l) => [l.styleSkuId, l]))
  blocks.value = p.styles.map((st) => {
    const cells = fgCells(st, (id) => onOrder.has(id))
    const qtys = keep ? Object.fromEntries(cells.filter((c) => keep.has(c.id)).map((c) => [c.id, keep.get(c.id) ?? 0])) : {}
    return { style: st, cells, qtys }
  })
  head.companyId = p.companyId
  return p
}

async function onOrderChange() {
  const before = head.shipTo
  const prevDefault = lastDefaultShipTo
  const p = await loadShip()
  // 发到没改过（空的、或还是上一张订单带的）就换成这张订单的送货要求
  if (p && (!before || before === prevDefault)) head.shipTo = p.deliveryReq ?? ''
  lastDefaultShipTo = p?.deliveryReq ?? ''
}
let lastDefaultShipTo = ''

function currentQtys() {
  return new Map(blocks.value.flatMap((b) => Object.entries(b.qtys)))
}

watch(
  () => head.warehouseId,
  (whId, old) => {
    if (!visible.value || loading.value || whId === old) return
    if (isShip.value) {
      if (head.orderId) void loadShip(currentQtys())
      void searchOrders('')
    } else {
      void refreshStocks()
    }
  },
)

// ---------- 保存 ----------
function items(): FgDocItemForm[] {
  return blocks.value.flatMap((b) =>
    b.cells.filter((c) => (b.qtys[c.id] ?? 0) > 0).map((c) => ({ styleSkuId: c.id, qty: b.qtys[c.id] ?? 0, remark: remarks.get(c.id) ?? null })),
  )
}

async function save() {
  if (!head.companyId) return void ElMessage.warning(isShip.value ? '先选需求订单' : '先选主体')
  if (!head.warehouseId) return void ElMessage.warning(isOut.value ? '先选出仓' : '先选入仓')
  if (isShip.value && !head.orderId) return void ElMessage.warning('先选需求订单')
  if (meta.value.reasonDict && !head.reason) return void ElMessage.warning('先选原因')
  const rows = items()
  if (!rows.length) return void ElMessage.warning('一件都没填')

  const body = {
    warehouseId: head.warehouseId,
    docDate: head.docDate,
    reason: meta.value.reasonDict ? head.reason : null,
    shipTo: isOut.value ? orNull(head.shipTo) : null,
    logisticsNo: isOut.value ? orNull(head.logisticsNo) : null,
    remark: orNull(head.remark),
    items: rows,
  }
  saving.value = true
  try {
    const d = props.editingDoc
    if (d) {
      await fgDocApi.update(d.id, { ...body, version: d.version })
      ElMessage.success(`${d.no} 已保存`)
      guard.markSaved()
      visible.value = false
      emit('saved', d.id)
    } else {
      const created = await fgDocApi.create({ ...body, companyId: head.companyId, docType: activeType.value, orderId: isShip.value ? head.orderId : null })
      ElMessage.success(`${created.no} 草稿存好了（${formatQty(total.value)} 件），核对后审核过账`)
      guard.markSaved()
      visible.value = false
      emit('saved', created.id)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="900px" :close-on-click-modal="false" :before-close="guard.beforeClose">
    <div v-loading="loading">
      <el-form label-position="top" class="head-grid">
        <template v-if="isShip">
          <el-form-item label="需求订单" required class="span-2">
            <el-select
              v-model="head.orderId"
              filterable
              remote
              :remote-method="searchOrders"
              :loading="orderSearching"
              :disabled="!!editingDoc"
              placeholder="订单号、款号"
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
        <el-form-item v-else label="主体" required>
          <CompanySelect v-model="head.companyId" :disabled="!!editingDoc" placeholder="选主体" />
        </el-form-item>

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

      <div class="line-bar">
        <span class="dim">
          共 {{ formatQty(total) }} 件
          <template v-if="isShip">；最多发订单数的 103%，也不能超过仓里的库存</template>
        </span>
        <el-button v-if="!isShip" size="small" icon="Plus" @click="picking = true">选款式</el-button>
      </div>

      <div v-for="(b, i) in blocks" :key="b.style.styleId" class="block">
        <div class="block-head">
          <ThumbImage v-if="b.style.image" :src="b.style.image.thumbUrl" alt="" class="thumb" />
          <span class="code">{{ b.style.code }}</span>
          <span class="dim">{{ b.style.name }}</span>
          <span class="dim">{{ formatQty(blockTotal(b)) }} 件</span>
          <el-button v-if="!isShip" link type="danger" size="small" class="remove" @click="removeBlock(i)">去掉这款</el-button>
        </div>
        <SkuMatrix v-model="b.qtys" :sizes="fgSizes(b.style, b.cells)" :colors="fgColors(b.style, b.cells)" :skus="b.cells" :max="maxOf" :sub="subOf" />
      </div>
      <div v-if="!blocks.length && !loading" class="empty dim">
        {{ isShip ? (head.orderId ? '这张订单没有能发的款' : '先选需求订单，订单上的款会列在这里') : '还没有款式：点「选款式」' }}
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="save">{{ editingDoc ? '保存' : '存草稿' }}</el-button>
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
