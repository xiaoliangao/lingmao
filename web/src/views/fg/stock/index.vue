<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { warehouseApi } from '@/api/basic'
import { fgStockApi, fgStockLogApi } from '@/api/fg'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useColumns } from '@/composables/useColumns'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTable } from '@/composables/useTable'
import type { WarehouseOption } from '@/types/basic'
import { FG_DOC_TYPE, type FgDocType, type FgStockByStyleRow, type FgStockLogRow, type FgStockRow } from '@/types/fg'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateRange, formatDateTime, formatQty } from '@/utils/format'
import { fgCells, fgColors, fgSizes } from '../doc/fgMatrix'

/**
 * 成品库存（/fg-stock，docs/api/07 §1.3、§3.6）：明细 / 按款 / 日志三个视图。
 * - 明细默认不列 0 的；点一行弹这个仓这个 SKU 的日志
 * - 按款：几个仓加在一起（筛了仓就只算这个仓），点一行弹只读的颜色 × 尺码矩阵
 * - 日志：新的在前，只能翻到前 1 万行（再往前缩小日期），点单号开单据
 */
type View = 'detail' | 'style' | 'logs'
const VIEW_OPTIONS: { label: string; value: View }[] = [
  { label: '明细', value: 'detail' },
  { label: '按款', value: 'style' },
  { label: '日志', value: 'logs' },
]
const CHANGE_TYPES = Object.keys(FG_DOC_TYPE) as FgDocType[]

const router = useRouter()

const detailCols = useColumns('fg-stock', [
  { key: 'color', label: '颜色' },
  { key: 'size', label: '尺码' },
  { key: 'sku', label: 'SKU 码' },
  { key: 'qty', label: '数量' },
  { key: 'updated', label: '最近变动' },
])
const logCols = useColumns('fg-stock-log', [
  { key: 'type', label: '类型' },
  { key: 'warehouse', label: '仓库' },
  { key: 'sku', label: 'SKU' },
  { key: 'change', label: '变动' },
  { key: 'balance', label: '结存' },
  { key: 'operator', label: '操作人' },
])

const viewMode = ref<View>('detail')
const warehouses = ref<WarehouseOption[]>([])
const warehouseName = (id: string | null | undefined) => (id ? (warehouses.value.find((w) => w.id === id)?.name ?? '已选') : '')

// ---------- 明细 ----------
const detailTable = useTable({
  fetch: fgStockApi.page,
  exportUrl: '/api/fg-stocks/export',
  exportName: '成品库存.xlsx',
  query: { keyword: '', warehouseId: undefined as string | undefined, includeZero: false },
})

// ---------- 按款 ----------
const styleTable = useTable({
  immediate: false,
  fetch: fgStockApi.byStyle,
  exportUrl: '/api/fg-stocks/by-style/export',
  exportName: '成品库存按款.xlsx',
  query: { keyword: '', warehouseId: undefined as string | undefined },
})
const styleViewing = ref<FgStockByStyleRow | null>(null)
const styleVisible = ref(false)
const styleCells = computed(() => (styleViewing.value ? fgCells(styleViewing.value, (id) => (styleViewing.value?.qtys[id] ?? 0) > 0) : []))

function openStyle(row: FgStockByStyleRow) {
  styleViewing.value = row
  styleVisible.value = true
}

// ---------- 日志 ----------
const logDateRange = ref<[string, string] | null>(null)
const logTable = useTable({
  immediate: false,
  fetch: fgStockLogApi.page,
  exportUrl: '/api/fg-stock-logs/export',
  exportName: '成品库存日志.xlsx',
  query: {
    keyword: '',
    warehouseId: undefined as string | undefined,
    changeType: [] as FgDocType[],
    bizDateFrom: undefined as string | undefined,
    bizDateTo: undefined as string | undefined,
  },
})
const changeTypeText = computed(() => logTable.query.changeType.map((t) => FG_DOC_TYPE[t].label).join('、'))

function onLogDate(val: [string, string] | null | undefined) {
  logDateRange.value = val ?? null
  logTable.query.bizDateFrom = val?.[0]
  logTable.query.bizDateTo = val?.[1]
}

function switchView(v: View) {
  viewMode.value = v
  if (v === 'detail') detailTable.search()
  else if (v === 'style') styleTable.search()
  else logTable.search()
}

function doExport() {
  if (viewMode.value === 'detail') void detailTable.exportFile()
  else if (viewMode.value === 'style') void styleTable.exportFile()
  else void logTable.exportFile()
}
const exporting = computed(() =>
  viewMode.value === 'detail' ? detailTable.exporting.value : viewMode.value === 'style' ? styleTable.exporting.value : logTable.exporting.value,
)

onMounted(async () => {
  warehouses.value = await warehouseApi.options('PRODUCT')
})
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (viewMode.value === 'detail') void detailTable.load()
  else if (viewMode.value === 'style') void styleTable.load()
  else void logTable.load()
})

function goDoc(row: FgStockLogRow) {
  const to = docRoute({ type: 'FG_DOC', kind: row.docKind, id: row.docId, parentId: null })
  if (to) {
    cellLogVisible.value = false
    void router.push(to)
  }
}

// ---------- 一个仓一个 SKU 的日志 ----------
const cellLogVisible = ref(false)
const cellLogTitle = ref('')
const cellLogRows = ref<FgStockLogRow[]>([])
const cellLogLoading = ref(false)
const cellRequest = useLatestRequest()

async function openCellLogs(row: FgStockRow) {
  const read = cellRequest.begin()
  cellLogTitle.value = `${row.warehouseName} · ${row.skuCode} 库存日志`
  cellLogVisible.value = true
  cellLogLoading.value = true
  try {
    const res = await read.run(() => fgStockLogApi.page({ page: 1, size: 100, warehouseId: row.warehouseId, styleSkuId: row.styleSkuId }))
    if (read.current()) cellLogRows.value = res.list
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) cellLogLoading.value = false
  }
}
watch(cellLogVisible, (on) => {
  if (!on) cellRequest.cancel()
})
</script>

<template>
  <div>
    <PageHeader>
      <template #title>
        <el-segmented :model-value="viewMode" :options="VIEW_OPTIONS" @change="switchView" />
      </template>
      <template #actions>
        <el-button v-perm="'fg:stock:export'" icon="Download" :loading="exporting" @click="doExport">导出</el-button>
      </template>
    </PageHeader>

    <!-- 1. 明细 -->
    <template v-if="viewMode === 'detail'">
      <FilterBar v-model:keyword="detailTable.query.keyword" placeholder="款号、款名、SKU 码" :columns="detailCols" @search="detailTable.search">
        <FilterItem label="仓库" :text="warehouseName(detailTable.query.warehouseId)" @clear="detailTable.query.warehouseId = undefined">
          <el-select v-model="detailTable.query.warehouseId" filterable clearable placeholder="全部成品仓" :value-on-clear="undefined">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
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
        row-class-name="clickable"
        scrollbar-always-on
        :empty-text="detailTable.query.keyword || detailTable.query.warehouseId ? '没有符合条件的库存：换个仓库，或者清空筛选' : '成品仓还没有货：回货入库、录期初（其他入库）后就有了'"
        @row-click="openCellLogs"
      >
        <el-table-column label="仓库" width="150" prop="warehouseName" fixed="left" />
        <el-table-column label="款式" min-width="220" fixed="left">
          <template #default="{ row }">
            <div class="style-cell">
              <ThumbImage v-if="row.image" :src="row.image.thumbUrl" alt="" class="thumb" loading="lazy" />
              <span class="code">{{ row.styleCode }}</span>
              <span class="dim">{{ row.styleName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.color" label="颜色" width="100" prop="colorName" />
        <el-table-column v-if="detailCols.show.size" label="尺码" width="72" prop="sizeCode" />
        <el-table-column v-if="detailCols.show.sku" label="SKU 码" width="170" class-name="code" prop="skuCode" />
        <el-table-column v-if="detailCols.show.qty" label="数量" width="96" class-name="num" align="right">
          <template #default="{ row }"><strong>{{ formatQty(row.qty) }}</strong></template>
        </el-table-column>
        <el-table-column v-if="detailCols.show.updated" label="最近变动" width="184" class-name="num">
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

    <!-- 2. 按款 -->
    <template v-else-if="viewMode === 'style'">
      <FilterBar v-model:keyword="styleTable.query.keyword" placeholder="款号、款名" @search="styleTable.search">
        <FilterItem label="仓库" :text="warehouseName(styleTable.query.warehouseId)" @clear="styleTable.query.warehouseId = undefined">
          <el-select v-model="styleTable.query.warehouseId" filterable clearable placeholder="全部成品仓" :value-on-clear="undefined">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
      </FilterBar>

      <el-table
        v-loading="styleTable.loading.value"
        :data="styleTable.list.value"
        row-key="styleId"
        row-class-name="clickable"
        scrollbar-always-on
        :empty-text="styleTable.query.keyword || styleTable.query.warehouseId ? '没有符合条件的款' : '成品仓还没有货'"
        @row-click="openStyle"
      >
        <el-table-column label="款号" width="190">
          <template #default="{ row }">
            <div class="style-cell">
              <ThumbImage v-if="row.image" :src="row.image.thumbUrl" alt="" class="thumb" loading="lazy" />
              <span class="code">{{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="款名" min-width="180" prop="name" />
        <el-table-column label="合计" width="110" class-name="num" align="right">
          <template #default="{ row }"><strong>{{ formatQty(row.totalQty) }}</strong></template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="styleTable.page.value"
        v-model:page-size="styleTable.size.value"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="styleTable.pageSizes"
        :total="styleTable.total.value"
      />
    </template>

    <!-- 3. 日志 -->
    <template v-else>
      <FilterBar v-model:keyword="logTable.query.keyword" placeholder="单号、款号、SKU 码" :columns="logCols" @search="logTable.search">
        <FilterItem label="仓库" :text="warehouseName(logTable.query.warehouseId)" @clear="logTable.query.warehouseId = undefined">
          <el-select v-model="logTable.query.warehouseId" filterable clearable placeholder="全部成品仓" :value-on-clear="undefined">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </FilterItem>
        <FilterItem label="类型" :text="changeTypeText" @clear="logTable.query.changeType = []">
          <el-select v-model="logTable.query.changeType" multiple filterable clearable placeholder="全部类型">
            <el-option v-for="t in CHANGE_TYPES" :key="t" :label="FG_DOC_TYPE[t].label" :value="t" />
          </el-select>
        </FilterItem>
        <FilterItem label="日期" :text="formatDateRange(logDateRange)" @clear="onLogDate(null)">
          <DateRange :model-value="logDateRange" @update:model-value="onLogDate" />
        </FilterItem>
      </FilterBar>

      <el-table v-loading="logTable.loading.value" :data="logTable.list.value" row-key="id" scrollbar-always-on empty-text="没有符合条件的库存变动">
        <el-table-column label="日期" width="128" class-name="num" fixed="left">
          <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="单号" width="150" fixed="left">
          <template #default="{ row }">
            <button type="button" class="link-btn code" @click="goDoc(row as FgStockLogRow)">{{ row.docNo }}</button>
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.type" label="类型" width="96">
          <template #default="{ row }">{{ FG_DOC_TYPE[(row as FgStockLogRow).changeType].label }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.warehouse" label="仓库" width="130" prop="warehouseName" />
        <el-table-column v-if="logCols.show.sku" label="SKU" min-width="220">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span> <span class="dim">{{ row.styleName }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.change" label="变动" width="90" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="row.changeQty >= 0 ? 'gain' : 'loss'">{{ row.changeQty >= 0 ? '+' : '' }}{{ formatQty(row.changeQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="logCols.show.balance" label="结存" width="90" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.balanceQty) }}</template>
        </el-table-column>
        <el-table-column v-if="logCols.show.operator" label="操作人" width="204" fixed="right">
          <template #default="{ row }">{{ row.createByName ?? '' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span></template>
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

    <el-dialog v-model="styleVisible" :title="styleViewing ? `${styleViewing.code} ${styleViewing.name} · ${formatQty(styleViewing.totalQty)} 件` : ''" width="760px">
      <SkuMatrix
        v-if="styleViewing"
        :model-value="styleViewing.qtys"
        :sizes="fgSizes(styleViewing, styleCells)"
        :colors="fgColors(styleViewing, styleCells)"
        :skus="styleCells"
        readonly
      />
    </el-dialog>

    <el-dialog v-model="cellLogVisible" :title="cellLogTitle" width="860px">
      <el-table v-loading="cellLogLoading" :data="cellLogRows" row-key="id" max-height="420" empty-text="这个仓里这个 SKU 还没有进出">
        <el-table-column label="日期" width="128" class-name="num">
          <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="96">
          <template #default="{ row }">{{ FG_DOC_TYPE[(row as FgStockLogRow).changeType].label }}</template>
        </el-table-column>
        <el-table-column label="单号" width="150">
          <template #default="{ row }">
            <button type="button" class="link-btn code" @click="goDoc(row as FgStockLogRow)">{{ row.docNo }}</button>
          </template>
        </el-table-column>
        <el-table-column label="变动" width="90" class-name="num" align="right">
          <template #default="{ row }">
            <span :class="row.changeQty >= 0 ? 'gain' : 'loss'">{{ row.changeQty >= 0 ? '+' : '' }}{{ formatQty(row.changeQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结存" width="90" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.balanceQty) }}</template>
        </el-table-column>
        <el-table-column label="操作人" min-width="220">
          <template #default="{ row }">{{ row.createByName ?? '' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span></template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.style-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.style-cell .dim {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thumb {
  flex: none;
  width: 24px;
  height: 24px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.small {
  font-size: 11.5px;
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
  font: inherit;
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
</style>
