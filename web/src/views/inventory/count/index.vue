<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { warehouseApi } from '@/api/basic'
import { countApi } from '@/api/inventory'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useTable } from '@/composables/useTable'
import type { WarehouseOption } from '@/types/basic'
import {
  COUNT_PURPOSE_LABEL,
  COUNT_STATUS,
  type CountPurpose,
  type CountRow,
  type CountStatus,
} from '@/types/inventory'
import { formatDate, formatDateRange } from '@/utils/format'
import CountCreateDialog from './CountCreateDialog.vue'

/**
 * 物料盘点单列表（/stock-takes，docs/api/04 §1.5、§4）。只认本页地址上的参数：
 * ?status=PENDING（首页待办）；?new=DAILY|OPENING（库存页"盘点""导入期初库存"）直接开新建对话框、用途选好，开了就从地址上去掉
 */
const route = useRoute()
const router = useRouter()

const STATUSES: CountStatus[] = ['DRAFT', 'PENDING', 'APPROVED']
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...STATUSES.map((st) => ({ label: COUNT_STATUS[st].label, value: st }))]
const PURPOSES: CountPurpose[] = ['DAILY', 'OPENING']
const pickStatus = (value: unknown): CountStatus | '' => (STATUSES.includes(value as CountStatus) ? (value as CountStatus) : '')
const pickPurpose = (value: unknown): CountPurpose | null => (PURPOSES.includes(value as CountPurpose) ? (value as CountPurpose) : null)

const warehouses = ref<WarehouseOption[]>([])
const dateRange = ref<[string, string] | null>(null)
const createVisible = ref(false)
const createPurpose = ref<CountPurpose>('DAILY')

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  fetch: ({ statusTab, ...rest }) =>
    countApi.page({ ...rest, status: statusTab ? [statusTab] : undefined }),
  query: {
    keyword: '',
    statusTab: pickStatus(route.query.status),
    warehouseId: undefined as number | undefined,
    purpose: undefined as CountPurpose | undefined,
    countDateFrom: undefined as string | undefined,
    countDateTo: undefined as string | undefined,
  },
  exportUrl: '/api/inventory-counts/export',
  exportName: '物料盘点单.xlsx',
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== '/stock-takes') return
    const st = pickStatus(value)
    if (st !== query.statusTab) {
      query.statusTab = st
      search()
    }
  },
)

/** ?new=：开新建对话框，再把参数从地址上去掉（下次从库存页点"盘点"还能再开） */
function openFromQuery() {
  if (route.path !== '/stock-takes') return
  const purpose = pickPurpose(route.query.new)
  if (!purpose) return
  openCreate(purpose)
  const rest = { ...route.query }
  delete rest.new
  void router.replace({ path: route.path, query: rest })
}

watch(() => route.query.new, openFromQuery)

onMounted(async () => {
  openFromQuery()
  warehouses.value = await warehouseApi.options()
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.countDateFrom = val?.[0]
  query.countDateTo = val?.[1]
}

const whText = computed(() =>
  query.warehouseId ? (warehouses.value.find((w) => w.id === query.warehouseId)?.name ?? '已选') : '',
)
/** 盘点只盘物料仓、工厂仓 */
const countWarehouses = computed(() => warehouses.value.filter((w) => w.type !== 'PRODUCT'))
const filtered = computed(() => !!(query.keyword.trim() || query.statusTab || query.warehouseId || query.purpose || query.countDateFrom))
const emptyText = computed(() =>
  filtered.value ? '没有符合条件的盘点单：换个状态，或者清空筛选' : '还没有盘点单：点「新建盘点单」，刚上线先「录期初库存」',
)

function openRow(row: CountRow) {
  void router.push(`/stock-takes/${row.id}`)
}

function openCreated(newId: number) {
  void router.push(`/stock-takes/${newId}`)
}

function openCreate(purpose: CountPurpose) {
  createPurpose.value = purpose
  createVisible.value = true
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'inventory:count:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'inventory:count:add'" @click="openCreate('OPENING')">录期初库存</el-button>
        <el-button v-perm="'inventory:count:add'" type="primary" icon="Plus" @click="openCreate('DAILY')">新建盘点单</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="盘点单号、仓库、盘点人、物料编码/名称" @search="search">
      <template #left>
        <el-segmented v-model="query.statusTab" :options="STATUS_OPTIONS" @change="search" />
      </template>
      <FilterItem label="仓库" :text="whText" @clear="query.warehouseId = undefined">
        <el-select v-model="query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
          <el-option v-for="w in countWarehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </FilterItem>
      <FilterItem label="用途" :text="query.purpose ? COUNT_PURPOSE_LABEL[query.purpose] : ''" @clear="query.purpose = undefined">
        <el-select v-model="query.purpose" filterable clearable placeholder="全部用途" :value-on-clear="undefined">
          <el-option v-for="p in PURPOSES" :key="p" :label="COUNT_PURPOSE_LABEL[p]" :value="p" />
        </el-select>
      </FilterItem>
      <FilterItem label="盘点日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
    </FilterBar>

    <el-table
      v-loading="loading"
      :data="list"
      row-key="id"
      row-class-name="clickable"
      :empty-text="emptyText"
      @row-click="openRow"
    >
      <el-table-column label="单号" prop="no" width="155" class-name="code" />
      <el-table-column label="仓库" prop="warehouseName" min-width="130" />
      <el-table-column label="用途" width="100">
        <template #default="{ row }">{{ COUNT_PURPOSE_LABEL[(row as CountRow).purpose] }}</template>
      </el-table-column>
      <el-table-column label="范围" prop="scopeText" min-width="180" show-overflow-tooltip />
      <el-table-column label="盘点日期" width="112" class-name="num">
        <template #default="{ row }">{{ formatDate(row.countDate) }}</template>
      </el-table-column>
      <el-table-column label="盘点人" prop="counterName" width="96" />
      <el-table-column label="已填实盘" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ row.filledCount }} / {{ row.itemCount }}</template>
      </el-table-column>
      <el-table-column label="差异" width="140">
        <template #default="{ row }">
          <template v-if="row.gainCount || row.lossCount">
            <span class="gain">盘盈 {{ row.gainCount }}</span> · <span class="loss">盘亏 {{ row.lossCount }}</span>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="94">
        <template #default="{ row }">
          <StatusTag :tone="COUNT_STATUS[(row as CountRow).status].tone">{{ COUNT_STATUS[(row as CountRow).status].label }}</StatusTag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />

    <CountCreateDialog
      v-model="createVisible"
      :default-purpose="createPurpose"
      @created="openCreated"
    />
  </div>
</template>

<style scoped>
.gain {
  color: var(--lm-done-text);
}

.loss {
  color: var(--lm-danger-text);
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
