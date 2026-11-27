<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reportApi } from '@/api/report'
import BlankState from '@/components/BlankState.vue'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import LineSelect from '@/components/LineSelect.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useProductLines } from '@/composables/useProductLines'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import { ORDER_STATUS, type OrderStatus } from '@/types/order'
import type { PageParams } from '@/api/system'
import type { OrderMarginPage, OrderMarginQuery, OrderMarginRow } from '@/types/report'
import { formatMoney, formatDate, formatDateRange, formatQty } from '@/utils/format'
import { PERIOD_OPTIONS, periodRange, type Period } from '@/utils/reportPeriod'

/**
 * 订单毛利（/order-margin，RFC 019 §3.4，甲方 11-27 答 Q66）：按下单日期筛需求订单，
 * 销售额 = Σ 数量 × 单价，成本 = Σ 数量 × 资料包核价的单件成本（估算，不是实际耗料）。
 * 要同时能看订单价格和成本，少一个就不查
 */
const router = useRouter()
const user = useUserStore()
const lines = useProductLines()
const allowed = computed(() => user.hasPerm('order:price:query') && user.hasPerm('techpack:cost:query'))
const totals = ref<Omit<OrderMarginPage, 'list' | 'total'> | null>(null)

const { query, page, size, pageSizes, list, total, loading, search } = useTable({
  fetch: async (params: PageParams & OrderMarginQuery) => {
    const r = await reportApi.orderMargins(params)
    totals.value = r
    return r
  },
  query: { period: 'LAST_WEEK' as Period, range: periodRange('LAST_WEEK') as [string, string] | null, keyword: '', productLine: '' },
  normalize: ({ period, range, ...rest }) => {
    const r = period === 'CUSTOM' ? range : periodRange(period ?? 'LAST_WEEK')
    return { ...rest, from: r?.[0] ?? '', to: r?.[1] ?? '' }
  },
  immediate: false,
})
onActivated(() => {
  if (allowed.value) search()
})

function onPeriod(p: Period) {
  query.period = p
  if (p !== 'CUSTOM') query.range = periodRange(p)
  search()
}
function onRange() {
  query.period = 'CUSTOM'
  search()
}
const rangeText = computed(() => formatDateRange(query.period === 'CUSTOM' ? query.range : periodRange(query.period)))
const pct = (v: number | null) => (v == null ? '—' : `${v}%`)
const foreign = (r: OrderMarginRow) => !!r.currency && r.currency !== 'CNY'
const marginClass = (v: number | null) => (v != null && v < 0 ? 'neg' : '')
</script>

<template>
  <div>
    <PageHeader sub="成本按资料包核价估：单件成本 × 件数" />
    <div v-if="!allowed" class="page">
      <BlankState title="看订单毛利要同时有「看订单价格」和「看成本」两个权限，找管理员开" />
    </div>
    <template v-else>
      <FilterBar v-model:keyword="query.keyword" placeholder="订单号、客户订单号、品牌" @search="search">
        <template #left>
          <el-segmented :model-value="query.period" :options="PERIOD_OPTIONS" @change="(p: Period) => onPeriod(p)" />
        </template>
        <FilterItem label="下单日期" :text="query.period === 'CUSTOM' ? rangeText : ''" @clear="onPeriod('LAST_WEEK')">
          <DateRange v-model="query.range" :clearable="false" @change="onRange" />
        </FilterItem>
        <FilterItem v-if="lines.multi.value" label="品类线" :text="lines.labelOf(query.productLine)" @clear="query.productLine = ''">
          <LineSelect v-model="query.productLine" clearable placeholder="全部品类线" />
        </FilterItem>
      </FilterBar>

      <div v-if="totals" class="summary">
        <span>{{ rangeText }} 下的 <b class="num">{{ total }}</b> 张单：</span>
        <span>销售额 <b class="num">{{ formatMoney(totals.salesAmount) }}</b></span>
        <span>成本 <b class="num">{{ formatMoney(totals.costAmount) }}</b></span>
        <span>毛利 <b class="num" :class="marginClass(totals.margin)">{{ formatMoney(totals.margin) }}</b>
          <span class="dim">（{{ pct(totals.marginRate) }}）</span></span>
        <span v-if="totals.foreignOrders" class="dim">{{ totals.foreignOrders }} 张外币单没有汇率，不算毛利、不进合计</span>
        <StatusTag v-if="totals.costMissingOrders" tone="pending">{{ totals.costMissingOrders }} 张单有款算不出成本，毛利偏高</StatusTag>
      </div>

      <el-table v-loading="loading" :data="list" row-key="id" scrollbar-always-on
        :empty-text="query.keyword || query.productLine ? '没有符合条件的订单' : '这段时间没有审核过的需求订单'"
        @row-click="(row: OrderMarginRow) => router.push(`/orders/${row.id}`)">
        <el-table-column label="订单号" width="150" fixed="left">
          <template #default="{ row }"><span class="code">{{ (row as OrderMarginRow).no }}</span></template>
        </el-table-column>
        <el-table-column label="下单日期" width="100">
          <template #default="{ row }">{{ formatDate((row as OrderMarginRow).orderDate) }}</template>
        </el-table-column>
        <el-table-column label="品牌" prop="brand" min-width="100" />
        <el-table-column v-if="lines.multi.value" label="品类线" width="90">
          <template #default="{ row }">{{ lines.labelOf((row as OrderMarginRow).productLine) }}</template>
        </el-table-column>
        <el-table-column label="件数" width="90" class-name="num" align="right">
          <template #default="{ row }">{{ formatQty((row as OrderMarginRow).totalQty) }}</template>
        </el-table-column>
        <el-table-column label="销售额" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ foreign(row as OrderMarginRow) ? `${(row as OrderMarginRow).currency} ` : '' }}{{ formatMoney((row as OrderMarginRow).salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="成本" width="120" class-name="num" align="right">
          <template #default="{ row }">
            {{ formatMoney((row as OrderMarginRow).costAmount) }}
            <el-tooltip v-if="(row as OrderMarginRow).costMissingQty" :content="`${formatQty((row as OrderMarginRow).costMissingQty)} 件算不出成本（资料包没锁版或核价不全），没算进去`">
              <el-icon class="warn"><Warning /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="毛利" width="120" class-name="num" align="right">
          <template #default="{ row }">
            <span v-if="(row as OrderMarginRow).margin == null" class="dim" title="外币单没有汇率，不算毛利">外币单</span>
            <span v-else :class="marginClass((row as OrderMarginRow).margin)">{{ formatMoney((row as OrderMarginRow).margin) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" width="90" class-name="num" align="right">
          <template #default="{ row }">{{ pct((row as OrderMarginRow).marginRate) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" fixed="right">
          <template #default="{ row }">
            <StatusTag :tone="ORDER_STATUS[(row as OrderMarginRow).status as OrderStatus]?.tone ?? 'off'">
              {{ ORDER_STATUS[(row as OrderMarginRow).status as OrderStatus]?.label ?? (row as OrderMarginRow).status }}
            </StatusTag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination"
        layout="total, sizes, prev, pager, next" :page-sizes="pageSizes" :total="total" />
    </template>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  align-items: center;
  padding: 10px 0 12px;
  font-size: 13px;
}

.neg {
  color: var(--lm-danger-text);
}

.warn {
  margin-left: 4px;
  color: var(--lm-pending-text);
  vertical-align: -2px;
}
</style>
