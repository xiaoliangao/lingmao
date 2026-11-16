<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { boardApi } from '@/api/production'
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
import type { BoardCardKey, BoardCards, BoardFactoryRow, BoardQuery, ProductionKind } from '@/types/production'
import { formatDateRange, formatQty } from '@/utils/format'
import ProductionTable, { productionColumns } from '../order/ProductionTable.vue'

/**
 * 生产看板（/board，docs/api/05 §1.4、§3.7）。
 * - 数字卡、按单列表、按工厂三份数分开取：卡片取不到就不显示卡片，列表照常
 * - 点卡片筛下面的列表，再点一次取消；卡片的数不受选中的卡片影响
 * - 认 ?card=（首页点进来）：只认 /board 自己的地址。离开看板时地址变了不能把选中的卡片清掉
 * - 按单的表格和外发加工单列表共用 ProductionTable（mode="board"），"显示列"只管按单的表格
 * - 按工厂：点一行把这家筛进去、切回按单
 */
const CARD_KEYS: BoardCardKey[] = ['RUNNING', 'WAITING', 'NEAR', 'LATE', 'NODE_LATE', 'ISSUE']
const KIND_OPTIONS = [
  { label: '全部', value: '' },
  { label: '外发加工', value: 'OUTSOURCE' },
  { label: '成品采购', value: 'BUY' },
]
const VIEW_OPTIONS = [
  { label: '按单', value: 'orders' },
  { label: '按工厂', value: 'factories' },
]
const MINE_OPTIONS = [
  { label: '我的', value: true },
  { label: '全部', value: false },
]
type View = 'orders' | 'factories'

const route = useRoute()
const brands = useDict('brand')
const cols = useColumns('board', productionColumns('board'))

const pickCard = (v: unknown): BoardCardKey | undefined => (CARD_KEYS.includes(v as BoardCardKey) ? (v as BoardCardKey) : undefined)

const viewMode = ref<View>('orders')
const activeCard = ref<BoardCardKey | undefined>(pickCard(route.query.card))
const cards = ref<BoardCards | null>(null)
const factories = ref<BoardFactoryRow[]>([])
const loadingFactories = ref(false)
const dateRange = ref<[string, string] | null>(null)
const factoryName = ref<string | null>(null)
const merchName = ref<string | null>(null)

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ kindTab, page, size, ...rest }) => {
    const filter: BoardQuery = { ...rest, kind: kindTab || undefined }
    void loadCards(filter)
    if (viewMode.value === 'factories') void loadFactories(filter)
    return boardApi.page({ ...filter, page, size, card: activeCard.value })
  },
  query: {
    kindTab: '' as '' | ProductionKind,
    factoryId: undefined as number | null | undefined,
    merchId: undefined as number | null | undefined,
    brand: '',
    dueDateFrom: undefined as string | undefined,
    dueDateTo: undefined as string | undefined,
    mine: false,
  },
})

function currentFilter(): BoardQuery {
  const { kindTab, ...rest } = query
  return { ...rest, kind: kindTab || undefined }
}

/** 卡片：失败不提示，不显示那一排 */
let cardSeq = 0
async function loadCards(filter: BoardQuery) {
  const seq = ++cardSeq
  try {
    const c = await boardApi.cards(filter)
    if (seq === cardSeq) cards.value = c
  } catch {
    if (seq === cardSeq) cards.value = null
  }
}

let factorySeq = 0
async function loadFactories(filter: BoardQuery = currentFilter()) {
  const seq = ++factorySeq
  loadingFactories.value = true
  try {
    // 按工厂不看加工厂筛选（点一行才筛进去）
    const rest = { ...filter }
    delete rest.factoryId
    const rows = await boardApi.factories(rest)
    if (seq === factorySeq) factories.value = rows
  } finally {
    if (seq === factorySeq) loadingFactories.value = false
  }
}

watch(
  () => route.query.card,
  (c) => {
    // 离开看板（去详情、别的页签）时 route 也会变，不管
    if (route.path !== '/board') return
    const next = pickCard(c)
    if (next && next !== activeCard.value) {
      activeCard.value = next
      viewMode.value = 'orders'
      search()
    }
  },
)

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function toggleCard(key: BoardCardKey) {
  activeCard.value = activeCard.value === key ? undefined : key
  viewMode.value = 'orders'
  search()
}

function onViewChange(v: View) {
  viewMode.value = v
  if (v === 'factories') void loadFactories()
}

function pickFactory(f: BoardFactoryRow) {
  query.factoryId = f.factoryId
  factoryName.value = f.factoryShortName
  viewMode.value = 'orders'
  search()
}

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.dueDateFrom = val?.[0]
  query.dueDateTo = val?.[1]
}

const maxWipQty = computed(() => Math.max(1, ...factories.value.map((f) => f.wipQty)))
const barWidth = (f: BoardFactoryRow) => `${Math.round((f.wipQty / maxWipQty.value) * 100)}%`

const filtered = computed(() => !!(query.kindTab || query.factoryId || query.merchId || query.brand || query.dueDateFrom || activeCard.value))
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的在制单：换张卡片，或者清空筛选'
  if (query.mine) return '你跟的单里没有在做的，切到「全部」看所有人的'
  return '没有在做的单：生产单审核下单后出现在这里'
})
</script>

<template>
  <div>
    <PageHeader title="看板" />

    <FilterBar :columns="viewMode === 'orders' ? cols : undefined" @search="search">
      <template #left>
        <el-segmented v-model="query.kindTab" :options="KIND_OPTIONS" @change="search" />
      </template>
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
        <el-segmented v-model="query.mine" :options="MINE_OPTIONS" @change="search" />
      </template>
    </FilterBar>

    <section v-if="cards" class="board-cards">
      <button type="button" class="b-card" :class="{ active: activeCard === 'RUNNING' }" @click="toggleCard('RUNNING')">
        <span class="lbl">在制</span>
        <strong class="num">{{ cards.runningCount }}</strong>
        <span class="sub num">{{ formatQty(cards.runningQty) }} 件</span>
      </button>
      <button type="button" class="b-card" :class="{ active: activeCard === 'WAITING' }" @click="toggleCard('WAITING')">
        <span class="lbl">待开工</span>
        <strong class="num">{{ cards.waitingCount }}</strong>
      </button>
      <button type="button" class="b-card" :class="{ active: activeCard === 'NEAR' }" @click="toggleCard('NEAR')">
        <span class="lbl">{{ cards.nearDays }} 天内交货</span>
        <strong class="num">{{ cards.nearCount }}</strong>
      </button>
      <button type="button" class="b-card" :class="{ active: activeCard === 'LATE', danger: cards.lateCount > 0 }" @click="toggleCard('LATE')">
        <span class="lbl">已逾期</span>
        <strong class="num">{{ cards.lateCount }}</strong>
      </button>
      <button type="button" class="b-card" :class="{ active: activeCard === 'NODE_LATE', warn: cards.nodeLateCount > 0 }" @click="toggleCard('NODE_LATE')">
        <span class="lbl">节点延期</span>
        <strong class="num">{{ cards.nodeLateCount }}</strong>
      </button>
      <button type="button" class="b-card" :class="{ active: activeCard === 'ISSUE', danger: cards.issueCount > 0 }" @click="toggleCard('ISSUE')">
        <span class="lbl">有异常</span>
        <strong class="num">{{ cards.issueCount }}</strong>
      </button>
      <div class="b-card static">
        <span class="lbl">本月回货</span>
        <strong class="num">{{ formatQty(cards.monthReturnedQty) }}</strong>
        <span class="sub">件</span>
      </div>
    </section>

    <div class="view-bar">
      <el-segmented :model-value="viewMode" :options="VIEW_OPTIONS" @change="onViewChange" />
    </div>

    <template v-if="viewMode === 'orders'">
      <ProductionTable mode="board" :rows="list" :loading="loading" :columns="cols" :empty-text="emptyText" />
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        class="pagination"
        layout="total, sizes, prev, pager, next"
        :page-sizes="pageSizes"
        :total="total"
      />
    </template>

    <el-table
      v-else
      v-loading="loadingFactories"
      :data="factories"
      row-key="factoryId"
      row-class-name="clickable"
      empty-text="还没有合作中的成衣厂、加工厂"
      @row-click="pickFactory"
    >
      <el-table-column label="加工厂" prop="factoryShortName" min-width="140" />
      <el-table-column label="类型" width="150">
        <template #default="{ row }">{{ (row as BoardFactoryRow).categoryNames.join('、') }}</template>
      </el-table-column>
      <el-table-column label="在制单数" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ row.wipCount }}</template>
      </el-table-column>
      <el-table-column label="在制件数" min-width="240">
        <template #default="{ row }">
          <div class="hbar-row num">
            <span class="hbar"><i :style="{ width: barWidth(row as BoardFactoryRow) }" /></span>
            <span>{{ formatQty(row.wipQty) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="逾期单" width="90" class-name="num" align="right">
        <template #default="{ row }">
          <strong v-if="row.lateCount > 0" class="danger-txt">{{ row.lateCount }}</strong>
          <span v-else class="dim">0</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.board-cards {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 10px;
  padding: 12px 20px;
  background: var(--lm-greige-2);
  border-bottom: 1px solid var(--lm-line-soft);
}

.b-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
  transition: border-color 0.14s var(--lm-ease);
}

.b-card:not(.static):hover {
  border-color: var(--lm-line-strong);
}

.b-card.active {
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent);
}

.b-card.static {
  cursor: default;
  background: var(--lm-subtle);
}

.b-card .lbl {
  font-size: 12px;
  color: var(--lm-muted);
}

.b-card strong {
  font-size: 22px;
  line-height: 1.2;
  color: var(--lm-ink);
}

.b-card.warn strong {
  color: var(--lm-pending-text);
}

.b-card.danger strong {
  color: var(--lm-danger-text);
}

.b-card .sub {
  font-size: 11.5px;
  color: var(--lm-muted);
}

.view-bar {
  padding: 10px 20px 0;
}

.danger-txt {
  color: var(--lm-danger-text);
}

.hbar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hbar {
  flex: 1;
  max-width: 220px;
  height: 8px;
  overflow: hidden;
  background: var(--lm-sunken);
  border-radius: 99px;
}

.hbar i {
  display: block;
  height: 100%;
  background: var(--lm-accent);
  border-radius: 99px;
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}

@media (max-width: 1120px) {
  .board-cards {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .board-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
