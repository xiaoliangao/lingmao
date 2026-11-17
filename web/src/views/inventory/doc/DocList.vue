<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { invDocApi } from '@/api/inventory'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import type { WarehouseOption } from '@/types/basic'
import { INV_DOC_STATUS, INV_DOC_TYPE, type InvDocKind, type InvDocQuery, type InvDocRow, type InvDocStatus, type InvDocType } from '@/types/inventory'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatQty } from '@/utils/format'

/**
 * 物料入库单、出库单列表（docs/api/04 §1.3、§1.5）：一张表按 kind 分，两个菜单页各放一个。
 *
 *   <DocList ref="listRef" kind="IN" />          页头"导出"调 listRef.value?.exportAll()，建了单调 listRef.value?.reload()
 *
 * - 分段控件按类型（全部 + 这种单的几种）；筛选：状态、日期、仓库（出仓或入仓是它）、往来单位；我的 / 全部
 * - 认地址上的 ?status=DRAFT（首页待办点进来），只认本页（/stock-in、/stock-out）自己的地址
 * - 勾选后批量审核（先确认"审核后马上改库存"，逐条做，失败的留着勾）、导出选中
 */
const props = defineProps<{ kind: InvDocKind }>()

const route = useRoute()
const router = useRouter()

// 单号、状态总在；数量、金额、原因、制单默认不显示，在"显示列"里勾。入库单、出库单各记各的
const cols = useColumns(props.kind === 'IN' ? 'stock-in' : 'stock-out', [
  { key: 'type', label: '类型' },
  { key: 'warehouses', label: '出仓 → 入仓' },
  { key: 'partner', label: '往来' },
  { key: 'source', label: '源单' },
  { key: 'material', label: '物料' },
  { key: 'date', label: '日期' },
  { key: 'qty', label: '数量', hidden: true },
  { key: 'amount', label: '金额', hidden: true },
  { key: 'reason', label: '原因', hidden: true },
  { key: 'created', label: '制单', hidden: true },
])
/** 原因：其他入库、其他出库存的是字典值，别的是手填的字 */
const reasons = useDict(props.kind === 'IN' ? 'stock_in_reason' : 'stock_out_reason')
const reasonText = (row: InvDocRow) => (row.reason && INV_DOC_TYPE[row.docType].reasonDict ? reasons.labelOf(row.reason) : (row.reason ?? '—'))

const basePath = computed(() => (props.kind === 'IN' ? '/stock-in' : '/stock-out'))
const docName = computed(() => (props.kind === 'IN' ? '入库单' : '出库单'))

const TYPES = (Object.keys(INV_DOC_TYPE) as InvDocType[]).filter((t) => INV_DOC_TYPE[t].kind === props.kind)
const TYPE_OPTIONS = [{ label: '全部', value: '' }, ...TYPES.map((t) => ({ label: INV_DOC_TYPE[t].label, value: t }))]
const STATUSES: InvDocStatus[] = ['DRAFT', 'APPROVED', 'VOIDED']
const pickStatus = (value: unknown): InvDocStatus | '' => (STATUSES.includes(value as InvDocStatus) ? (value as InvDocStatus) : '')

const warehouses = ref<WarehouseOption[]>([])
const dateRange = ref<[string, string] | null>(null)
const partnerName = ref<string | null>(null)
const tableRef = ref<TableInstance>()
const selected = ref<InvDocRow[]>([])

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ docTypeTab, statusTab, ...rest }) => invDocApi.page({ ...rest, kind: props.kind, docType: docTypeTab || undefined, status: statusTab ? [statusTab] : undefined }),
  query: {
    keyword: '',
    docTypeTab: '' as InvDocType | '',
    statusTab: pickStatus(route.query.status),
    warehouseId: undefined as number | undefined,
    partnerId: undefined as number | null | undefined,
    docDateFrom: undefined as string | undefined,
    docDateTo: undefined as string | undefined,
    mine: false,
  },
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== basePath.value) return
    const st = pickStatus(value)
    if (st !== query.statusTab) {
      query.statusTab = st
      search()
    }
  },
)

onMounted(async () => {
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
  query.docDateFrom = val?.[0]
  query.docDateTo = val?.[1]
}

const warehouseText = computed(() => (query.warehouseId ? (warehouses.value.find((w) => w.id === query.warehouseId)?.name ?? '已选') : ''))
const filtered = computed(
  () => !!(query.keyword.trim() || query.docTypeTab || query.statusTab || query.warehouseId || query.partnerId || query.docDateFrom),
)
const emptyText = computed(() => {
  if (filtered.value) return `没有符合条件的${docName.value}：换个类型，或者清空筛选`
  if (query.mine) return `还没有你建的${docName.value}，切到「全部」看所有人的`
  return props.kind === 'IN'
    ? '还没有入库单：采购到货在「待到料」点「入库」，其他的点页头按钮新建'
    : '还没有出库单：领料在生产单上点「领料」，其他的点页头按钮新建'
})

function onRowClick(row: InvDocRow, column: TableColumnCtx<InvDocRow> | null) {
  if (column?.type !== 'selection') void router.push(`${basePath.value}/${row.id}`)
}

const batchActions: BatchAction<InvDocRow>[] = [
  {
    label: '审核',
    perm: 'inventory:doc:audit',
    can: (r) => r.actions.includes('audit'),
    confirm: '审核后马上改库存；库存不够的会失败，留着勾。',
    run: (r, o) => invDocApi.act(r.id, 'audit', { version: r.version }, o),
  },
]

/** 导出用的条件：和列表一样（不翻页） */
function exportQuery(): InvDocQuery {
  return {
    kind: props.kind,
    keyword: query.keyword.trim() || undefined,
    docType: query.docTypeTab || undefined,
    status: query.statusTab ? [query.statusTab] : undefined,
    warehouseId: query.warehouseId,
    partnerId: query.partnerId,
    docDateFrom: query.docDateFrom,
    docDateTo: query.docDateTo,
    mine: query.mine,
  }
}

const exporting = ref(false)
async function exportAll() {
  exporting.value = true
  try {
    await invDocApi.export(exportQuery())
  } finally {
    exporting.value = false
  }
}

async function exportPicked() {
  exporting.value = true
  try {
    await invDocApi.export({ kind: props.kind, ids: selected.value.map((r) => r.id) })
  } finally {
    exporting.value = false
  }
}

defineExpose({ reload: load, exportAll, exporting })
</script>

<template>
  <div>
    <FilterBar v-model:keyword="query.keyword" placeholder="单号、源单号、物料、缸号" batch="单号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.docTypeTab" :options="TYPE_OPTIONS" @change="search" />
      </template>
      <FilterItem label="状态" :text="query.statusTab ? INV_DOC_STATUS[query.statusTab].label : ''" @clear="query.statusTab = ''">
        <el-select v-model="query.statusTab" filterable clearable placeholder="全部状态" value-on-clear="">
          <el-option v-for="st in STATUSES" :key="st" :label="INV_DOC_STATUS[st].label" :value="st" />
        </el-select>
      </FilterItem>
      <FilterItem label="日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <FilterItem label="仓库" :text="warehouseText" @clear="query.warehouseId = undefined">
        <el-select v-model="query.warehouseId" filterable clearable placeholder="全部仓库" :value-on-clear="undefined">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </FilterItem>
      <FilterItem label="往来单位" :text="query.partnerId ? (partnerName ?? '已选') : ''" @clear="query.partnerId = undefined">
        <SupplierSelect v-model="query.partnerId" v-model:label="partnerName" usage="BULK" clearable placeholder="全部往来单位" />
      </FilterItem>
      <template #right>
        <el-segmented v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
      </template>
    </FilterBar>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="list"
      row-key="id"
      row-class-name="clickable"
      scrollbar-always-on
      :empty-text="emptyText"
      @selection-change="selected = $event"
      @row-click="onRowClick"
    >
      <el-table-column type="selection" width="40" fixed="left" reserve-selection />
      <el-table-column label="单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.type" label="类型" width="96">
        <template #default="{ row }">{{ INV_DOC_TYPE[(row as InvDocRow).docType].label }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.warehouses" label="出仓 → 入仓" min-width="240">
        <template #default="{ row }">
          {{ row.fromWarehouseName ?? '' }}<template v-if="row.fromWarehouseName && row.toWarehouseName"> → </template>{{ row.toWarehouseName ?? '' }}
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.partner" label="往来" width="130">
        <template #default="{ row }">{{ row.partnerName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.source" label="源单" width="140">
        <template #default="{ row }"><span class="code">{{ row.sourceNo ?? '—' }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.material" label="物料" min-width="180">
        <template #default="{ row }">
          <template v-if="row.firstItem">
            {{ row.firstItem.materialName }}<span v-if="row.itemCount > 1" class="dim"> 等 {{ row.itemCount }} 种</span>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.qty" label="数量" width="100" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.amount" label="金额" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ row.totalAmount ? formatMoney(row.totalAmount) : '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.date" label="日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.docDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.reason" label="原因" min-width="140">
        <template #default="{ row }">{{ reasonText(row as InvDocRow) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.created" label="制单" width="190">
        <template #default="{ row }">
          {{ row.createByName ?? '—' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="112" fixed="right">
        <template #default="{ row }">
          <StatusTag :tone="INV_DOC_STATUS[(row as InvDocRow).status].tone">{{ INV_DOC_STATUS[(row as InvDocRow).status].label }}</StatusTag>
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

    <BatchBar :rows="selected" :table="tableRef" unit="张" :actions="batchActions" @done="load">
      <el-button v-perm="'inventory:doc:export'" size="small" :loading="exporting" @click="exportPicked">导出选中</el-button>
    </BatchBar>
  </div>
</template>

<style scoped>
.small {
  font-size: 11.5px;
}
</style>
