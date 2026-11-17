<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { orderChangeApi } from '@/api/order'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import { runDocAction } from '@/components/doc/docUtils'
import { useColumns } from '@/composables/useColumns'
import { useTable } from '@/composables/useTable'
import { CHANGE_TYPE, type ChangeStatus, type ChangeType, type OrderChangeQuery, type OrderChangeRow } from '@/types/order'
import { formatDateRange } from '@/utils/format'
import ChangeTable, { CHANGE_COLUMNS } from './ChangeTable.vue'

/**
 * 订单变更单列表（/order-changes，docs/api/02 §1.4、§3.14）。没有"新建"：变更从订单详情的"提变更"进来。
 * "已驳回"不是状态（库里是草稿）：发 status=DRAFT&rejected=true；"草稿"发 rejected=false（后端筛，分页才对）。
 * 认 ?status=（首页"待我审核"点进来），只认 /order-changes 自己的地址
 */
type TabKey = '' | 'PENDING' | 'REJECTED' | 'DRAFT' | 'APPROVED' | 'VOIDED'
const TAB_KEYS: TabKey[] = ['', 'PENDING', 'REJECTED', 'DRAFT', 'APPROVED', 'VOIDED']

const route = useRoute()
// 变更单号、行尾的审核按钮总在；审核人、费用说明、驳回原因默认不显示，在"显示列"里勾
const cols = useColumns('order-changes', CHANGE_COLUMNS)
const counts = ref<Partial<Record<ChangeStatus | 'REJECTED', number>>>({})
const dateRange = ref<[string, string] | null>(null)

const pickTab = (value: unknown): TabKey => (TAB_KEYS.includes(value as TabKey) ? (value as TabKey) : '')

let countSeq = 0
async function loadCounts(params: Omit<OrderChangeQuery, 'status' | 'rejected'>) {
  const current = ++countSeq
  try {
    const result = await orderChangeApi.counts(params)
    if (current === countSeq) counts.value = result
  } catch {
    if (current === countSeq) counts.value = {}
  }
}

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ tabKey, ...rest }) => {
    void loadCounts(rest)
    const draft = tabKey === 'REJECTED' || tabKey === 'DRAFT'
    return orderChangeApi.page({
      ...rest,
      status: tabKey ? [draft ? 'DRAFT' : (tabKey as ChangeStatus)] : undefined,
      rejected: draft ? tabKey === 'REJECTED' : undefined,
    })
  },
  query: {
    keyword: '',
    tabKey: pickTab(route.query.status),
    changeType: undefined as ChangeType | undefined,
    createDateFrom: undefined as string | undefined,
    createDateTo: undefined as string | undefined,
    mine: false,
  },
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== '/order-changes') return
    const tab = pickTab(value)
    if (tab !== query.tabKey) {
      query.tabKey = tab
      search()
    }
  },
)

const tabOptions = computed(() => {
  const c = counts.value
  const rejected = c.REJECTED ?? 0
  const label = (text: string, n: number | undefined) => (n ? `${text} ${n}` : text)
  return [
    { label: '全部', value: '' },
    { label: label('待审核', c.PENDING), value: 'PENDING' },
    { label: label('已驳回', rejected), value: 'REJECTED' },
    { label: label('草稿', Math.max(0, (c.DRAFT ?? 0) - rejected)), value: 'DRAFT' },
    { label: label('已审核', c.APPROVED), value: 'APPROVED' },
    { label: label('已作废', c.VOIDED), value: 'VOIDED' },
  ]
})

const CHANGE_TYPE_OPTIONS = Object.entries(CHANGE_TYPE).map(([value, label]) => ({ value: value as ChangeType, label }))

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.createDateFrom = val?.[0]
  query.createDateTo = val?.[1]
}

const filtered = computed(() => !!(query.keyword.trim() || query.tabKey || query.changeType || query.createDateFrom))
const emptyText = computed(() => {
  if (query.mine && !filtered.value) return '还没有你提的变更单，切到「全部」看所有人的'
  return '没有符合条件的变更单。已审核的订单在详情页「提变更」'
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

/** 行尾的审核、驳回：列表行上没有 version，先取一次详情 */
async function quickAct(row: OrderChangeRow, action: 'audit' | 'reject') {
  const detail = await orderChangeApi.get(row.id)
  const ok = await runDocAction(action, `变更单 ${row.no}`, detail.version, (a, body, o) => orderChangeApi.act(row.id, a, body, o), {
    reload: load,
  })
  if (ok) await load()
}
</script>

<template>
  <div>
    <PageHeader />

    <FilterBar v-model:keyword="query.keyword" placeholder="变更单号、订单号、SKU 码" batch="单号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.tabKey" :options="tabOptions" @change="search" />
      </template>
      <FilterItem label="变更类型" :text="query.changeType ? CHANGE_TYPE[query.changeType] : ''" @clear="query.changeType = undefined">
        <el-select v-model="query.changeType" filterable clearable placeholder="全部类型" :value-on-clear="undefined">
          <el-option v-for="o in CHANGE_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterItem>
      <FilterItem label="制单日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <template #right>
        <el-segmented v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
      </template>
    </FilterBar>

    <ChangeTable :rows="list" :loading="loading" :columns="cols" :empty-text="emptyText" @act="quickAct" />

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />
  </div>
</template>
