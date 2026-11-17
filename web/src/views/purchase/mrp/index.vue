<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { mrpApi } from '@/api/purchase'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import { useColumns } from '@/composables/useColumns'
import { useTable } from '@/composables/useTable'
import { statusQuery } from '@/utils/query'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { MRP_STATUS, type MrpCalcRow, type MrpOrderOption, type MrpStatus, type ReservePct } from '@/types/purchase'
import { formatDate, formatDateRange, formatQty } from '@/utils/format'

/**
 * 算料单列表（/mrp，docs/api/03 §1.4）。认 ?status=（首页"算料单草稿"点进来），只认 /mrp 自己的地址。
 * 页头：备料比（改各物料类型的默认备料比）、选订单算料（勾几张订单建一张草稿，跳到详情）
 */
const route = useRoute()
const router = useRouter()

const STATUSES: MrpStatus[] = ['DRAFT', 'CONFIRMED', 'CLOSED']
const STATUS_OPTIONS = [{ label: '全部', value: '' }, ...STATUSES.map((st) => ({ label: MRP_STATUS[st].label, value: st }))]
const pickStatus = (value: unknown): MrpStatus | '' => (STATUSES.includes(value as MrpStatus) ? (value as MrpStatus) : '')

// 单号、状态总在（状态固定在右边，重新算料成的新单小字放在状态下面）
const cols = useColumns('mrp', [
  { key: 'orders', label: '订单' },
  { key: 'styles', label: '款式' },
  { key: 'buy', label: '要买' },
  { key: 'converted', label: '转采购' },
  { key: 'needDate', label: '要料日期' },
  { key: 'creator', label: '制单人' },
  { key: 'createDate', label: '日期' },
])

const dateRange = ref<[string, string] | null>(null)
const creatorName = ref<string | null>(null)

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  normalize: (query) => statusQuery(query),
  fetch: mrpApi.page,
  query: {
    keyword: '',
    statusTab: pickStatus(route.query.status),
    createBy: undefined as number | null | undefined,
    createDateFrom: undefined as string | undefined,
    createDateTo: undefined as string | undefined,
    mine: false,
  },
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== '/mrp') return
    const st = pickStatus(value)
    if (st !== query.statusTab) {
      query.statusTab = st
      search()
    }
  },
)

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.createDateFrom = val?.[0]
  query.createDateTo = val?.[1]
}

const filtered = computed(() => !!(query.keyword.trim() || query.statusTab || query.createBy || query.createDateFrom))
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的算料单：换个状态，或者清空筛选'
  if (query.mine) return '还没有你算的料，切到「全部」看所有人的'
  return '还没有算料单：订单审核后点「选订单算料」'
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function openRow(row: MrpCalcRow) {
  void router.push(`/mrp/${row.id}`)
}

// ---------- 备料比 ----------
const reserveVisible = ref(false)
const reserveRows = ref<ReservePct[]>([])
const savingReserve = ref(false)
const reserveGuard = useUnsavedChanges(reserveRows, reserveVisible)

async function openReserve() {
  reserveRows.value = await mrpApi.reservePcts()
  reserveGuard.markSaved()
  reserveVisible.value = true
}

async function saveReserve() {
  savingReserve.value = true
  try {
    await mrpApi.saveReservePcts(reserveRows.value.map((r) => ({ materialType: r.materialType, pct: r.pct ?? 0 })))
    ElMessage.success('备料比存好了，以后算的单按它')
    reserveGuard.markSaved()
    reserveVisible.value = false
  } finally {
    savingReserve.value = false
  }
}

// ---------- 选订单算料 ----------
const pickVisible = ref(false)
const orderKeyword = ref('')
const orderOptions = ref<MrpOrderOption[]>([])
const orderTotal = ref(0)
const orderPage = ref(1)
const picked = ref<MrpOrderOption[]>([])
const loadingOrders = ref(false)
const creating = ref(false)

async function loadOrderOptions() {
  loadingOrders.value = true
  try {
    const res = await mrpApi.orderOptions({ page: orderPage.value, size: 20, keyword: orderKeyword.value.trim() || undefined })
    orderOptions.value = res.list
    orderTotal.value = res.total
  } finally {
    loadingOrders.value = false
  }
}

function searchOrders() {
  orderPage.value = 1
  void loadOrderOptions()
}

function openPick() {
  picked.value = []
  orderKeyword.value = ''
  orderPage.value = 1
  pickVisible.value = true
  void loadOrderOptions()
}

async function createCalc() {
  if (!picked.value.length) {
    ElMessage.warning('至少勾一张订单')
    return
  }
  creating.value = true
  try {
    const created = await mrpApi.create(picked.value.map((o) => o.orderId))
    ElMessage.success(`${created.no} 算好了，勾选要买的行生成采购单`)
    pickVisible.value = false
    await router.push(`/mrp/${created.id}`)
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-perm="'purchase:mrp:edit'" @click="openReserve">备料比</el-button>
        <el-button v-perm="'purchase:mrp:add'" type="primary" icon="Plus" @click="openPick">选订单算料</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="算料单号、订单号、款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.statusTab" :options="STATUS_OPTIONS" @change="search" />
      </template>
      <FilterItem label="制单人" :text="query.createBy ? (creatorName ?? '已选') : ''" @clear="query.createBy = undefined">
        <UserSelect v-model="query.createBy" v-model:label="creatorName" clearable placeholder="全部制单人" />
      </FilterItem>
      <FilterItem label="算料日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <template #right>
        <el-segmented v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
      </template>
    </FilterBar>

    <el-table v-loading="loading" :data="list" row-key="id" row-class-name="clickable" scrollbar-always-on :empty-text="emptyText" @row-click="openRow">
      <el-table-column label="单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.orders" label="订单" min-width="160">
        <template #default="{ row }"><span class="code">{{ (row as MrpCalcRow).orderNos.join('、') }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.styles" label="款式" min-width="150">
        <template #default="{ row }"><span class="code">{{ (row as MrpCalcRow).styleCodes.join('、') }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.buy" label="要买" width="80" class-name="num" align="right">
        <template #default="{ row }">{{ row.buyCount }} 种</template>
      </el-table-column>
      <el-table-column v-if="cols.show.converted" label="转采购" width="112">
        <template #default="{ row }">
          <QtyProgress v-if="row.buyCount > 0" :done="row.convertedCount" :total="row.buyCount" />
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.needDate" label="要料日期" width="104" class-name="num">
        <template #default="{ row }">{{ row.needDate ? formatDate(row.needDate) : '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.creator" label="制单人" width="96">
        <template #default="{ row }">{{ row.createByName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.createDate" label="日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="136" fixed="right">
        <template #default="{ row }">
          <StatusTag :tone="MRP_STATUS[(row as MrpCalcRow).status].tone">{{ MRP_STATUS[(row as MrpCalcRow).status].label }}</StatusTag>
          <div v-if="row.nextCalcId"><router-link :to="`/mrp/${row.nextCalcId}`" class="next code" @click.stop>→ {{ row.nextCalcNo }}</router-link></div>
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

    <el-dialog v-model="reserveVisible" title="备料比" width="420px" :before-close="reserveGuard.beforeClose">
      <el-form label-position="top" class="form-grid">
        <el-form-item v-for="r in reserveRows" :key="r.materialType" :label="`${r.label}（%）`">
          <el-input-number v-model="r.pct" :min="0" :max="50" :precision="1" :controls="false" />
        </el-form-item>
      </el-form>
      <p class="dim small">物料上自己设了备料比的不看这里；只影响以后算的单</p>
      <template #footer>
        <el-button @click="reserveVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingReserve" @click="saveReserve">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pickVisible" title="选订单算料" width="680px">
      <div class="pick-search">
        <el-input v-model="orderKeyword" placeholder="订单号、款号" clearable @keyup.enter="searchOrders" @clear="searchOrders" />
        <el-button @click="searchOrders">查询</el-button>
      </div>
      <el-table v-loading="loadingOrders" :data="orderOptions" row-key="orderId" max-height="380" empty-text="没有能算料的订单：订单审核后才能算" @selection-change="picked = $event">
        <el-table-column type="selection" width="40" reserve-selection />
        <el-table-column label="订单号" prop="orderNo" width="150" class-name="code" />
        <el-table-column label="款式" min-width="140">
          <template #default="{ row }"><span class="code">{{ (row as MrpOrderOption).styleCodes.join('、') }}</span></template>
        </el-table-column>
        <el-table-column label="件数" width="80" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
        </el-table-column>
        <el-table-column label="承诺交期" width="100" class-name="num">
          <template #default="{ row }">{{ row.commitDate ? formatDate(row.commitDate) : '—' }}</template>
        </el-table-column>
        <el-table-column label="算过的" min-width="150">
          <template #default="{ row }">
            <span v-for="c in (row as MrpOrderOption).calcs" :key="c.id" class="calc code">{{ c.no }} {{ MRP_STATUS[c.status].label }}</span>
            <span v-if="!(row as MrpOrderOption).calcs.length" class="dim">没算过</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="orderPage" class="pagination" layout="total, prev, pager, next" :page-size="20" :total="orderTotal" @current-change="loadOrderOptions" />
      <template #footer>
        <el-button @click="pickVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!picked.length" @click="createCalc">算料{{ picked.length ? `（${picked.length} 张）` : '' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.next {
  font-size: 11.5px;
}

.small {
  margin: 0;
  font-size: 12px;
}

.pick-search {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.calc {
  display: inline-block;
  margin-right: 6px;
  font-size: 12px;
  color: var(--lm-pending-text);
}
</style>
