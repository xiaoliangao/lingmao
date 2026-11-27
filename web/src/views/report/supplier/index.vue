<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { reportApi } from '@/api/report'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import type { FactoryPerfRow, PerfResult, PurchasePerfRow } from '@/types/report'
import { formatDateRange, formatQty } from '@/utils/format'
import { PERIOD_OPTIONS, periodRange, rateTone, type Period } from '@/utils/reportPeriod'

/**
 * 供应商绩效（/supplier-performance，RFC 019 §3.1，甲方 11-27 答 Q66、Q53）：
 * - 物料供应商：采购到货及时率，按采购行，到齐那天不晚于约定交期算准时
 * - 加工厂：交期达成率（按单、按件）、次品率（这段时间里的回货）
 * 交期在这段时间里、还没到期也没到齐的不算，单列"还没到期"
 */
type View = 'materials' | 'factories'
const view = ref<View>('materials')
const query = reactive({ period: 'LAST_WEEK' as Period, range: periodRange('LAST_WEEK') as [string, string] | null, keyword: '' })
const loading = ref(false)
const purchase = ref<PerfResult<PurchasePerfRow> | null>(null)
const factory = ref<PerfResult<FactoryPerfRow> | null>(null)
const reader = useLatestRequest()

async function load() {
  const range = query.period === 'CUSTOM' ? query.range : periodRange(query.period)
  if (!range) return
  const params = { from: range[0], to: range[1], keyword: query.keyword || undefined }
  const read = reader.begin()
  loading.value = true
  try {
    if (view.value === 'materials') {
      const r = await read.run(() => reportApi.purchasePerf(params))
      if (read.current()) purchase.value = r
    } else {
      const r = await read.run(() => reportApi.factoryPerf(params))
      if (read.current()) factory.value = r
    }
  } finally {
    if (read.current()) loading.value = false
  }
}
onActivated(load)

function onPeriod(p: Period) {
  query.period = p
  if (p !== 'CUSTOM') query.range = periodRange(p)
  void load()
}
function onRange() {
  query.period = 'CUSTOM'
  void load()
}
function switchView(v: View) {
  view.value = v
  void load()
}

const rangeText = computed(() => formatDateRange(query.period === 'CUSTOM' ? query.range : periodRange(query.period)))
const pct = (v: number | null) => (v == null ? '—' : `${v}%`)
const emptyText = computed(() =>
  query.keyword ? '没有符合条件的供应商' : view.value === 'materials' ? '这段时间里没有到交期的采购行' : '这段时间里没有到交期的生产单、也没有回货',
)
</script>

<template>
  <div>
    <PageHeader sub="按约定交期算：到齐（回齐）那天不晚于交期就是准时">
      <template #actions>
        <el-segmented :model-value="view" :options="[{ label: '物料供应商', value: 'materials' }, { label: '加工厂', value: 'factories' }]"
          @change="(v: View) => switchView(v)" />
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="供应商编码、简称" @search="load">
      <template #left>
        <el-segmented :model-value="query.period" :options="PERIOD_OPTIONS" @change="(p: Period) => onPeriod(p)" />
      </template>
      <FilterItem label="交期" :text="query.period === 'CUSTOM' ? rangeText : ''" @clear="onPeriod('LAST_WEEK')">
        <DateRange v-model="query.range" :clearable="false" @change="onRange" />
      </FilterItem>
    </FilterBar>

    <template v-if="view === 'materials'">
      <div v-if="purchase" class="summary">
        <span>{{ rangeText }} 交期的采购行：</span>
        <span>算进来 <b class="num">{{ purchase.total.lineCount }}</b> 行，准时 <b class="num">{{ purchase.total.onTimeCount }}</b>，
          逾期 <b class="num">{{ purchase.total.lateCount }}</b></span>
        <span>及时率 <StatusTag v-if="rateTone(purchase.total.onTimeRate, 95, 80)" :tone="rateTone(purchase.total.onTimeRate, 95, 80) || undefined">{{ pct(purchase.total.onTimeRate) }}</StatusTag>
          <b v-else class="num">{{ pct(purchase.total.onTimeRate) }}</b></span>
        <span v-if="purchase.total.avgLateDays != null" class="dim">逾期的平均晚 {{ purchase.total.avgLateDays }} 天</span>
        <span v-if="purchase.total.pendingCount" class="dim">还没到期的 {{ purchase.total.pendingCount }} 行不算</span>
      </div>
      <el-table v-loading="loading" :data="purchase?.rows ?? []" row-key="supplierId" scrollbar-always-on :empty-text="emptyText">
        <el-table-column label="供应商" min-width="160" fixed="left">
          <template #default="{ row }">
            <span>{{ (row as PurchasePerfRow).supplierShortName }}</span>
            <span class="code dim"> {{ (row as PurchasePerfRow).supplierCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="算进来的行" prop="lineCount" width="110" class-name="num" />
        <el-table-column label="准时" prop="onTimeCount" width="80" class-name="num" />
        <el-table-column label="逾期" prop="lateCount" width="80" class-name="num" />
        <el-table-column label="及时率" width="100" class-name="num">
          <template #default="{ row }">
            <StatusTag v-if="rateTone((row as PurchasePerfRow).onTimeRate, 95, 80)" :tone="rateTone((row as PurchasePerfRow).onTimeRate, 95, 80) || undefined">
              {{ pct((row as PurchasePerfRow).onTimeRate) }}
            </StatusTag>
            <span v-else>{{ pct((row as PurchasePerfRow).onTimeRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="逾期平均晚（天）" width="140" class-name="num">
          <template #default="{ row }">{{ (row as PurchasePerfRow).avgLateDays ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="还没到期" prop="pendingCount" width="110" class-name="num" />
      </el-table>
    </template>

    <template v-else>
      <div v-if="factory" class="summary">
        <span>{{ rangeText }} 交期的生产单：</span>
        <span>算进来 <b class="num">{{ factory.total.orderCount }}</b> 张，按期 <b class="num">{{ factory.total.onTimeCount }}</b>，
          逾期 <b class="num">{{ factory.total.lateCount }}</b></span>
        <span>达成率 <b class="num">{{ pct(factory.total.onTimeRate) }}</b>，按件 <b class="num">{{ pct(factory.total.qtyRate) }}</b></span>
        <span>次品率 <b class="num">{{ pct(factory.total.defectRate) }}</b>
          <span class="dim">（回货 {{ formatQty(factory.total.receiptQty) }} 件，次品 {{ formatQty(factory.total.defectQty) }}）</span></span>
      </div>
      <el-table v-loading="loading" :data="factory?.rows ?? []" row-key="factoryId" scrollbar-always-on :empty-text="emptyText">
        <el-table-column label="加工厂" min-width="150" fixed="left">
          <template #default="{ row }">
            <span>{{ (row as FactoryPerfRow).factoryShortName }}</span>
            <span class="code dim"> {{ (row as FactoryPerfRow).factoryCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="算进来的单" prop="orderCount" width="110" class-name="num" />
        <el-table-column label="按期" prop="onTimeCount" width="70" class-name="num" />
        <el-table-column label="逾期" prop="lateCount" width="70" class-name="num" />
        <el-table-column label="达成率" width="100" class-name="num">
          <template #default="{ row }">
            <StatusTag v-if="rateTone((row as FactoryPerfRow).onTimeRate, 95, 80)" :tone="rateTone((row as FactoryPerfRow).onTimeRate, 95, 80) || undefined">
              {{ pct((row as FactoryPerfRow).onTimeRate) }}
            </StatusTag>
            <span v-else>{{ pct((row as FactoryPerfRow).onTimeRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="应交件数" width="100" class-name="num">
          <template #default="{ row }">{{ formatQty((row as FactoryPerfRow).dueQty) }}</template>
        </el-table-column>
        <el-table-column label="交期前回的良品" width="130" class-name="num">
          <template #default="{ row }">{{ formatQty((row as FactoryPerfRow).qtyByDue) }}</template>
        </el-table-column>
        <el-table-column label="按件达成" width="100" class-name="num">
          <template #default="{ row }">{{ pct((row as FactoryPerfRow).qtyRate) }}</template>
        </el-table-column>
        <el-table-column label="回货" width="90" class-name="num">
          <template #default="{ row }">{{ formatQty((row as FactoryPerfRow).receiptQty) }}</template>
        </el-table-column>
        <el-table-column label="次品" width="80" class-name="num">
          <template #default="{ row }">{{ formatQty((row as FactoryPerfRow).defectQty) }}</template>
        </el-table-column>
        <el-table-column label="次品率" width="100" class-name="num">
          <template #default="{ row }">
            <StatusTag v-if="rateTone((row as FactoryPerfRow).defectRate, 2, 5, true)" :tone="rateTone((row as FactoryPerfRow).defectRate, 2, 5, true) || undefined">
              {{ pct((row as FactoryPerfRow).defectRate) }}
            </StatusTag>
            <span v-else>{{ pct((row as FactoryPerfRow).defectRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="还没到期" prop="pendingCount" width="110" class-name="num" />
      </el-table>
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
</style>
