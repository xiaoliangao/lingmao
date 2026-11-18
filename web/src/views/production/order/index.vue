<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { productionApi } from '@/api/production'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import DictSelect from '@/components/DictSelect.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import {
  PRODUCTION_KIND,
  PRODUCTION_RISK_LABEL,
  PRODUCTION_STATUS,
  type ProductionCounts,
  type ProductionKind,
  type ProductionQuery,
  type ProductionRiskKey,
  type ProductionRow,
  type ProductionStatus,
} from '@/types/production'
import { formatDateRange } from '@/utils/format'
import ProductionTable, { productionColumns } from './ProductionTable.vue'

/**
 * 外发加工单 / 成品采购单列表（/production、/fp-purchases，docs/api/05 §1.4）。两个菜单共用这个文件、各有一个缓存实例，
 * 种类在 setup 时按地址定死（keep-alive 下 route 会变成别的页，用 computed 跟着变就错了）。
 * - 认 ?status=PENDING、?risk=（首页、看板点进来），只认本页自己的地址
 * - 状态页签上的数单独取，取不到就不显示数，不拖住表格；"生产中" = RUNNING − PARTIAL，"全部"不加 PARTIAL
 * - 没有"新建"：生产单只能在需求订单详情里排单生成
 */
type TabKey = '' | Exclude<ProductionStatus, 'RUNNING'> | 'RUNNING_PURE' | 'PARTIAL'
const TAB_KEYS: Exclude<TabKey, ''>[] = ['DRAFT', 'PENDING', 'APPROVED', 'RUNNING_PURE', 'PARTIAL', 'DONE', 'CLOSED', 'VOIDED']
const RISK_OPTIONS = Object.entries(PRODUCTION_RISK_LABEL).map(([value, label]) => ({ value: value as ProductionRiskKey, label }))
const MINE_OPTIONS = [
  { label: '我的', value: true },
  { label: '全部', value: false },
]

const route = useRoute()
const brands = useDict('brand')

const kind: ProductionKind = route.path.startsWith(PRODUCTION_KIND.BUY.path) ? 'BUY' : 'OUTSOURCE'
const kindMeta = PRODUCTION_KIND[kind]
// 两个菜单各记各的列（订单号、单价、次品默认不显示）
const cols = useColumns(kind === 'BUY' ? 'fp-purchases' : 'production', productionColumns('list'))

const pickTab = (v: unknown): TabKey => (TAB_KEYS.includes(v as Exclude<TabKey, ''>) ? (v as TabKey) : '')
const pickRisk = (v: unknown): ProductionRiskKey | undefined =>
  typeof v === 'string' && v in PRODUCTION_RISK_LABEL ? (v as ProductionRiskKey) : undefined

const counts = ref<ProductionCounts | null>(null)
const dateRange = ref<[string, string] | null>(null)
const factoryName = ref<string | null>(null)
const merchName = ref<string | null>(null)

type ListQuery = Omit<ProductionQuery, 'kind' | 'status' | 'returned' | 'ids'>

/** 页签上的数：和表格分开取，失败不提示（页签只是少了数） */
let countSeq = 0
async function loadCounts(params: ListQuery) {
  const seq = ++countSeq
  try {
    const c = await productionApi.counts({ ...params, kind })
    if (seq === countSeq) counts.value = c
  } catch {
    if (seq === countSeq) counts.value = null
  }
}

function statusParams(tab: TabKey): Pick<ProductionQuery, 'status' | 'returned'> {
  if (tab === 'RUNNING_PURE') return { status: ['RUNNING'], returned: false }
  if (tab === 'PARTIAL') return { status: ['RUNNING'], returned: true }
  return tab ? { status: [tab] } : {}
}

const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile, exportSelected } = useTable({
  normalize: ({ tabKey, ...rest }) => ({ ...rest, kind, ...statusParams(tabKey ?? '') }),
  fetch: (params) => {
    const { status: _status, returned: _returned, page: _page, size: _size, ...countsQuery } = params
    void loadCounts(countsQuery)
    return productionApi.page(params)
  },
  query: {
    keyword: '',
    tabKey: pickTab(route.query.status),
    risk: pickRisk(route.query.risk),
    factoryId: undefined as string | null | undefined,
    merchId: undefined as string | null | undefined,
    brand: '',
    dueDateFrom: undefined as string | undefined,
    dueDateTo: undefined as string | undefined,
    mine: false,
    auditForMe: route.query.auditForMe === '1',
  },
  exportUrl: '/api/production-orders/export',
  exportName: `${kindMeta.label}.xlsx`,
})

// keep-alive：从首页、看板再点进来时地址参数变了，重新筛；别的页面的地址变化不管
watch(
  () => [route.query.status, route.query.risk, route.query.auditForMe],
  () => {
    if (route.path !== kindMeta.path) return
    const tab = pickTab(route.query.status)
    const risk = pickRisk(route.query.risk)
    const auditForMe = route.query.auditForMe === '1'
    if (tab !== query.tabKey || risk !== query.risk || auditForMe !== query.auditForMe) {
      query.auditForMe = auditForMe
      query.tabKey = tab
      query.risk = risk
      search()
    }
  },
)

const tabOptions = computed(() => {
  const c = counts.value
  const fmt = (label: string, n: number | undefined) => (n ? `${label} ${n}` : label)
  if (!c) return [{ label: '全部', value: '' }, ...TAB_KEYS.map((k) => ({ label: tabLabel(k), value: k }))]
  const all = (['DRAFT', 'PENDING', 'APPROVED', 'RUNNING', 'DONE', 'CLOSED', 'VOIDED'] as ProductionStatus[]).reduce((s, k) => s + (c[k] ?? 0), 0)
  const partial = c.PARTIAL ?? 0
  return [
    { label: fmt('全部', all), value: '' },
    ...TAB_KEYS.map((k) => {
      const n = k === 'RUNNING_PURE' ? Math.max(0, (c.RUNNING ?? 0) - partial) : k === 'PARTIAL' ? partial : c[k]
      return { label: fmt(tabLabel(k), n), value: k }
    }),
  ]
})

function tabLabel(k: TabKey): string {
  if (k === 'RUNNING_PURE') return '生产中'
  if (k === 'PARTIAL') return '部分回货'
  return k ? PRODUCTION_STATUS[k].label : '全部'
}

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.dueDateFrom = val?.[0]
  query.dueDateTo = val?.[1]
}

const filtered = computed(
  () =>
    !!(query.keyword.trim() || query.tabKey || query.risk || query.factoryId || query.merchId || query.brand || query.dueDateFrom),
)
const emptyText = computed(() => {
  if (filtered.value) return `没有符合条件的${kindMeta.label}：换个状态，或者清空筛选`
  if (query.mine) return `还没有你跟单、你做的${kindMeta.label}，切到「全部」看所有人的`
  const tail = kind === 'BUY' ? '，排单时加工方式选包工包料' : ''
  return `还没有${kindMeta.label}。需求订单审核后，在订单详情里点「排单」生成${tail}`
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

// ---------- 勾选与批量 ----------
const tableRef = ref<InstanceType<typeof ProductionTable>>()
const selected = ref<ProductionRow[]>([])

const batchActions: BatchAction<ProductionRow>[] = [
  {
    label: '提交',
    perm: 'production:order:submit',
    can: (r) => r.actions.includes('submit'),
    run: (r, o) => productionApi.act(r.id, 'submit', { version: r.version }, o),
  },
  {
    label: '审核',
    can: (r) => r.actions.includes('audit'),
    confirm: '单价和价格档案不一样的单，审核后记进价格档案；审核后按交期倒排进度节点。',
    run: (r, o) => productionApi.act(r.id, 'audit', { version: r.version }, o),
  },
]

const exportAll = () => exportFile()
const exportPicked = () => exportSelected(selected.value.map((r) => r.id))
</script>

<template>
  <div>
    <PageHeader :title="kindMeta.label">
      <template #actions>
        <el-button v-perm="'production:order:export'" icon="Download" :loading="exporting" @click="exportAll">导出</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="单号、订单号、款号、加工厂" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.tabKey" :options="tabOptions" @change="search" />
      </template>
      <FilterItem label="风险" :text="query.risk ? PRODUCTION_RISK_LABEL[query.risk] : ''" @clear="query.risk = undefined">
        <el-select v-model="query.risk" filterable clearable placeholder="全部" :value-on-clear="undefined">
          <el-option v-for="o in RISK_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </FilterItem>
      <FilterItem label="交期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <FilterItem label="加工厂" :text="query.factoryId ? (factoryName ?? '已选') : ''" @clear="query.factoryId = undefined">
        <SupplierSelect v-model="query.factoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" clearable placeholder="全部加工厂" />
      </FilterItem>
      <FilterItem label="生产跟单" :text="query.merchId ? (merchName ?? '已选') : ''" @clear="query.merchId = undefined">
        <UserSelect v-model="query.merchId" v-model:label="merchName" clearable placeholder="全部跟单" />
      </FilterItem>
      <FilterItem label="品牌" :text="brands.labelOf(query.brand)" @clear="query.brand = ''">
        <DictSelect v-model="query.brand" type="brand" clearable />
      </FilterItem>
      <template #right>
        <el-checkbox v-model="query.auditForMe" data-testid="audit-for-me-toggle" @change="search">待我审批</el-checkbox>
        <el-segmented :disabled="query.auditForMe" v-model="query.mine" :options="MINE_OPTIONS" @change="search" />
      </template>
    </FilterBar>

    <ProductionTable
      ref="tableRef"
      mode="list"
      :rows="list"
      :loading="loading"
      :columns="cols"
      :empty-text="emptyText"
      @selection-change="selected = $event"
    />

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />

    <BatchBar :rows="selected" :table="tableRef" unit="张" :actions="batchActions" @done="load">
      <el-button v-perm="'production:order:export'" size="small" :loading="exporting" @click="exportPicked">导出选中</el-button>
    </BatchBar>
  </div>
</template>
