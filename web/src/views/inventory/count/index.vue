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
import { useColumns } from '@/composables/useColumns'
import { useTable } from '@/composables/useTable'
import { statusQuery } from '@/utils/query'
import type { WarehouseOption } from '@/types/basic'
import {
  COUNT_PURPOSE_LABEL,
  COUNT_STATUS,
  type CountPurpose,
  type CountRow,
  type CountStatus,
} from '@/types/inventory'
import { formatDate, formatDateRange, formatDateTime } from '@/utils/format'
import CountCreateDialog from './CountCreateDialog.vue'

/**
 * 物料盘点单列表（/stock-takes，docs/api/04 §1.5、§4）。只认本页地址上的参数：
 * ?status=PENDING&auditForMe=1（首页待办；待我审核 = 不列自己建的，自己建的单不能自己审，Q10）；?new=DAILY|OPENING（库存页"盘点""导入期初库存"）直接开新建对话框、用途选好，开了就从地址上去掉
 */
const route = useRoute()
const router = useRouter()

// 单号、状态总在；建单时间默认不显示，在"显示列"里勾
const cols = useColumns('stock-takes', [
  { key: 'warehouse', label: '仓库' },
  { key: 'purpose', label: '用途' },
  { key: 'scope', label: '范围' },
  { key: 'countDate', label: '盘点日期' },
  { key: 'counter', label: '盘点人' },
  { key: 'filled', label: '已填实盘' },
  { key: 'diff', label: '差异' },
  { key: 'createTime', label: '建单时间', hidden: true },
])

const STATUSES: CountStatus[] = ['DRAFT', 'PENDING', 'APPROVED', 'VOIDED']
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...STATUSES.map((st) => ({ label: COUNT_STATUS[st].label, value: st }))]
const PURPOSES: CountPurpose[] = ['DAILY', 'OPENING']
const pickStatus = (value: unknown): CountStatus | '' => (STATUSES.includes(value as CountStatus) ? (value as CountStatus) : '')
const pickPurpose = (value: unknown): CountPurpose | null => (PURPOSES.includes(value as CountPurpose) ? (value as CountPurpose) : null)

const warehouses = ref<WarehouseOption[]>([])
const dateRange = ref<[string, string] | null>(null)
const createVisible = ref(false)
const createPurpose = ref<CountPurpose>('DAILY')

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  normalize: (query) => statusQuery(query),
  fetch: countApi.page,
  query: {
    keyword: '',
    statusTab: pickStatus(route.query.status),
    warehouseId: undefined as string | undefined,
    purpose: undefined as CountPurpose | undefined,
    countDateFrom: undefined as string | undefined,
    countDateTo: undefined as string | undefined,
    auditForMe: route.query.auditForMe === '1',
  },
  exportUrl: '/api/inventory-counts/export',
  exportName: '物料盘点单.xlsx',
})

watch(
  () => [route.query.status, route.query.auditForMe],
  ([value, auditForMe]) => {
    if (route.path !== '/stock-takes') return
    const st = pickStatus(value)
    const forMe = auditForMe === '1'
    if (st !== query.statusTab || forMe !== query.auditForMe) {
      query.statusTab = st
      query.auditForMe = forMe
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
const emptyText = computed(() => {
  if (query.auditForMe) return '没有等你审核的盘点单（自己建的要别人审）'
  return filtered.value ? '没有符合条件的盘点单：换个状态，或者清空筛选' : '还没有盘点单：点「新建盘点单」，刚上线先「录期初库存」'
})

function openRow(row: CountRow) {
  void router.push(`/stock-takes/${row.id}`)
}

function openCreated(newId: string) {
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

    <FilterBar v-model:keyword="query.keyword" placeholder="盘点单号、仓库、盘点人、物料编码/名称" :columns="cols" @search="search">
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
      <template #right>
        <el-checkbox v-perm="'inventory:count:audit'" v-model="query.auditForMe" @change="search">待我审核</el-checkbox>
      </template>
    </FilterBar>

    <el-table
      v-loading="loading"
      :data="list"
      row-key="id"
      row-class-name="clickable"
      scrollbar-always-on
      :empty-text="emptyText"
      @row-click="openRow"
    >
      <el-table-column label="单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.warehouse" label="仓库" prop="warehouseName" min-width="130" />
      <el-table-column v-if="cols.show.purpose" label="用途" width="96">
        <template #default="{ row }">{{ COUNT_PURPOSE_LABEL[(row as CountRow).purpose] }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.scope" label="范围" prop="scopeText" min-width="180" />
      <el-table-column v-if="cols.show.countDate" label="盘点日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.countDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.counter" label="盘点人" prop="counterName" width="96" />
      <el-table-column v-if="cols.show.filled" label="已填实盘" width="100" class-name="num" align="right">
        <template #default="{ row }">{{ row.filledCount }} / {{ row.itemCount }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.diff" label="差异" width="140">
        <template #default="{ row }">
          <template v-if="row.gainCount || row.lossCount">
            <span class="gain">盘盈 {{ row.gainCount }}</span> · <span class="loss">盘亏 {{ row.lossCount }}</span>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.createTime" label="建单时间" width="150" class-name="num">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="112" fixed="right">
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
