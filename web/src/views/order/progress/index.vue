<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { orderProgressApi } from '@/api/order'
import ThumbImage from '@/components/ThumbImage.vue'
import DateRange from '@/components/DateRange.vue'
import DictSelect from '@/components/DictSelect.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import LineSelect from '@/components/LineSelect.vue'
import { useProductLines } from '@/composables/useProductLines'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import NodeDots from '@/components/doc/NodeDots.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import type { OrderProgressCounts, OrderProgressQuery, OrderProgressRow, OrderProgressSegment, ProductionBrief } from '@/types/order'
import { PRODUCTION_STATUS, productionStatusOf } from '@/types/production'
import { KITTING_STATUS, type OrderKitting } from '@/types/purchase'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateRange, formatQty } from '@/utils/format'
/** 本人范围的人不出"我的 / 全部"（走查 T-8a56c5） */
const userScope = useUserStore()

/**
 * 订单进度总览（/order-progress，RFC 016 §1，docs/api/02 §3.15）：一行是一张在手订单（已审核、生产中）里的一个款，
 * 后面每张生产单一个小块（工厂 · 当前节点，颜色按它的风险）。排序后端按风险分数、交期排好，这里不再排。
 * 分段上的数另取，失败了只是不显示数
 */
const router = useRouter()
/** 齐料标签点了去算料单：没有算料权限的（开发）那个页面打不开，标签只看不点 */
const canOpenMrp = useUserStore().hasPerm('purchase:mrp:list')
const orderTypes = useDict('order_type')
const brands = useDict('brand')

const cols = useColumns('orderProgress', [
  { key: 'type', label: '品牌 / 类型' },
  { key: 'qty', label: '数量' },
  { key: 'dueDate', label: '交期' },
  { key: 'scheduled', label: '排产' },
  { key: 'kitting', label: '齐料' },
  { key: 'productions', label: '生产' },
  { key: 'returned', label: '回货' },
  { key: 'merch', label: '跟单' },
])

const counts = ref<OrderProgressCounts | null>(null)
const dueRange = ref<[string, string] | null>(null)
const salesName = ref<string | null>(null)
const merchName = ref<string | null>(null)

const countsRequest = useLatestRequest()
async function loadCounts(params: Omit<OrderProgressQuery, 'segment'>) {
  const read = countsRequest.begin()
  try {
    const result = await read.run(() => orderProgressApi.counts(params))
    if (read.current()) counts.value = result
  } catch {
    if (read.current()) counts.value = null
  }
}

// 几条品类线的人能按线筛（PRD §3.2；走查 T-120e64：这页原来漏了）
const lines = useProductLines()
const multiLine = lines.multi
const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: (params) => {
    const { segment: _segment, page: _page, size: _size, ...countsQuery } = params
    void loadCounts(countsQuery)
    return orderProgressApi.page(params)
  },
  query: {
    keyword: '',
    segment: 'ALL' as OrderProgressSegment,
    productLine: '',
    brand: '',
    orderType: '',
    salesId: undefined as string | null | undefined,
    merchId: undefined as string | null | undefined,
    dueDateFrom: undefined as string | undefined,
    dueDateTo: undefined as string | undefined,
    mine: false,
  },
})

const SEGMENTS: { value: OrderProgressSegment; label: string; count: keyof OrderProgressCounts }[] = [
  { value: 'ALL', label: '全部', count: 'total' },
  { value: 'UNSCHEDULED', label: '未排完', count: 'unscheduled' },
  { value: 'NEAR', label: '临期', count: 'near' },
  { value: 'LATE', label: '已逾期', count: 'late' },
  { value: 'PRODUCTION', label: '生产有风险', count: 'production' },
]
const segmentOptions = computed(() =>
  SEGMENTS.map((s) => {
    const n = counts.value?.[s.count]
    return { value: s.value, label: n ? `${s.label} ${n}` : s.label }
  }),
)

function onDueChange(val: [string, string] | null | undefined) {
  dueRange.value = val ?? null
  query.dueDateFrom = val?.[0]
  query.dueDateTo = val?.[1]
}

const filtered = computed(
  () => !!(query.keyword.trim() || query.segment !== 'ALL' || query.productLine || query.brand || query.orderType || query.salesId || query.merchId || query.dueDateFrom),
)
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的：换个分段，或者清空筛选'
  if (query.mine) return '没有你是制单人、业务员或跟单员的在手订单'
  return '没有在手的需求订单：订单审核以后才出现在这里，做完了就不再列'
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function openOrder(row: OrderProgressRow) {
  void router.push(`/orders/${row.orderId}`)
}

function openKitting(kit: OrderKitting) {
  const calcId = kit.calcIds[0]
  if (calcId) void router.push(`/mrp/${calcId}`)
}

function openProduction(p: ProductionBrief) {
  const to = docRoute({ type: 'PRODUCTION_ORDER', kind: p.kind, id: p.id, parentId: null })
  if (to) void router.push(to)
}

/** 小块的颜色：逾期、节点延期红，临期、有异常琥珀，做完绿，别的灰 */
function chipTone(p: ProductionBrief): string {
  if (p.risk.late || p.risk.nodeLate) return 'danger'
  if (p.risk.near || p.risk.issue) return 'pending'
  if (p.status === 'DONE' || p.status === 'CLOSED') return 'done'
  return ''
}

/** 小块上的字：有节点写当前节点，没有（草稿、待审核）写状态 */
function chipStep(p: ProductionBrief): string {
  if (p.nodes.length) return p.currentNodeName ?? '全部完成'
  return PRODUCTION_STATUS[p.status].label
}

function riskTags(row: OrderProgressRow): { label: string; tone: 'danger' | 'pending' }[] {
  const r = row.risk
  const tags: { label: string; tone: 'danger' | 'pending' }[] = []
  if (r.late) tags.push({ label: '已逾期', tone: 'danger' })
  if (r.nodeLate) tags.push({ label: '节点延期', tone: 'danger' })
  if (r.issue) tags.push({ label: '有异常', tone: 'pending' })
  if (r.near) tags.push({ label: '临期', tone: 'pending' })
  if (r.unscheduled) tags.push({ label: '未排完', tone: 'pending' })
  // 只因为生产单自己逾期、临期进了"生产有风险"的：也要写出原因
  if (r.production && !r.nodeLate && !r.issue) {
    if (row.productions.some((p) => p.risk.late)) tags.push({ label: '生产逾期', tone: 'danger' })
    else tags.push({ label: '生产临期', tone: 'pending' })
  }
  return tags
}
</script>

<template>
  <div>
    <PageHeader />
    <FilterBar v-model:keyword="query.keyword" placeholder="订单号、款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.segment" :options="segmentOptions" @change="search" />
      </template>
      <FilterItem v-if="multiLine" label="品类线" :text="lines.labelOf(query.productLine)" @clear="query.productLine = ''">
        <LineSelect v-model="query.productLine" clearable placeholder="全部品类线" />
      </FilterItem>
      <FilterItem label="品牌" :text="brands.labelOf(query.brand)" @clear="query.brand = ''">
        <DictSelect v-model="query.brand" type="brand" clearable placeholder="全部品牌" />
      </FilterItem>
      <FilterItem label="订单类型" :text="orderTypes.labelOf(query.orderType)" @clear="query.orderType = ''">
        <DictSelect v-model="query.orderType" type="order_type" clearable placeholder="全部类型" />
      </FilterItem>
      <FilterItem label="交期" :text="formatDateRange(dueRange)" @clear="onDueChange(null)">
        <DateRange :model-value="dueRange" @update:model-value="onDueChange" />
      </FilterItem>
      <FilterItem label="业务员" :text="query.salesId ? (salesName ?? '已选') : ''" @clear="query.salesId = undefined">
        <UserSelect v-model="query.salesId" v-model:label="salesName" clearable placeholder="全部业务员" />
      </FilterItem>
      <FilterItem label="跟单员" :text="query.merchId ? (merchName ?? '已选') : ''" @clear="query.merchId = undefined">
        <UserSelect v-model="query.merchId" v-model:label="merchName" clearable placeholder="全部跟单员" />
      </FilterItem>
      <template #right>
        <el-segmented v-if="userScope.canSeeAll" v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
      </template>
    </FilterBar>
    <el-table
      v-loading="loading"
      :data="list"
      :row-key="(r: OrderProgressRow) => `${r.orderId}-${r.styleId}`"
      row-class-name="clickable"
      scrollbar-always-on
      :empty-text="emptyText"
      @row-click="openOrder"
    >
      <el-table-column label="订单号" prop="orderNo" width="140" fixed="left" class-name="code" />
      <el-table-column label="款式" min-width="200">
        <template #default="{ row }">
          <span class="style-cell">
            <ThumbImage v-if="row.image" :src="row.image.thumbUrl" alt="" class="thumb" loading="lazy" />
            <span class="code">{{ row.styleCode }}</span>
            <span class="dim small">{{ row.styleName }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.type" label="品牌 / 类型" min-width="120">
        <template #default="{ row }">
          {{ row.brand ? brands.labelOf(row.brand) : '—' }}
          <span class="dim small"> {{ orderTypes.labelOf(row.orderType) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.qty" label="数量" width="84" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.qty) }}</template>
      </el-table-column>
      <!-- 日期 + 剩余天数、"部分到料 1/8"一行放下（走查 T-120e64：1280 宽时被截） -->
      <el-table-column v-if="cols.show.dueDate" label="交期" width="196" class-name="num">
        <template #default="{ row }">
          {{ formatDate(row.dueDate) }}
          <DaysLeftTag v-if="row.daysLeft != null" :days-left="row.daysLeft" />
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.scheduled" label="排产" width="112">
        <template #default="{ row }"><QtyProgress :done="row.scheduledQty" :total="row.qty" /></template>
      </el-table-column>
      <el-table-column v-if="cols.show.kitting" label="齐料" width="160">
        <template #default="{ row }">
          <button v-if="row.kitting" type="button" class="tag-btn" :disabled="!canOpenMrp || !row.kitting.calcIds.length" @click.stop="openKitting(row.kitting)">
            <StatusTag :tone="KITTING_STATUS[(row as OrderProgressRow).kitting!.status].tone">{{ row.kitting.label }}</StatusTag>
          </button>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.productions" label="生产" min-width="260" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <span v-if="!row.productions.length" class="dim">没排单</span>
          <span v-else class="chips">
            <el-tooltip v-for="p in (row as OrderProgressRow).productions" :key="p.id" effect="light" placement="top" :show-after="200">
              <template #content>
                <div class="chip-tip">
                  <div><span class="code">{{ p.no }}</span> · {{ productionStatusOf(p).label }}</div>
                  <NodeDots :nodes="p.nodes" :current-name="p.currentNodeName" />
                  <div>回货良品 {{ formatQty(p.returnedQty - p.defectQty) }} / {{ formatQty(p.totalQty) }}<template v-if="p.defectQty">（共回 {{ formatQty(p.returnedQty) }}，次品 {{ formatQty(p.defectQty) }}）</template></div>
                  <div>交期 {{ formatDate(p.expectDate ?? p.dueDate) }}</div>
                </div>
              </template>
              <button type="button" class="chip" :class="chipTone(p)" @click.stop="openProduction(p)">
                {{ p.factoryShortName }} · {{ chipStep(p) }}
              </button>
            </el-tooltip>
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.returned" label="回货" width="112">
        <template #default="{ row }">
          <QtyProgress v-if="row.scheduledQty > 0" :done="row.returnedQty" :total="row.qty" tone="done" />
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.merch" label="跟单" width="96">
        <template #default="{ row }">{{ row.merchName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="风险" width="216" fixed="right" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <span class="risks">
            <StatusTag v-for="t in riskTags(row as OrderProgressRow).slice(0, 2)" :key="t.label" :tone="t.tone">{{ t.label }}</StatusTag>
            <el-tooltip v-if="riskTags(row as OrderProgressRow).length > 2" placement="top">
              <template #content>{{ riskTags(row as OrderProgressRow).map((t) => t.label).join('、') }}</template>
              <span class="dim small">+{{ riskTags(row as OrderProgressRow).length - 2 }}</span>
            </el-tooltip>
            <span v-if="!riskTags(row as OrderProgressRow).length" class="dim">—</span>
          </span>
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
  </div>
</template>

<style scoped>
.style-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.thumb {
  width: 28px;
  height: 28px;
  flex: none;
  object-fit: cover;
  border-radius: 4px;
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

.chips {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 2px 0;
}

.chip {
  max-width: 180px;
  padding: 1px 8px;
  overflow: hidden;
  font: inherit;
  font-size: 12px;
  line-height: 20px;
  color: var(--lm-ink-2);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: var(--lm-greige-2);
  border: 1px solid var(--lm-line);
  border-radius: 10px;
}

.chip:hover {
  border-color: var(--lm-line-strong);
}

.chip.danger {
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

.chip.pending {
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border-color: var(--lm-pending-line);
}

.chip.done {
  color: var(--lm-done-text);
  background: var(--lm-done-bg);
  border-color: var(--lm-done-line);
}

.chip-tip {
  display: grid;
  gap: 4px;
}

.risks {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.small {
  font-size: 11.5px;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
