<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import VirtualDataTable, { type VirtualColumn } from '@/components/VirtualDataTable.vue'
import StockLogExportDialog from '@/components/StockLogExportDialog.vue'
import { useSeekTable } from '@/composables/useSeekTable'
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { warehouseApi } from '@/api/basic'
import { stockApi, stockLogApi } from '@/api/inventory'
import { reorderApi, supplyApi } from '@/api/purchase'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import { WAREHOUSE_TYPE_LABEL, type WarehouseOption } from '@/types/basic'
import {
  STOCK_CHANGE_TYPE_LABEL,
  type StockChangeType,
  type StockLogRow,
  type StockMovementRow,
  type StockRow,
  type StockSummaryRow,
} from '@/types/inventory'
import { businessToday } from '@/utils/businessDate'
import type { SkuSupply } from '@/types/purchase'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()

/**
 * 库存（/stock，docs/api/04 §1.5、§3.6、§4）：明细 / 按物料 / 进销存 / 日志四个视图，认 ?view=log（只认 /stock 自己的地址）。
 * - 进销存（二期，RFC 016 §4）：期间必填（默认本月、清不掉），入库、出库按类型拆开的列在"显示列"里勾
 * - 明细的在途、低于最小库存（标黄）、按物料的在途要等 supplyApi.bySkus 回来：和主表分开取，没回来、取不到都写"—"，不拖住主表
 * - 库存是 0 的格子有在途也默认列（用户 11-22 定）：还没有库存记录的那种 id 是 null，行的 key 用 仓库 × SKU
 * - 页头：补货提醒（n）、盘点（去新建日常盘点）、导入期初库存、导出
 * - 点明细的一行：弹它的库存日志（带上仓库和 SKU）
 */
type View = 'detail' | 'summary' | 'movement' | 'logs'
const VIEW_OPTIONS: { label: string; value: View }[] = [
  { label: '明细', value: 'detail' },
  { label: '按物料', value: 'summary' },
  { label: '进销存', value: 'movement' },
  { label: '日志', value: 'logs' },
]
const WH_TYPE_OPTIONS = [
  { label: '物料仓', value: 'MATERIAL' },
  { label: '工厂仓', value: 'FACTORY' },
  { label: '全部', value: '' },
]
const CHANGE_TYPE_OPTIONS = Object.entries(STOCK_CHANGE_TYPE_LABEL).map(([value, label]) => ({ value: value as StockChangeType, label }))

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const units = useDict('unit')
const materialTypes = useDict('material_type')

// 三个视图各挑各的列。仓库、物料（日志是日期、单号）固定在左边、总在；带 hidden 的默认不显示，在"显示列"里勾
const detailCols = useColumns('stock', [
  { key: 'qty', label: '数量' },
  { key: 'transit', label: '在途' },
  { key: 'safety', label: '最小库存' },
  { key: 'updated', label: '最近变动' },
  { key: 'warehouseCode', label: '仓库编码', hidden: true },
  { key: 'materialType', label: '物料类型', hidden: true },
])
const summaryCols = useColumns('stock-summary', [
  { key: 'material', label: '物料仓合计' },
  { key: 'factory', label: '工厂净托管' },
  { key: 'total', label: '合计' },
  { key: 'transit', label: '在途' },
  { key: 'safety', label: '最小库存' },
  { key: 'materialType', label: '物料类型', hidden: true },
])
type MovementPart = { key: keyof StockMovementRow & string; label: string }
/**
 * 入库、出库拆开的列（默认藏着）：反审核算进原来那一列，所以可能是负数。
 * 领料出库在工厂仓那边是"领料入厂"、退料入库在工厂仓那边是"退料出厂"，几列加起来总等于入库、出库
 */
const IN_PARTS: MovementPart[] = [
  { key: 'purchaseIn', label: '采购入库' },
  { key: 'returnIn', label: '退料入库' },
  { key: 'issueIn', label: '领料入厂' },
  { key: 'otherIn', label: '其他入库' },
  { key: 'transferIn', label: '调入' },
  { key: 'countGain', label: '盘盈' },
  { key: 'openingIn', label: '期初录入' },
]
const OUT_PARTS: MovementPart[] = [
  { key: 'issueOut', label: '领料出库' },
  { key: 'returnOut', label: '退料出厂' },
  { key: 'purchaseReturn', label: '采购退货' },
  { key: 'otherOut', label: '其他出库' },
  { key: 'transferOut', label: '调出' },
  { key: 'countLoss', label: '盘亏' },
]
const movementCols = useColumns('stock-movement', [
  { key: 'unit', label: '单位' },
  { key: 'opening', label: '期初' },
  { key: 'in', label: '入库' },
  ...IN_PARTS.map((p) => ({ ...p, hidden: true })),
  { key: 'out', label: '出库' },
  ...OUT_PARTS.map((p) => ({ ...p, hidden: true })),
  { key: 'closing', label: '期末' },
  { key: 'materialType', label: '物料类型', hidden: true },
])
const logCols = useColumns('stock-log', [
  { key: 'type', label: '类型' },
  { key: 'warehouse', label: '仓库' },
  { key: 'material', label: '物料' },
  { key: 'changeQty', label: '变动数量' },
  { key: 'balance', label: '结存' },
  { key: 'valuation', label: '估值 / 金额' },
  { key: 'dyeLot', label: '缸号' },
  { key: 'operator', label: '操作人' },
  { key: 'createTime', label: '记账时间', hidden: true },
])

const viewFromQuery = (): View => (route.query.view === 'log' ? 'logs' : 'detail')
const viewMode = ref<View>(viewFromQuery())
const warehouses = ref<WarehouseOption[]>([])
/** 在途、低于最小库存：null = 还没回来或取不到，那两列写"—" */
const supplyMap = ref<Record<string, SkuSupply> | null>(null)
const reorderCount = ref(0)

// ---------- 视图 1：库存明细 ----------
const detailTable = useTable({
  immediate: viewMode.value === 'detail',
  normalize: ({ whType, ...rest }) => ({ ...rest, warehouseType: whType || undefined }),
  fetch: stockApi.page,
  exportUrl: '/api/stocks/export',
  exportName: '库存明细.xlsx',
  query: {
    keyword: '',
    whType: 'MATERIAL' as '' | 'MATERIAL' | 'FACTORY',
    warehouseId: undefined as string | undefined,
    includeZero: false,
  },
})

let supplySeq = 0
watch(detailTable.list, async (rows) => {
  const seq = ++supplySeq
  supplyMap.value = null
  const skuIds = [...new Set(rows.map((r) => r.materialSkuId))]
  if (!skuIds.length) return
  try {
    const supplies = await supplyApi.bySkus(skuIds)
    if (seq === supplySeq) supplyMap.value = Object.fromEntries(supplies.map((s) => [s.materialSkuId, s]))
  } catch {
    // 取不到：在途、标黄写"—"，主表照常
  }
})

/** 只有在途、还没有库存记录的格子 id 是 null */
const stockRowKey = (row: StockRow) => row.id ?? `transit-${row.warehouseId}-${row.materialSkuId}`

function transitText(row: StockRow): string {
  const s = supplyMap.value?.[row.materialSkuId]
  if (!s) return '—'
  const qty = s.transits.filter((t) => t.warehouseId === row.warehouseId).reduce((acc, t) => acc + t.qty, 0)
  return qty ? formatQty(qty) : '—'
}

function isBelowSafety(row: StockRow): boolean {
  return row.warehouseType === 'MATERIAL' && !!supplyMap.value?.[row.materialSkuId]?.belowSafety
}

const detailRowClass = ({ row }: { row: StockRow }) => (isBelowSafety(row) ? 'clickable warn-row' : 'clickable')

// ---------- 视图 2：按物料汇总 ----------
const summaryTable = useTable({
  immediate: false,
  fetch: (params) => stockApi.summary(params),
  exportUrl: '/api/stocks/summary/export',
  exportName: '库存汇总.xlsx',
  query: {
    keyword: '',
    includeZero: false,
  },
})

/** 按物料的在途：送各个仓（物料仓、直发工厂仓）的加起来；null = 还没回来或取不到 */
const summarySupplyMap = ref<Record<string, SkuSupply> | null>(null)
let summarySupplySeq = 0
watch(summaryTable.list, async (rows) => {
  const seq = ++summarySupplySeq
  summarySupplyMap.value = null
  const skuIds = rows.map((r) => r.materialSkuId)
  if (!skuIds.length) return
  try {
    const supplies = await supplyApi.bySkus(skuIds)
    if (seq === summarySupplySeq) summarySupplyMap.value = Object.fromEntries(supplies.map((s) => [s.materialSkuId, s]))
  } catch {
    // 取不到：在途写"—"，主表照常
  }
})
function summaryTransitText(row: StockSummaryRow): string {
  const s = summarySupplyMap.value?.[row.materialSkuId]
  if (!s) return '—'
  const qty = s.transits.reduce((acc, t) => acc + t.qty, 0)
  return qty ? formatQty(qty) : '—'
}

// ---------- 视图 3：进销存 ----------
/** 本月：1 号到今天（东八区） */
const thisMonth = (): [string, string] => {
  const today = businessToday()
  return [`${today.slice(0, 8)}01`, today]
}
const movementTable = useTable({
  immediate: false,
  normalize: ({ whType, period, ...rest }) => ({
    ...rest,
    warehouseType: whType || undefined,
    bizDateFrom: period?.[0] ?? thisMonth()[0],
    bizDateTo: period?.[1] ?? thisMonth()[1],
  }),
  fetch: (params) => stockApi.movement(params),
  exportUrl: '/api/stocks/movement/export',
  exportName: '进销存.xlsx',
  query: {
    keyword: '',
    whType: 'MATERIAL' as '' | 'MATERIAL' | 'FACTORY',
    warehouseId: undefined as string | undefined,
    period: thisMonth() as [string, string],
  },
})
/** 期间清不掉：清空就回到本月 */
function onPeriod(val: [string, string] | null | undefined) {
  movementTable.query.period = val ?? thisMonth()
  movementTable.search()
}
const partText = (v: number) => (v ? formatQty(v) : '')

// ---------- 视图 4：库存日志 ----------
const logDateRange = ref<[string, string] | null>(null)
const exportDialog = ref<InstanceType<typeof StockLogExportDialog>>()
const logTable = useSeekTable({
  immediate: false,
  fetch: (params) => stockLogApi.seek(params),
  exporter: (filters) => exportDialog.value?.open(filters),
  query: {
    keyword: '',
    warehouseId: undefined as string | undefined,
    changeType: [] as StockChangeType[],
    bizDateFrom: undefined as string | undefined,
    bizDateTo: undefined as string | undefined,
  },
})

const logVirtualColumns = computed<VirtualColumn[]>(() => [
  { key: 'bizDate', label: '日期', width: 116, fixed: 'left' as const },
  { key: 'docNo', label: '单号', width: 164, fixed: 'left' as const },
  ...(logCols.show.type ? [{ key: 'changeType', label: '类型', width: 132 }] : []),
  ...(logCols.show.warehouse ? [{ key: 'warehouseName', label: '仓库', width: 130 }] : []),
  ...(logCols.show.material ? [{ key: 'material', label: '物料', width: 240 }] : []),
  ...(logCols.show.changeQty ? [{ key: 'changeQty', label: '变动数量', width: 130 }] : []),
  ...(logCols.show.balance ? [{ key: 'balanceQty', label: '结存', width: 100 }] : []),
  ...(canSeeCost.value && logCols.show.valuation ? [{ key: 'valuation', label: '单价 / 金额', width: 200 }] : []),
  ...(logCols.show.dyeLot ? [{ key: 'dyeLot', label: '缸号', width: 110 }] : []),
  ...(logCols.show.operator ? [{ key: 'operatorName', label: '操作人', width: 100, fixed: 'right' as const }] : []),
  ...(logCols.show.createTime ? [{ key: 'createTime', label: '记账时间', width: 170 }] : []),
])

function onLogDate(val: [string, string] | null | undefined) {
  logDateRange.value = val ?? null
  logTable.query.bizDateFrom = val?.[0]
  logTable.query.bizDateTo = val?.[1]
}

function switchView(v: View) {
  viewMode.value = v
  if (v === 'detail') detailTable.search()
  else if (v === 'summary') summaryTable.search()
  else if (v === 'movement') movementTable.search()
  else logTable.search()
}

if (viewMode.value === 'logs') logTable.search()

watch(
  () => route.query.view,
  () => {
    if (route.path !== '/stock') return
    const v = viewFromQuery()
    if (v === 'logs' && viewMode.value !== 'logs') switchView('logs')
  },
)

const warehouseName = (id: string | undefined) => (id ? (warehouses.value.find((w) => w.id === id)?.name ?? '已选') : '')
const changeTypeText = computed(() => logTable.query.changeType.map((t) => STOCK_CHANGE_TYPE_LABEL[t]).join('、'))

async function loadMeta() {
  warehouses.value = await warehouseApi.options()
  if (user.hasPerm('purchase:order:list')) {
    // 只为页头上的数：取不到就不显示数
    try {
      reorderCount.value = (await reorderApi.list()).length
    } catch {
      reorderCount.value = 0
    }
  }
}

onMounted(loadMeta)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void loadMeta()
  if (viewMode.value === 'detail') void detailTable.load()
  else if (viewMode.value === 'summary') void summaryTable.load()
  else if (viewMode.value === 'movement') void movementTable.load()
  else void logTable.load()
})

const filteredWhOptions = computed(() =>
  warehouses.value.filter((w) => w.type !== 'PRODUCT'),
)
type WhType = '' | 'MATERIAL' | 'FACTORY'
/** 明细、进销存的仓库下拉跟着"物料仓 / 工厂仓"页签走 */
const whOptionsOf = (type: WhType) => filteredWhOptions.value.filter((w) => !type || w.type === type)
/** 切了页签：已选的仓库不是这一类的就清掉（不然条件互相矛盾，查出来必是空的），再查 */
function onWhType(query: { whType: WhType; warehouseId?: string }, search: () => void) {
  if (query.warehouseId && !whOptionsOf(query.whType).some((w) => w.id === query.warehouseId)) query.warehouseId = undefined
  search()
}
/** 页头"导出"转圈：看当前视图那张表 */
const exporting = computed(() => {
  if (viewMode.value === 'detail') return detailTable.exporting.value
  if (viewMode.value === 'summary') return summaryTable.exporting.value
  if (viewMode.value === 'movement') return movementTable.exporting.value
  return logTable.exporting.value
})

// ---------- 单仓单 SKU 流水浮窗 ----------
const cellLogVisible = ref(false)
const cellLogTitle = ref('')
const cellLogRows = ref<StockLogRow[]>([])
const cellLogLoading = ref(false)

const cellRequest = useLatestRequest()
async function openCellLogs(row: StockRow) {
  const read = cellRequest.begin()
  cellLogTitle.value = `${row.warehouseName} · ${row.skuCode} 库存日志`
  cellLogVisible.value = true
  cellLogLoading.value = true
  try {
    const res = await read.run(() => stockLogApi.page({
      page: 1,
      size: 100,
      warehouseId: row.warehouseId,
      materialSkuId: row.materialSkuId,
    }))
    if (!read.current()) return
    cellLogRows.value = res.list
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) cellLogLoading.value = false
  }
}

function goLogDoc(row: StockLogRow) {
  const to = docRoute({ type: row.docType, kind: row.docKind, id: row.docId, parentId: null })
  if (to) {
    cellLogVisible.value = false
    router.push(to)
  }
}

function doExport() {
  if (viewMode.value === 'detail') void detailTable.exportFile()
  else if (viewMode.value === 'summary') void summaryTable.exportFile()
  else if (viewMode.value === 'movement') void movementTable.exportFile()
  else void logTable.exportFile()
}
watch(cellLogVisible, (on) => { if (!on) cellRequest.cancel() })
</script>

<template>
  <div>
    <el-alert title="工厂仓显示我方物料的净托管账面量（领料减退料），不代表工厂当前实盘余料。历史日志未知价格 / 单位显示未知，不按零计价。" type="info" :closable="false" />
    <PageHeader>
      <template #title>
        <el-segmented :model-value="viewMode" :options="VIEW_OPTIONS" @change="switchView" />
      </template>

      <template #actions>
        <el-button v-if="user.hasPerm('purchase:order:list')" @click="router.push('/reorder')">
          补货提醒{{ reorderCount ? `（${reorderCount}）` : '' }}
        </el-button>
        <el-button v-perm="'inventory:count:add'" @click="router.push('/stock-takes?new=DAILY')">盘点</el-button>
        <el-button v-perm="'inventory:count:add'" @click="router.push('/stock-takes?new=OPENING')">导入期初库存</el-button>
        <el-button v-if="viewMode === 'logs'" v-perm="'inventory:stock:export'" @click="exportDialog?.open()">导出任务</el-button>
        <el-button v-perm="'inventory:stock:export'" icon="Download" :loading="exporting" @click="doExport">导出</el-button>
      </template>
    </PageHeader>

    <!-- 1. 明细视图 -->
    <template v-if="viewMode === 'detail'">
      <FilterBar v-model:keyword="detailTable.query.keyword" placeholder="SKU 码、物料编码、名称、仓库" :columns="detailCols" @search="detailTable.search">
        <template #left>
          <el-segmented v-model="detailTable.query.whType" :options="WH_TYPE_OPTIONS" @change="onWhType(detailTable.query, detailTable.search)" />
        </template>
        <FilterItem label="仓库" :text="warehouseName(detailTable.query.warehouseId)" @clear="detailTable.query.warehouseId = undefined">
          <el-select v-model="detailTable.query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
            <el-option v-for="w in whOptionsOf(detailTable.query.whType)" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
        <template #right>
          <el-checkbox v-model="detailTable.query.includeZero" @change="detailTable.search">含 0 库存</el-checkbox>
        </template>
      </FilterBar>

      <el-table
        v-loading="detailTable.loading.value"
        :data="detailTable.list.value"
        :row-key="stockRowKey"
        :row-class-name="detailRowClass"
        scrollbar-always-on
        :empty-text="detailTable.query.keyword || detailTable.query.warehouseId ? '没有符合条件的库存：换个仓库，或者清空筛选' : '这类仓库里还没有库存、也没有在途：采购入库、录期初库存后就有了'"
        @row-click="openCellLogs"
      >
        <el-table-column label="仓库" width="190" fixed="left">
          <template #default="{ row }">
            {{ row.warehouseName }}
            <span class="dim small">({{ WAREHOUSE_TYPE_LABEL[(row as StockRow).warehouseType] }})</span>
          </template>
        </el-table-column>
        <el-table-column label="物料" min-width="240" fixed="left">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span>
            <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template><template v-if="row.spec"> · {{ row.spec }}</template></div>
          </template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.warehouseCode" label="仓库编码" width="110" class-name="code" prop="warehouseCode" />
        <el-table-column v-if="detailCols.show.materialType" label="物料类型" width="96">
          <template #default="{ row }">{{ materialTypes.labelOf(row.materialType) }}</template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.qty" label="数量" width="130" class-name="num" align="right">
          <template #default="{ row }">
            <strong :class="{ warn: isBelowSafety(row as StockRow) }">
              {{ formatQty(row.qty) }} {{ units.labelOf(row.stockUnit) }}
            </strong>
          </template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.transit" label="在途" width="100" class-name="num" align="right">
          <template #default="{ row }">{{ transitText(row as StockRow) }}</template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.safety" label="最小库存" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ row.safetyStock == null ? '—' : formatQty(row.safetyStock) }}</template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.updated" label="最近变动" width="160" class-name="num">
          <template #default="{ row }">{{ formatDateTime(row.updateTime) || '—' }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="detailTable.page.value"
        v-model:page-size="detailTable.size.value"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="detailTable.pageSizes"
        :total="detailTable.total.value"
      />
    </template>

    <!-- 2. 按物料汇总视图 -->
    <template v-else-if="viewMode === 'summary'">
      <FilterBar v-model:keyword="summaryTable.query.keyword" placeholder="SKU 码、物料编码、名称" :columns="summaryCols" @search="summaryTable.search">
        <template #right>
          <el-checkbox v-model="summaryTable.query.includeZero" @change="summaryTable.search">含 0 库存</el-checkbox>
        </template>
      </FilterBar>

      <el-table
        v-loading="summaryTable.loading.value"
        :data="summaryTable.list.value"
        row-key="materialSkuId"
        scrollbar-always-on
        :empty-text="summaryTable.query.keyword ? '没有符合条件的物料' : '还没有库存，也没有在途的料'"
      >
        <el-table-column label="物料" min-width="240" fixed="left">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span>
            <div class="dim small">{{ (row as StockSummaryRow).materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
          </template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.materialType" label="物料类型" width="96">
          <template #default="{ row }">{{ materialTypes.labelOf(row.materialType) }}</template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.material" label="物料仓合计" width="130" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.materialQty) }} {{ units.labelOf(row.stockUnit) }}</template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.factory" label="工厂净托管" width="130" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.factoryQty) }}</template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.total" label="合计" width="120" class-name="num" align="right">
          <template #default="{ row }"><strong>{{ formatQty(row.totalQty) }}</strong></template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.transit" label="在途" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ summaryTransitText(row as StockSummaryRow) }}</template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.safety" label="最小库存" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ row.safetyStock == null ? '—' : formatQty(row.safetyStock) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="summaryTable.page.value"
        v-model:page-size="summaryTable.size.value"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="summaryTable.pageSizes"
        :total="summaryTable.total.value"
      />
    </template>

    <!-- 3. 进销存视图 -->
    <template v-else-if="viewMode === 'movement'">
      <FilterBar v-model:keyword="movementTable.query.keyword" placeholder="SKU 码、物料编码、名称" :columns="movementCols" @search="movementTable.search">
        <template #left>
          <el-segmented v-model="movementTable.query.whType" :options="WH_TYPE_OPTIONS" @change="onWhType(movementTable.query, movementTable.search)" />
        </template>
        <FilterItem label="仓库" :text="warehouseName(movementTable.query.warehouseId)" @clear="movementTable.query.warehouseId = undefined">
          <el-select v-model="movementTable.query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
            <el-option v-for="w in whOptionsOf(movementTable.query.whType)" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
        <template #right>
          <DateRange :model-value="movementTable.query.period" :clearable="false" @update:model-value="onPeriod" />
        </template>
      </FilterBar>

      <el-table
        v-loading="movementTable.loading.value"
        :data="movementTable.list.value"
        row-key="materialSkuId"
        scrollbar-always-on
        :empty-text="movementTable.query.keyword || movementTable.query.warehouseId ? '没有符合条件的物料：换个仓库、期间，或者清空筛选' : '这段时间里没有进出，期初也都是 0'"
      >
        <el-table-column label="物料" min-width="240" fixed="left">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span>
            <div class="dim small">{{ (row as StockMovementRow).materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template><template v-if="row.spec"> · {{ row.spec }}</template></div>
          </template>
        </el-table-column>
        <el-table-column v-if="movementCols.show.materialType" label="物料类型" width="96">
          <template #default="{ row }">{{ materialTypes.labelOf(row.materialType) }}</template>
        </el-table-column>
        <el-table-column v-if="movementCols.show.unit" label="单位" width="70">
          <template #default="{ row }">{{ units.labelOf(row.stockUnit) }}</template>
        </el-table-column>
        <el-table-column v-if="movementCols.show.opening" label="期初" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.openingQty) }}</template>
        </el-table-column>
        <el-table-column v-if="movementCols.show.in" label="入库" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.inQty) }}</template>
        </el-table-column>
        <template v-for="p in IN_PARTS" :key="p.key">
          <el-table-column v-if="movementCols.show[p.key]" :label="p.label" width="100" class-name="num" align="right">
            <template #default="{ row }">{{ partText((row as StockMovementRow)[p.key] as number) }}</template>
          </el-table-column>
        </template>
        <el-table-column v-if="movementCols.show.out" label="出库" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.outQty) }}</template>
        </el-table-column>
        <template v-for="p in OUT_PARTS" :key="p.key">
          <el-table-column v-if="movementCols.show[p.key]" :label="p.label" width="100" class-name="num" align="right">
            <template #default="{ row }">{{ partText((row as StockMovementRow)[p.key] as number) }}</template>
          </el-table-column>
        </template>
        <el-table-column v-if="movementCols.show.closing" label="期末" width="134" class-name="num" align="right" fixed="right">
          <template #default="{ row }"><strong>{{ formatQty(row.closingQty) }}</strong></template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="movementTable.page.value"
        v-model:page-size="movementTable.size.value"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="movementTable.pageSizes"
        :total="movementTable.total.value"
      />
    </template>

    <!-- 4. 库存日志视图 -->
    <template v-else>
      <FilterBar v-model:keyword="logTable.query.keyword" placeholder="单号、SKU 码、物料名称、缸号" :columns="logCols" @search="logTable.search">
        <FilterItem label="仓库" :text="warehouseName(logTable.query.warehouseId)" @clear="logTable.query.warehouseId = undefined">
          <el-select v-model="logTable.query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
            <el-option v-for="w in filteredWhOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
        <FilterItem label="类型" :text="changeTypeText" @clear="logTable.query.changeType = []">
          <el-select v-model="logTable.query.changeType" multiple filterable clearable placeholder="全部类型">
            <el-option v-for="o in CHANGE_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </FilterItem>
        <FilterItem label="日期" :text="formatDateRange(logDateRange)" @clear="onLogDate(null)">
          <DateRange :model-value="logDateRange" @update:model-value="onLogDate" />
        </FilterItem>
      </FilterBar>

      <p v-if="logTable.error.value" class="page" role="alert">{{ logTable.error.value }} <el-button @click="logTable.load">重试</el-button></p>
      <VirtualDataTable v-loading="logTable.loading.value" :rows="logTable.list.value" :columns="logVirtualColumns" empty-text="没有符合条件的库存变动">
        <template #bizDate="{ row }">{{ formatDate(row.bizDate) }}</template>
        <template #docNo="{ row }"><button type="button" class="link-btn code" @click="goLogDoc(row)">{{ row.docNo }}</button></template>
        <!-- 反审核冲销的标在类型上：没有看价权限的（仓管）也看得到，原来只在单价 / 金额列里（走查 T-33e438） -->
        <template #changeType="{ row }">{{ STOCK_CHANGE_TYPE_LABEL[row.changeType] }}<span v-if="row.reversalOfLogId" class="dim"> · 冲销</span></template>
        <template #material="{ row }"><span class="code">{{ row.skuCode }}</span> {{ row.materialName }}</template>
        <template #changeQty="{ row }"><span :class="row.changeQty >= 0 ? 'gain' : 'loss'">{{ row.changeQty >= 0 ? '+' : '' }}{{ formatQty(row.changeQty) }} {{ row.stockUnit == null ? '单位未知' : units.labelOf(row.stockUnit) }}</span></template>
        <template #balanceQty="{ row }">{{ formatQty(row.balanceQty) }}</template>
        <template #valuation="{ row }"><span v-if="row.valuationStatus === 'UNKNOWN'" class="dim">估值未知</span><span v-else>{{ row.unitPrice == null ? '—' : formatPrice(row.unitPrice) }} / {{ row.amount == null ? '—' : formatMoney(row.amount) }} {{ row.currency || '' }}</span></template>
        <template #createTime="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </VirtualDataTable>
      <div class="pagination seek-pagination">
        <el-button :disabled="!logTable.canPrevious.value || logTable.loading.value" @click="logTable.previous">上一页</el-button>
        <span>第 {{ logTable.page.value }} 页</span>
        <el-button :disabled="!logTable.hasMore.value || logTable.loading.value" @click="logTable.next">下一页</el-button>
      </div>
    </template>

    <StockLogExportDialog v-if="user.hasPerm('inventory:stock:export')" ref="exportDialog" />

    <!-- 单格库存流水浮窗 -->
    <!-- 日期、操作人放得下（走查 T-33e438：原来日期截成"2026-1…"、操作人和时间挤一格） -->
    <el-dialog v-model="cellLogVisible" :title="cellLogTitle" width="min(1040px, 94vw)">
      <el-table v-loading="cellLogLoading" :data="cellLogRows" row-key="id" max-height="420" scrollbar-always-on empty-text="这个仓里这种料还没有进出">
        <el-table-column label="日期" width="116" class-name="num">
          <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="132">
          <template #default="{ row }">{{ STOCK_CHANGE_TYPE_LABEL[(row as StockLogRow).changeType] }}<span v-if="row.reversalOfLogId" class="dim"> · 冲销</span></template>
        </el-table-column>
        <el-table-column label="单号" width="150">
          <template #default="{ row }">
            <button type="button" class="link-btn code" @click="goLogDoc(row as StockLogRow)">{{ row.docNo }}</button>
          </template>
        </el-table-column>
        <el-table-column label="变动量" width="120" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="row.changeQty >= 0 ? 'gain' : 'loss'">
              {{ row.changeQty >= 0 ? '+' : '' }}{{ formatQty(row.changeQty) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="结存" width="100" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.balanceQty) }}</template>
        </el-table-column>
        <el-table-column label="缸号" min-width="110">
          <template #default="{ row }">{{ row.dyeLot || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">{{ row.operatorName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="记账时间" width="184" class-name="num">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.seek-pagination { display: flex; align-items: center; gap: 12px; }
.small {
  font-size: 11.5px;
}

.warn {
  color: var(--lm-pending-text);
}

.gain {
  font-weight: 600;
  color: var(--lm-done-text);
}

.loss {
  font-weight: 600;
  color: var(--lm-danger-text);
}

.link-btn {
  padding: 0;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.link-btn:hover {
  text-decoration: underline;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}

:deep(.el-table__row.warn-row) {
  background: var(--lm-pending-bg);
}
</style>
