<script setup lang="ts">
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
  type StockRow,
  type StockSummaryRow,
} from '@/types/inventory'
import type { SkuSupply } from '@/types/purchase'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateRange, formatDateTime, formatQty } from '@/utils/format'

/**
 * 库存（/stock，docs/api/04 §1.5、§3.6、§4）：明细 / 按物料 / 日志三个视图，认 ?view=log（只认 /stock 自己的地址）。
 * - 明细的在途、低于最小库存（标黄）要等 supplyApi.bySkus 回来：和主表分开取，没回来、取不到都写"—"，不拖住主表
 * - 页头：补货提醒（n）、盘点（去新建日常盘点）、导入期初库存、导出
 * - 点明细的一行：弹它的库存日志（带上仓库和 SKU）
 */
type View = 'detail' | 'summary' | 'logs'
const VIEW_OPTIONS: { label: string; value: View }[] = [
  { label: '明细', value: 'detail' },
  { label: '按物料', value: 'summary' },
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
  { key: 'factory', label: '工厂仓合计' },
  { key: 'total', label: '合计' },
  { key: 'safety', label: '最小库存' },
  { key: 'materialType', label: '物料类型', hidden: true },
])
const logCols = useColumns('stock-log', [
  { key: 'type', label: '类型' },
  { key: 'warehouse', label: '仓库' },
  { key: 'material', label: '物料' },
  { key: 'changeQty', label: '变动数量' },
  { key: 'balance', label: '结存' },
  { key: 'dyeLot', label: '缸号' },
  { key: 'operator', label: '操作人' },
  { key: 'createTime', label: '记账时间', hidden: true },
])

const viewFromQuery = (): View => (route.query.view === 'log' ? 'logs' : 'detail')
const viewMode = ref<View>(viewFromQuery())
const warehouses = ref<WarehouseOption[]>([])
/** 在途、低于最小库存：null = 还没回来或取不到，那两列写"—" */
const supplyMap = ref<Record<number, SkuSupply> | null>(null)
const reorderCount = ref(0)

// ---------- 视图 1：库存明细 ----------
const detailTable = useTable({
  immediate: viewMode.value === 'detail',
  fetch: ({ whType, ...rest }) => stockApi.page({ ...rest, warehouseType: whType || undefined }),
  query: {
    keyword: '',
    whType: 'MATERIAL' as '' | 'MATERIAL' | 'FACTORY',
    warehouseId: undefined as number | undefined,
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
  query: {
    keyword: '',
    includeZero: false,
  },
})

// ---------- 视图 3：库存日志 ----------
const logDateRange = ref<[string, string] | null>(null)
const logTable = useTable({
  immediate: false,
  fetch: (params) => stockLogApi.page(params),
  query: {
    keyword: '',
    warehouseId: undefined as number | undefined,
    changeType: [] as StockChangeType[],
    bizDateFrom: undefined as string | undefined,
    bizDateTo: undefined as string | undefined,
  },
})

function onLogDate(val: [string, string] | null | undefined) {
  logDateRange.value = val ?? null
  logTable.query.bizDateFrom = val?.[0]
  logTable.query.bizDateTo = val?.[1]
}

function switchView(v: View) {
  viewMode.value = v
  if (v === 'detail') detailTable.search()
  else if (v === 'summary') summaryTable.search()
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

const warehouseName = (id: number | undefined) => (id ? (warehouses.value.find((w) => w.id === id)?.name ?? '已选') : '')
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
  else void logTable.load()
})

const filteredWhOptions = computed(() =>
  warehouses.value.filter((w) => w.type !== 'PRODUCT'),
)

// ---------- 单仓单 SKU 流水浮窗 ----------
const cellLogVisible = ref(false)
const cellLogTitle = ref('')
const cellLogRows = ref<StockLogRow[]>([])
const cellLogLoading = ref(false)

async function openCellLogs(row: StockRow) {
  cellLogTitle.value = `${row.warehouseName} · ${row.skuCode} 库存日志`
  cellLogVisible.value = true
  cellLogLoading.value = true
  try {
    const res = await stockLogApi.page({
      page: 1,
      size: 100,
      warehouseId: row.warehouseId,
      materialSkuId: row.materialSkuId,
    })
    cellLogRows.value = res.list
  } finally {
    cellLogLoading.value = false
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
  if (viewMode.value === 'detail') {
    void stockApi.export({
      keyword: detailTable.query.keyword || undefined,
      warehouseType: detailTable.query.whType || undefined,
      warehouseId: detailTable.query.warehouseId,
      includeZero: detailTable.query.includeZero || undefined,
    })
  } else if (viewMode.value === 'summary') {
    void stockApi.exportSummary({
      keyword: summaryTable.query.keyword || undefined,
      includeZero: summaryTable.query.includeZero || undefined,
    })
  } else {
    void stockLogApi.export({
      keyword: logTable.query.keyword || undefined,
      warehouseId: logTable.query.warehouseId,
      changeType: logTable.query.changeType.length ? logTable.query.changeType : undefined,
      bizDateFrom: logTable.query.bizDateFrom,
      bizDateTo: logTable.query.bizDateTo,
    })
  }
}
</script>

<template>
  <div>
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
        <el-button v-perm="'inventory:stock:export'" icon="Download" @click="doExport">导出</el-button>
      </template>
    </PageHeader>

    <!-- 1. 明细视图 -->
    <template v-if="viewMode === 'detail'">
      <FilterBar v-model:keyword="detailTable.query.keyword" placeholder="SKU 码、物料编码、名称、仓库" :columns="detailCols" @search="detailTable.search">
        <template #left>
          <el-segmented v-model="detailTable.query.whType" :options="WH_TYPE_OPTIONS" @change="detailTable.search" />
        </template>
        <FilterItem label="仓库" :text="warehouseName(detailTable.query.warehouseId)" @clear="detailTable.query.warehouseId = undefined">
          <el-select v-model="detailTable.query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
            <el-option v-for="w in filteredWhOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
        <template #right>
          <el-checkbox v-model="detailTable.query.includeZero" @change="detailTable.search">含 0 库存</el-checkbox>
        </template>
      </FilterBar>

      <el-table
        v-loading="detailTable.loading.value"
        :data="detailTable.list.value"
        row-key="id"
        :row-class-name="detailRowClass"
        scrollbar-always-on
        :empty-text="detailTable.query.keyword || detailTable.query.warehouseId ? '没有符合条件的库存：换个仓库，或者清空筛选' : '这类仓库里还没有库存：采购入库、录期初库存后就有了'"
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
          <template #default="{ row }">{{ formatDateTime(row.updateTime) }}</template>
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
        :empty-text="summaryTable.query.keyword ? '没有符合条件的物料' : '还没有库存'"
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
        <el-table-column v-if="summaryCols.show.factory" label="工厂仓合计" width="130" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.factoryQty) }}</template>
        </el-table-column>
        <el-table-column v-if="summaryCols.show.total" label="合计" width="120" class-name="num" align="right">
          <template #default="{ row }"><strong>{{ formatQty(row.totalQty) }}</strong></template>
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

    <!-- 3. 库存日志视图 -->
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

      <el-table v-loading="logTable.loading.value" :data="logTable.list.value" row-key="id" scrollbar-always-on empty-text="没有符合条件的库存变动">
        <el-table-column label="日期" width="116" fixed="left" class-name="num">
          <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="单号" width="150" fixed="left">
          <template #default="{ row }">
            <button type="button" class="link-btn code" @click="goLogDoc(row as StockLogRow)">{{ row.docNo }}</button>
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.type" label="类型" width="100">
          <template #default="{ row }">{{ STOCK_CHANGE_TYPE_LABEL[(row as StockLogRow).changeType] }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.warehouse" label="仓库" prop="warehouseName" width="130" />
        <el-table-column v-if="logCols.show.material" label="物料" min-width="220">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span> {{ row.materialName }}
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.changeQty" label="变动数量" width="120" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="row.changeQty >= 0 ? 'gain' : 'loss'">
              {{ row.changeQty >= 0 ? '+' : '' }}{{ formatQty(row.changeQty) }} {{ units.labelOf(row.stockUnit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.balance" label="结存" width="100" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.balanceQty) }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.dyeLot" label="缸号" width="110">
          <template #default="{ row }">{{ row.dyeLot || '—' }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.operator" label="操作人" width="100">
          <template #default="{ row }">{{ row.operatorName ?? '—' }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.createTime" label="记账时间" width="160" class-name="num">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="logTable.page.value"
        v-model:page-size="logTable.size.value"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="logTable.pageSizes"
        :total="logTable.total.value"
      />
    </template>

    <!-- 单格库存流水浮窗 -->
    <el-dialog v-model="cellLogVisible" :title="cellLogTitle" width="860px">
      <el-table v-loading="cellLogLoading" :data="cellLogRows" row-key="id" max-height="420" empty-text="这个仓里这种料还没有进出">
        <el-table-column label="日期" width="100" class-name="num">
          <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="96">
          <template #default="{ row }">{{ STOCK_CHANGE_TYPE_LABEL[(row as StockLogRow).changeType] }}</template>
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
        <el-table-column label="操作人" min-width="175" class-name="num">
          <template #default="{ row }">{{ row.operatorName ?? '' }} {{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
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
