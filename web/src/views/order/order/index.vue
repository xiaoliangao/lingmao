<script setup lang="ts">
import { computed, onActivated, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type TableColumnCtx, type TableInstance } from 'element-plus'
import { orderApi } from '@/api/order'
import { kittingApi } from '@/api/purchase'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import DictSelect from '@/components/DictSelect.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import { addDaysStr, todayStr } from '@/components/doc/docUtils'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import { ORDER_SOURCE, ORDER_STATUS, type OrderImportHead, type OrderQuery, type OrderRow, type OrderStatus } from '@/types/order'
import { KITTING_STATUS, type OrderKitting } from '@/types/purchase'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatQty } from '@/utils/format'
import { download } from '@/utils/request'

/**
 * 需求订单列表（/orders，docs/api/02 §1.1，原型 route('/orders')）。
 * - 地址参数：?status=、?unscheduled=1（首页点进来）、?orderDateFrom=&orderDateTo=。页签留着时别处带参数点进来也要换，
 *   但只认 /orders 自己的地址：keep-alive 的页面切走以后 route 还会变，不看 path 会被别的页面的 ?status= 改掉
 * - 齐料一列（02 §3.13）：列表到了以后另取，只取已审核、生产中、已完成的；取不到显示"—"，不影响列表
 * - 状态页签上的数也是另取，失败了只是不显示数
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const orderTypes = useDict('order_type')
const brands = useDict('brand')

// 订单号、状态总在；后面几列默认不显示，在"显示列"里勾
const cols = useColumns('orders', [
  { key: 'orderDate', label: '下单日期' },
  { key: 'type', label: '类型' },
  { key: 'styles', label: '款式' },
  { key: 'qty', label: '数量' },
  { key: 'amount', label: '金额' },
  { key: 'dueDate', label: '交期' },
  { key: 'scheduled', label: '排产' },
  { key: 'returned', label: '回货' },
  { key: 'kitting', label: '齐料' },
  { key: 'merch', label: '跟单' },
  { key: 'contractNo', label: '合同号', hidden: true },
  { key: 'sales', label: '业务员', hidden: true },
  { key: 'company', label: '主体', hidden: true },
  { key: 'source', label: '来源', hidden: true },
  { key: 'created', label: '制单', hidden: true },
])

const ALL_STATUSES: OrderStatus[] = ['DRAFT', 'PENDING', 'APPROVED', 'RUNNING', 'DONE', 'CLOSED', 'VOIDED']
const counts = ref<Partial<Record<OrderStatus, number>>>({})
const dateRange = ref<[string, string] | null>(null)
const salesName = ref<string | null>(null)
const merchName = ref<string | null>(null)

const pickStatus = (value: unknown): OrderStatus | '' => (ALL_STATUSES as unknown[]).includes(value) ? (value as OrderStatus) : ''
const pickDate = (value: unknown) => (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : undefined)

let countSeq = 0
async function loadCounts(params: Omit<OrderQuery, 'status' | 'ids'>) {
  const current = ++countSeq
  try {
    const result = await orderApi.counts(params)
    if (current === countSeq) counts.value = result
  } catch {
    // 数取不到只是页签上不显示数，列表照常
    if (current === countSeq) counts.value = {}
  }
}

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile } = useTable({
  fetch: ({ statusTab, ...rest }) => {
    void loadCounts(rest)
    return orderApi.page({ ...rest, status: statusTab ? [statusTab] : undefined })
  },
  query: {
    keyword: '',
    statusTab: pickStatus(route.query.status),
    orderType: '',
    brand: '',
    salesId: undefined as number | null | undefined,
    merchId: undefined as number | null | undefined,
    orderDateFrom: pickDate(route.query.orderDateFrom),
    orderDateTo: pickDate(route.query.orderDateTo),
    unscheduled: route.query.unscheduled === '1' ? true : (undefined as boolean | undefined),
    mine: false,
  },
  exportUrl: orderApi.exportUrl,
  exportName: '需求订单.xlsx',
})
if (query.orderDateFrom && query.orderDateTo) dateRange.value = [query.orderDateFrom, query.orderDateTo]

watch(
  () => route.query,
  (q) => {
    if (route.path !== '/orders') return
    const status = pickStatus(q.status)
    const unscheduled = q.unscheduled === '1' ? true : undefined
    const from = pickDate(q.orderDateFrom)
    const to = pickDate(q.orderDateTo)
    if (status === query.statusTab && unscheduled === query.unscheduled && from === query.orderDateFrom && to === query.orderDateTo) return
    query.statusTab = status
    query.unscheduled = unscheduled
    query.orderDateFrom = from
    query.orderDateTo = to
    dateRange.value = from && to ? [from, to] : null
    search()
  },
)

// ---------- 齐料（另取，02 §3.13） ----------
const KITTING_STATUSES: OrderStatus[] = ['APPROVED', 'RUNNING', 'DONE']
const kittingMap = ref<Record<number, OrderKitting>>({})
let kittingSeq = 0
watch(list, async (rows) => {
  const current = ++kittingSeq
  const ids = rows.filter((r) => KITTING_STATUSES.includes(r.status)).map((r) => r.id)
  try {
    const kits = await kittingApi.byOrders(ids)
    if (current === kittingSeq) kittingMap.value = Object.fromEntries(kits.map((k) => [k.orderId, k]))
  } catch {
    if (current === kittingSeq) kittingMap.value = {}
  }
})

function openKitting(kit: OrderKitting) {
  const calcId = kit.calcIds[0]
  if (calcId) void router.push(`/mrp/${calcId}`)
}

const statusOptions = computed(() => {
  const totalAll = Object.values(counts.value).reduce((s, n) => s + (n ?? 0), 0)
  return [
    { label: totalAll ? `全部 ${totalAll}` : '全部', value: '' },
    ...ALL_STATUSES.map((st) => {
      const n = counts.value[st]
      return { label: n ? `${ORDER_STATUS[st].label} ${n}` : ORDER_STATUS[st].label, value: st }
    }),
  ]
})

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.orderDateFrom = val?.[0]
  query.orderDateTo = val?.[1]
}

const filtered = computed(
  () =>
    !!(query.keyword.trim() || query.statusTab || query.orderType || query.brand || query.salesId || query.merchId || query.orderDateFrom || query.unscheduled),
)
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的需求订单：换个状态页签，或者清空筛选'
  if (query.mine) return '还没有你是制单人、业务员或跟单员的订单，切到「全部」看所有人的'
  return '还没有需求订单'
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function onRowClick(row: OrderRow, column: TableColumnCtx<OrderRow> | null) {
  if (column?.type !== 'selection') void router.push(`/orders/${row.id}`)
}

const canSchedule = (row: OrderRow) => row.status !== 'DRAFT' && row.status !== 'PENDING' && row.status !== 'VOIDED'

// ---------- 勾选与批量 ----------
const tableRef = ref<TableInstance>()
const selected = ref<OrderRow[]>([])

/** 用行上的 version：列表取到以后别人改过，后端回 1002，批量条留着勾、报原因 */
const batchActions: BatchAction<OrderRow>[] = [
  {
    label: '提交',
    perm: 'order:order:submit',
    can: (r) => r.actions.includes('submit'),
    run: (r, o) => orderApi.act(r.id, 'submit', { version: r.version }, o),
  },
  {
    label: '审核',
    can: (r) => r.actions.includes('audit'),
    confirm: '承诺交期按要求交期定，要另定的进详情审。',
    run: (r, o) => orderApi.audit(r.id, { version: r.version, commitDate: r.commitDate ?? r.requiredDate }, o),
  },
]

const exportingPicked = ref(false)
async function exportPicked() {
  exportingPicked.value = true
  try {
    await download(orderApi.exportUrl, { ids: selected.value.map((r) => r.id) }, '需求订单.xlsx')
  } finally {
    exportingPicked.value = false
  }
}

// ---------- Excel 导入（单头在 #head 里，02 §3.9） ----------
const importVisible = ref(false)
/** 主体默认我的默认主体，必填（没有默认主体时要选了才能导）；业务员、跟单员默认我，清空 = 导入的人 */
const importHead = reactive({
  companyId: user.defaultCompanyId,
  orderType: 'STOCK',
  brand: null as string | null,
  requiredDate: addDaysStr(todayStr(), 60),
  salesId: user.info?.id ?? null,
  merchId: user.info?.id ?? null,
})

const importReadyHead = computed<OrderImportHead | null>(() => {
  const { companyId, ...rest } = importHead
  return companyId && rest.orderType && rest.requiredDate ? { ...rest, companyId } : null
})
const importUrl = computed(() => (importReadyHead.value ? orderApi.importUrl(importReadyHead.value) : ''))

function onImported(count: number) {
  ElMessage.success(`导进来 ${count} 行明细，都是草稿：核对后再提交审核`)
  search()
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'order:order:import'" icon="Upload" @click="importVisible = true">导入</el-button>
        <el-button v-perm="'order:order:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'order:order:add'" type="primary" icon="Plus" @click="router.push('/orders/new')">新建需求订单</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="单号、合同号、款号" batch="单号、款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.statusTab" :options="statusOptions" @change="search" />
      </template>

      <FilterItem label="订单类型" :text="orderTypes.labelOf(query.orderType)" @clear="query.orderType = ''">
        <DictSelect v-model="query.orderType" type="order_type" clearable placeholder="全部类型" />
      </FilterItem>
      <FilterItem label="品牌" :text="brands.labelOf(query.brand)" @clear="query.brand = ''">
        <DictSelect v-model="query.brand" type="brand" clearable placeholder="全部品牌" />
      </FilterItem>
      <FilterItem label="下单日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <FilterItem label="业务员" :text="query.salesId ? (salesName ?? '已选') : ''" @clear="query.salesId = undefined">
        <UserSelect v-model="query.salesId" v-model:label="salesName" clearable placeholder="全部业务员" />
      </FilterItem>
      <FilterItem label="跟单员" :text="query.merchId ? (merchName ?? '已选') : ''" @clear="query.merchId = undefined">
        <UserSelect v-model="query.merchId" v-model:label="merchName" clearable placeholder="全部跟单员" />
      </FilterItem>
      <FilterItem label="排单" :text="query.unscheduled ? '待排单' : ''" @clear="query.unscheduled = undefined">
        <el-checkbox :model-value="!!query.unscheduled" @change="query.unscheduled = $event ? true : undefined">只看已审核、还没排单的</el-checkbox>
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
      <el-table-column label="订单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.orderDate" label="下单日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.orderDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.type" label="类型" min-width="140">
        <template #default="{ row }">
          {{ orderTypes.labelOf(row.orderType) }}
          <span v-if="row.brand" class="dim small"> {{ brands.labelOf(row.brand) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.styles" label="款式" min-width="180">
        <template #default="{ row }">
          <span class="styles-cell">
            <template v-for="st in (row as OrderRow).styles.slice(0, 2)" :key="st.styleId">
              <img v-if="st.image" :src="st.image.thumbUrl ?? st.image.url" alt="" class="thumb" loading="lazy" />
              <span class="code">{{ st.styleCode }}</span>
            </template>
            <span v-if="(row as OrderRow).styles.length > 2" class="dim">等 {{ (row as OrderRow).styles.length }} 款</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.qty" label="数量" width="90" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.amount" label="金额" width="130" class-name="num" align="right">
        <template #default="{ row }">
          <span v-if="row.currency !== 'CNY'" class="dim">{{ row.currency }} </span>{{ formatMoney(row.totalAmount) }}
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.dueDate" label="交期" width="136" class-name="num">
        <template #default="{ row }">
          {{ formatDate(row.commitDate && row.status !== 'DRAFT' && row.status !== 'PENDING' ? row.commitDate : row.requiredDate) }}
          <span class="dim small">{{ row.commitDate && row.status !== 'DRAFT' && row.status !== 'PENDING' ? '承诺' : '要求' }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.scheduled" label="排产" width="112">
        <template #default="{ row }">
          <QtyProgress v-if="canSchedule(row as OrderRow)" :done="row.scheduledQty" :total="row.totalQty" />
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.returned" label="回货" width="112">
        <template #default="{ row }">
          <QtyProgress v-if="row.scheduledQty > 0" :done="row.returnedQty" :total="row.totalQty" tone="done" />
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.kitting" label="齐料" width="136">
        <template #default="{ row }">
          <button v-if="kittingMap[row.id]" type="button" class="tag-btn" :disabled="!kittingMap[row.id]!.calcIds.length" @click.stop="openKitting(kittingMap[row.id]!)">
            <StatusTag :tone="KITTING_STATUS[kittingMap[row.id]!.status].tone">{{ kittingMap[row.id]!.label }}</StatusTag>
          </button>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.merch" label="跟单" width="96">
        <template #default="{ row }">{{ row.merchName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.contractNo" label="合同号" min-width="130" class-name="code">
        <template #default="{ row }">{{ row.contractNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.sales" label="业务员" width="96">
        <template #default="{ row }">{{ row.salesName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.company" label="主体" min-width="160">
        <template #default="{ row }">{{ row.companyName }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.source" label="来源" width="104">
        <template #default="{ row }">{{ ORDER_SOURCE[(row as OrderRow).source] }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.created" label="制单" width="190">
        <template #default="{ row }">
          {{ row.createByName ?? '—' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="112" fixed="right">
        <template #default="{ row }">
          <StatusTag :tone="ORDER_STATUS[(row as OrderRow).status].tone">{{ ORDER_STATUS[(row as OrderRow).status].label }}</StatusTag>
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
      <el-button v-perm="'order:order:export'" size="small" :loading="exportingPicked" @click="exportPicked">导出选中</el-button>
    </BatchBar>

    <ImportDialog
      v-model="importVisible"
      title="导入需求订单"
      :template-url="orderApi.importTemplateUrl"
      :import-url="importUrl"
      :ready="!!importReadyHead"
      @success="onImported"
    >
      <template #head>
        <el-form label-position="top" class="form-grid import-head">
          <el-form-item label="主体" required>
            <CompanySelect v-model="importHead.companyId" placeholder="选主体" />
          </el-form-item>
          <el-form-item label="订单类型" required>
            <DictSelect v-model="importHead.orderType" type="order_type" />
          </el-form-item>
          <el-form-item label="品牌">
            <DictSelect v-model="importHead.brand" type="brand" clearable />
          </el-form-item>
          <el-form-item label="要求交期" required>
            <el-date-picker v-model="importHead.requiredDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
          </el-form-item>
          <el-form-item label="业务员">
            <UserSelect v-model="importHead.salesId" :label="user.info?.nickname" />
          </el-form-item>
          <el-form-item label="跟单员">
            <UserSelect v-model="importHead.merchId" :label="user.info?.nickname" />
          </el-form-item>
        </el-form>
      </template>
    </ImportDialog>
  </div>
</template>

<style scoped>
.styles-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
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

.tag-btn {
  padding: 0;
  font: inherit;
  cursor: pointer;
  background: none;
  border: none;
}

.tag-btn:disabled {
  cursor: default;
}

.import-head {
  margin-bottom: 12px;
}

.import-head :deep(.el-select),
.import-head :deep(.el-date-editor) {
  width: 100%;
}
</style>
