<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { qcApi } from '@/api/quality'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import { useDict } from '@/composables/useDict'
import type { QcKind, QcSupplierStat } from '@/types/quality'
import { formatDateRange, formatMoney, formatQty } from '@/utils/format'

/**
 * 质检页的"供应商质量"页签：按家统计这段时间（默认最近 90 天）的检验次数、结论、常见问题、质量扣款，不合格多的在前。
 * 回货的次品率在 报表 › 供应商绩效
 */
const defectLabel = useDict('qc_defect_type').labelOf
const rows = ref<QcSupplierStat[]>([])
const loading = ref(false)
const kind = ref<'' | QcKind>('')
const dates = ref<[string, string] | null>(null)

const KIND_OPTIONS = [
  { label: '全部', value: '' },
  { label: '尾期验货', value: 'FINAL' },
  { label: '来料检验', value: 'INCOMING' },
  { label: '中检', value: 'MIDDLE' },
  { label: '产前样', value: 'PRE_PRODUCTION' },
]

async function load() {
  loading.value = true
  try {
    rows.value = await qcApi.supplierStats({ kind: kind.value || undefined, dateFrom: dates.value?.[0], dateTo: dates.value?.[1] })
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 弹层里的条件改了、去掉了，FilterBar 自己会 @search：这里只改条件，再查就重复发了（agy T-77e556 P1-8）
function onDates(val: [string, string] | null | undefined) {
  dates.value = val ?? null
}

const pct = (n: number | null | undefined, d: number | null | undefined) => (n != null && d ? `${((n / d) * 100).toFixed(1)}%` : '—')

defineExpose({ reload: load })
</script>

<template>
  <div>
    <FilterBar @search="load">
      <template #left>
        <el-segmented v-model="kind" :options="KIND_OPTIONS" @change="load" />
      </template>
      <FilterItem label="检验日期" :text="formatDateRange(dates)" @clear="onDates(null)">
        <DateRange :model-value="dates" @update:model-value="onDates" />
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="rows" row-key="partnerId" scrollbar-always-on
      empty-text="这段时间没有检验记录：换个日期看看">
      <el-table-column label="供应商" min-width="160" fixed="left">
        <template #default="{ row }"><span class="code dim">{{ row.partnerCode }}</span> {{ row.partnerName }}</template>
      </el-table-column>
      <el-table-column label="检验次数" width="96" class-name="num" align="right" prop="inspectCount" />
      <el-table-column label="合格率" width="90" class-name="num" align="right">
        <template #default="{ row }">{{ pct(row.passCount, row.inspectCount) }}</template>
      </el-table-column>
      <el-table-column label="不合格" width="80" class-name="num" align="right">
        <template #default="{ row }"><span :class="{ red: row.failCount > 0 }">{{ row.failCount }}</span></template>
      </el-table-column>
      <el-table-column label="让步接收" width="90" class-name="num" align="right" prop="concessionCount" />
      <el-table-column label="抽检 / 不合格" width="130" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.sampleQty != null">{{ formatQty(row.sampleQty) }} / {{ formatQty(row.defectQty) }}</template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="抽检不良率" width="100" class-name="num" align="right">
        <template #default="{ row }">{{ pct(row.defectQty, row.sampleQty) }}</template>
      </el-table-column>
      <el-table-column label="常见问题" min-width="180">
        <template #default="{ row }">{{ (row as QcSupplierStat).topDefects.map(defectLabel).join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column label="质量扣款" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ row.deductAmount != null ? formatMoney(row.deductAmount) : '—' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.red {
  color: var(--lm-danger-text);
}
</style>
