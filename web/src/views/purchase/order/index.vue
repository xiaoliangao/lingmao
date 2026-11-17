<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { purchaseApi } from '@/api/purchase'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import { useColumns } from '@/composables/useColumns'
import { useTable } from '@/composables/useTable'
import {
  PURCHASE_STATUS,
  PURCHASE_TYPE_LABEL,
  type PurchaseRow,
  type PurchaseStatus,
  type PurchaseType,
} from '@/types/purchase'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatQty } from '@/utils/format'

/**
 * 采购单列表（/purchases，docs/api/03 §1.4）。认 ?status=（首页"采购单待审核"点进来），只认 /purchases 自己的地址。
 * 勾选后批量提交、审核、导出选中；批量审核和详情一样，单价和档案不一样的行审核后记进价格档案
 */
const route = useRoute()
const router = useRouter()

// 采购单号、状态总在；后面几列默认不显示，在"显示列"里勾
const cols = useColumns('purchases', [
  { key: 'supplier', label: '供应商' },
  { key: 'type', label: '类型' },
  { key: 'material', label: '物料摘要' },
  { key: 'amount', label: '总金额' },
  { key: 'orderDate', label: '下单日' },
  { key: 'deliveryDate', label: '交货日' },
  { key: 'received', label: '到货进度' },
  { key: 'buyer', label: '采购员' },
  { key: 'qty', label: '数量', hidden: true },
  { key: 'warehouse', label: '交货地点', hidden: true },
  { key: 'company', label: '主体', hidden: true },
  { key: 'createTime', label: '制单时间', hidden: true },
])

const STATUSES: PurchaseStatus[] = ['DRAFT', 'PENDING', 'APPROVED', 'RUNNING', 'DONE', 'CLOSED', 'VOIDED']
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...STATUSES.map((st) => ({ label: PURCHASE_STATUS[st].label, value: st }))]
const TYPE_OPTIONS = Object.entries(PURCHASE_TYPE_LABEL).map(([value, label]) => ({ value: value as PurchaseType, label }))
const pickStatus = (value: unknown): PurchaseStatus | '' => (STATUSES.includes(value as PurchaseStatus) ? (value as PurchaseStatus) : '')
const supplierName = ref<string | null>(null)
const buyerName = ref<string | null>(null)

const dateRange = ref<[string, string] | null>(null)

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  fetch: ({ statusTab, ...rest }) =>
    purchaseApi.page({ ...rest, status: statusTab ? [statusTab] : undefined }),
  query: {
    keyword: '',
    statusTab: pickStatus(route.query.status),
    purchaseType: undefined as PurchaseType | undefined,
    supplierId: undefined as number | null | undefined,
    buyerId: undefined as number | null | undefined,
    orderDateFrom: undefined as string | undefined,
    orderDateTo: undefined as string | undefined,
    mine: false,
  },
  exportUrl: '/api/purchase-orders/export',
  exportName: '采购单.xlsx',
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== '/purchases') return
    const st = pickStatus(value)
    if (st !== query.statusTab) {
      query.statusTab = st
      search()
    }
  },
)

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.orderDateFrom = val?.[0]
  query.orderDateTo = val?.[1]
}

const filtered = computed(
  () => !!(query.keyword.trim() || query.statusTab || query.purchaseType || query.supplierId || query.buyerId || query.orderDateFrom),
)
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的采购单：换个状态，或者清空筛选'
  if (query.mine) return '还没有你是采购员、制单人的采购单，切到「全部」看所有人的'
  return '还没有采购单：在算料单里勾要买的行生成，或者点「新建采购单」'
})
/** 到货进度：草稿、待审核、作废写"—" */
const showProgress = (row: PurchaseRow) => !['DRAFT', 'PENDING', 'VOIDED'].includes(row.status)

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function onRowClick(row: PurchaseRow, column: TableColumnCtx<PurchaseRow> | null) {
  if (column?.type !== 'selection') router.push(`/purchases/${row.id}`)
}


// ---------- 勾选与批量 ----------
const tableRef = ref<TableInstance>()
const selected = ref<PurchaseRow[]>([])

const batchActions: BatchAction<PurchaseRow>[] = [
  {
    label: '提交',
    perm: 'purchase:order:submit',
    can: (r) => r.actions.includes('submit'),
    run: (r, o) => purchaseApi.act(r.id, 'submit', { version: r.version }, o),
  },
  {
    label: '审核',
    can: (r) => r.actions.includes('audit'),
    confirm: '单价和价格档案不一样的行，审核后记进价格档案。',
    run: (r, o) => purchaseApi.act(r.id, 'audit', { version: r.version }, o),
  },
]

const exportingPicked = ref(false)
async function exportPicked() {
  exportingPicked.value = true
  try {
    await purchaseApi.export({ ids: selected.value.map((r) => r.id) })
  } finally {
    exportingPicked.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'purchase:order:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'purchase:order:add'" type="primary" icon="Plus" @click="router.push('/purchases/new')">新建采购单</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="采购单号、供应商、物料编码/名称、订单号、款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.statusTab" :options="STATUS_OPTIONS" @change="search" />
      </template>
      <FilterItem label="采购类型" :text="query.purchaseType ? PURCHASE_TYPE_LABEL[query.purchaseType] : ''" @clear="query.purchaseType = undefined">
        <el-select v-model="query.purchaseType" filterable clearable placeholder="全部类型" :value-on-clear="undefined">
          <el-option v-for="o in TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterItem>
      <FilterItem label="供应商" :text="query.supplierId ? (supplierName ?? '已选') : ''" @clear="query.supplierId = undefined">
        <SupplierSelect v-model="query.supplierId" v-model:label="supplierName" clearable placeholder="全部供应商" />
      </FilterItem>
      <FilterItem label="下单日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <FilterItem label="采购员" :text="query.buyerId ? (buyerName ?? '已选') : ''" @clear="query.buyerId = undefined">
        <UserSelect v-model="query.buyerId" v-model:label="buyerName" clearable placeholder="全部采购员" />
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
      <el-table-column label="采购单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.supplier" label="供应商" min-width="130">
        <template #default="{ row }">
          <span>{{ row.supplierName }}</span>
          <span v-if="row.supplierStatus !== 'ACTIVE'" class="sup-warn">供应商已停用</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.type" label="类型" width="88">
        <template #default="{ row }">{{ PURCHASE_TYPE_LABEL[(row as PurchaseRow).purchaseType] }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.material" label="物料摘要" min-width="160">
        <template #default="{ row }">
          <template v-if="row.firstItem">
            {{ row.firstItem.materialName }}
            <span v-if="row.itemCount > 1" class="dim"> 等 {{ row.itemCount }} 种</span>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.qty" label="数量" width="100" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.amount" label="总金额" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.orderDate" label="下单日" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.orderDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.deliveryDate" label="交货日" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.deliveryDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.received" label="到货进度" width="112">
        <template #default="{ row }">
          <QtyProgress v-if="showProgress(row as PurchaseRow)" :done="row.receivedQty" :total="row.totalQty" tone="done" />
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.buyer" label="采购员" width="88" prop="buyerName" />
      <el-table-column v-if="cols.show.warehouse" label="交货地点" min-width="130" prop="warehouseName" />
      <el-table-column v-if="cols.show.company" label="主体" min-width="160" prop="companyName" />
      <el-table-column v-if="cols.show.createTime" label="制单时间" width="150" class-name="num">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="124" fixed="right">
        <template #default="{ row }">
          <StatusTag :tone="PURCHASE_STATUS[(row as PurchaseRow).status].tone">{{ PURCHASE_STATUS[(row as PurchaseRow).status].label }}</StatusTag>
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
      <el-button v-perm="'purchase:order:export'" size="small" :loading="exportingPicked" @click="exportPicked">导出选中</el-button>
    </BatchBar>
  </div>
</template>

<style scoped>
.sup-warn {
  margin-left: 4px;
  padding: 0 5px;
  font-size: 11px;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border-radius: 4px;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
