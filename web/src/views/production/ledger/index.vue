<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ledgerApi } from '@/api/production'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTable } from '@/composables/useTable'
import {
  LEDGER_STATUS,
  PRODUCTION_KIND,
  PRODUCTION_STATUS,
  type LedgerMaterialRow,
  type LedgerOrderRow,
  type LedgerStatus,
  type ProductionKind,
} from '@/types/production'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatQty } from '@/utils/format'

/**
 * 工厂料账（/factory-ledger，RFC 015 §4.2）：两个视图。
 * - 按生产单：领了多少料、按良品该用掉多少、核销了没有；点一行去生产单的"领料 · 料账"页签核销
 * - 按工厂物料：每个工厂仓 × 料的账面、还没核销的单会用掉多少、估计工厂手上还剩多少；能导出发给工厂对账
 */
const route = useRoute()
const router = useRouter()
const units = useDict('unit')
type View = 'orders' | 'materials'
const view = ref<View>('orders')

// ---------- 按生产单 ----------

const orderCols = useColumns('factoryLedgerOrders', [
  { key: 'kind', label: '种类' },
  { key: 'factory', label: '加工厂' },
  { key: 'style', label: '款式' },
  { key: 'qty', label: '加工数 / 良品' },
  { key: 'status', label: '生产状态' },
  { key: 'dueDate', label: '交期', hidden: true },
  { key: 'over', label: '超耗' },
  { key: 'returns', label: '该退' },
  { key: 'settleDoc', label: '核销单' },
])

const counts = ref<Record<LedgerStatus, number> | null>(null)
const countsRequest = useLatestRequest()
async function loadCounts(params: { keyword?: string; factoryId?: string | null; kind?: ProductionKind | null }) {
  const read = countsRequest.begin()
  try {
    const result = await read.run(() => ledgerApi.counts(params))
    if (read.current()) counts.value = result
  } catch {
    if (read.current()) counts.value = null
  }
}

const initialStatus = (['IN_PROGRESS', 'TO_SETTLE', 'SETTLING', 'SETTLED'] as const).find((s) => s === route.query.status) ?? 'TO_SETTLE'
const factoryName = ref<string | null>(null)
const orders = reactive(useTable({
  fetch: (params) => {
    const { status: _s, page: _p, size: _z, ...rest } = params
    void loadCounts({ ...rest, kind: rest.kind || null })
    return ledgerApi.orders({ ...params, kind: params.kind || null, status: params.status === 'ALL' ? null : params.status })
  },
  query: {
    keyword: '',
    status: initialStatus as LedgerStatus | 'ALL',
    factoryId: undefined as string | null | undefined,
    kind: '' as ProductionKind | '',
  },
}))

const SEGMENTS: { value: LedgerStatus | 'ALL'; label: string }[] = [
  { value: 'TO_SETTLE', label: '待核销' },
  { value: 'SETTLING', label: '核销中' },
  { value: 'SETTLED', label: '已核销' },
  { value: 'IN_PROGRESS', label: '在制' },
  { value: 'ALL', label: '全部' },
]
const segmentOptions = computed(() =>
  SEGMENTS.map((s) => {
    const n = s.value === 'ALL' ? null : counts.value?.[s.value]
    return { value: s.value, label: n ? `${s.label} ${n}` : s.label }
  }),
)

const orderEmpty = computed(() => {
  const q = orders.query
  if (q.keyword.trim() || q.factoryId || q.kind) return '没有符合条件的：换个分段，或者清空筛选'
  if (q.status === 'TO_SETTLE') return '没有待核销的生产单：做完货（已完成、已结案）又领过料的单会出现在这里'
  return '没有这种状态的生产单：资料包里有自购料的生产单才进料账'
})

function openOrder(row: LedgerOrderRow) {
  const to = docRoute({ type: 'PRODUCTION_ORDER', kind: row.kind, id: row.id, parentId: null })
  if (to) void router.push(`${to}?tab=materials`)
}

function openSettleDoc(row: LedgerOrderRow) {
  if (row.settleDocId) void router.push(`/stock-out/${row.settleDocId}`)
}

// ---------- 按工厂物料 ----------

const matCols = useColumns('factoryLedgerMaterials', [
  { key: 'spec', label: '规格' },
  { key: 'book', label: '账面' },
  { key: 'pending', label: '未核销的耗料' },
  { key: 'estimate', label: '估计手上' },
  { key: 'orders', label: '涉及几张单' },
])
const matFactoryName = ref<string | null>(null)
const materials = reactive(useTable({
  fetch: (params) => ledgerApi.materials(params),
  query: {
    keyword: '',
    factoryId: undefined as string | null | undefined,
  },
  immediate: false,
}))
const exporting = ref(false)
async function exportMaterials() {
  exporting.value = true
  try {
    const { keyword, factoryId } = materials.query
    await ledgerApi.exportMaterials({ keyword, factoryId })
  } finally {
    exporting.value = false
  }
}

let materialsLoaded = false
function switchView(v: View) {
  view.value = v
  if (v === 'materials' && !materialsLoaded) {
    materialsLoaded = true
    void materials.search()
  }
}

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (view.value === 'orders') void orders.load()
  else void materials.load()
})
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-segmented :model-value="view" :options="[{ label: '按生产单', value: 'orders' }, { label: '按工厂物料', value: 'materials' }]"
          @change="(v: View) => switchView(v)" />
        <el-button v-if="view === 'materials'" v-perm="'production:ledger:export'" icon="Download" :loading="exporting" @click="exportMaterials">导出</el-button>
      </template>
    </PageHeader>

    <template v-if="view === 'orders'">
      <FilterBar v-model:keyword="orders.query.keyword" placeholder="生产单号、款号" :columns="orderCols" @search="orders.search">
        <template #left>
          <el-segmented v-model="orders.query.status" :options="segmentOptions" @change="orders.search" />
        </template>
        <FilterItem label="加工厂" :text="orders.query.factoryId ? (factoryName ?? '已选') : ''" @clear="orders.query.factoryId = undefined">
          <SupplierSelect v-model="orders.query.factoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" clearable />
        </FilterItem>
        <FilterItem label="种类" :text="orders.query.kind ? PRODUCTION_KIND[orders.query.kind].label : ''" @clear="orders.query.kind = ''">
          <el-select v-model="orders.query.kind" clearable placeholder="全部">
            <el-option v-for="(m, k) in PRODUCTION_KIND" :key="k" :label="m.label" :value="k" />
          </el-select>
        </FilterItem>
      </FilterBar>
      <el-table v-loading="orders.loading" :data="orders.list" row-key="id" row-class-name="clickable" scrollbar-always-on
        :empty-text="orderEmpty" @row-click="openOrder">
        <el-table-column label="生产单号" prop="no" width="150" fixed="left" class-name="code" />
        <el-table-column v-if="orderCols.show.kind" label="种类" width="90">
          <template #default="{ row }">{{ PRODUCTION_KIND[(row as LedgerOrderRow).kind].label }}</template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.factory" label="加工厂" prop="factoryShortName" min-width="110" />
        <el-table-column v-if="orderCols.show.style" label="款式" min-width="180">
          <template #default="{ row }"><span class="code">{{ row.styleCode }}</span> <span class="dim small">{{ row.styleName }}</span></template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.qty" label="加工数 / 良品" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.totalQty) }} / {{ formatQty(row.goodQty) }}</template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.status" label="生产状态" width="100">
          <template #default="{ row }">
            <StatusTag :tone="PRODUCTION_STATUS[(row as LedgerOrderRow).status].tone">{{ PRODUCTION_STATUS[(row as LedgerOrderRow).status].label }}</StatusTag>
          </template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.dueDate" label="交期" width="110" class-name="num">
          <template #default="{ row }">{{ formatDate(row.dueDate) }}</template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.over" label="超耗" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.overCount" class="warn">{{ row.overCount }} 种</span><span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.returns" label="该退" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.returnCount" class="warn">{{ row.returnCount }} 种</span><span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="orderCols.show.settleDoc" label="核销单" width="150">
          <template #default="{ row }">
            <a v-if="row.settleDocNo" class="code link" @click.stop="openSettleDoc(row as LedgerOrderRow)">{{ row.settleDocNo }}</a>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="料账" width="96" fixed="right">
          <template #default="{ row }">
            <StatusTag :tone="LEDGER_STATUS[(row as LedgerOrderRow).ledgerStatus].tone">{{ LEDGER_STATUS[(row as LedgerOrderRow).ledgerStatus].label }}</StatusTag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="orders.page" v-model:page-size="orders.size" class="pagination"
        layout="total, sizes, prev, pager, next" :page-sizes="orders.pageSizes" :total="orders.total" />
    </template>

    <template v-else>
      <FilterBar v-model:keyword="materials.query.keyword" placeholder="SKU 码、物料编码、名称" :columns="matCols" @search="materials.search">
        <FilterItem label="加工厂" :text="materials.query.factoryId ? (matFactoryName ?? '已选') : ''" @clear="materials.query.factoryId = undefined">
          <SupplierSelect v-model="materials.query.factoryId" v-model:label="matFactoryName" :category="['GARMENT', 'PROCESSOR']" clearable />
        </FilterItem>
      </FilterBar>
      <el-table v-loading="materials.loading" :data="materials.list" :row-key="(r: LedgerMaterialRow) => `${r.factoryId}-${r.materialSkuId}`"
        scrollbar-always-on :empty-text="materials.query.keyword || materials.query.factoryId ? '没有符合条件的：清空筛选再看'
          : '工厂仓里没有料，也没有没核销的单：领料出库到工厂仓以后就有了'">
        <el-table-column label="加工厂" prop="factoryShortName" width="120" fixed="left" />
        <el-table-column label="物料" min-width="200">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span>
            <span class="dim small"> {{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></span>
          </template>
        </el-table-column>
        <el-table-column v-if="matCols.show.spec" label="规格" prop="spec" width="100" />
        <el-table-column label="单位" width="60">
          <template #default="{ row }">{{ units.labelOf(row.stockUnit) }}</template>
        </el-table-column>
        <el-table-column v-if="matCols.show.book" label="账面" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.bookQty) }}</template>
        </el-table-column>
        <el-table-column v-if="matCols.show.pending" label="未核销的耗料" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.pendingQty) }}</template>
        </el-table-column>
        <el-table-column v-if="matCols.show.estimate" label="估计手上" width="120" class-name="num" align="right">
          <template #default="{ row }">
            <strong v-if="row.estimateQty < 0" class="danger-text" title="工厂用的比账上多：直发的料没入库、料被调走了，或者超耗了没报">{{ formatQty(row.estimateQty) }}</strong>
            <template v-else>{{ formatQty(row.estimateQty) }}</template>
          </template>
        </el-table-column>
        <el-table-column v-if="matCols.show.orders" label="涉及几张单" width="110" align="right">
          <template #default="{ row }">{{ row.orderCount ? `${row.orderCount} 张` : '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="materials.page" v-model:page-size="materials.size" class="pagination"
        layout="total, sizes, prev, pager, next" :page-sizes="materials.pageSizes" :total="materials.total" />
    </template>
  </div>
</template>

<style scoped>
.link {
  color: var(--lm-accent);
  cursor: pointer;
}

.warn {
  color: var(--lm-pending-text);
}

.danger-text {
  color: var(--lm-danger-text);
}
</style>
