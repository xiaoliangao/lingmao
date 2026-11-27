<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { reportApi } from '@/api/report'
import PageHeader from '@/components/PageHeader.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useProductLines } from '@/composables/useProductLines'
import type { LineWipRow } from '@/types/report'
import { businessToday } from '@/utils/businessDate'
import { formatQty } from '@/utils/format'

/**
 * 各品类线在做件数（/line-wip，RFC 019 §3.2，甲方 11-27 答 Q66，每月看）：
 * 在做 = 外发加工单、成品采购单还没回的件数（草稿到生产中），按品类线 × 一级品类；逾期按预计交期（没有用约定交期）。
 * "这个月"两列看选的月份：新排了多少、回了多少良品
 */
const lines = useProductLines()
const month = ref(businessToday().slice(0, 7))
const rows = ref<LineWipRow[]>([])
const loading = ref(false)
const reader = useLatestRequest()

async function load() {
  const read = reader.begin()
  loading.value = true
  try {
    const r = await read.run(() => reportApi.lineWip(month.value || undefined))
    if (read.current()) rows.value = r
  } finally {
    if (read.current()) loading.value = false
  }
}
onActivated(load)

/** 同一品类线的行连着，第一行出品类线名 */
const firstOfLine = computed(() => {
  const seen = new Set<string>()
  return rows.value.map((r) => {
    const first = !seen.has(r.productLine)
    seen.add(r.productLine)
    return first
  })
})
const sum = (key: 'orderCount' | 'wipQty' | 'lateQty' | 'monthNewQty' | 'monthGoodQty') =>
  rows.value.reduce((s, r) => s + (r[key] ?? 0), 0)
function summary({ columns }: { columns: { property?: string }[] }) {
  return columns.map((c, i) => {
    if (i === 0) return '合计'
    const key = c.property as Parameters<typeof sum>[0] | undefined
    return key && ['orderCount', 'wipQty', 'lateQty', 'monthNewQty', 'monthGoodQty'].includes(key) ? formatQty(sum(key)) : ''
  })
}
const monthLabel = computed(() => (month.value ? `${Number(month.value.slice(5, 7))} 月` : '这个月'))
</script>

<template>
  <div>
    <PageHeader sub="外发加工单、成品采购单还没回的件数">
      <template #actions>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false" style="width: 130px" @change="load" />
      </template>
    </PageHeader>

    <el-table v-loading="loading" :data="rows" :row-key="(r: LineWipRow) => `${r.productLine}-${r.categoryId}`" scrollbar-always-on
      show-summary :summary-method="summary" empty-text="没有在做的生产单，这个月也没有回货">
      <el-table-column label="品类线" width="110" fixed="left">
        <template #default="{ row, $index }">
          <span v-if="firstOfLine[$index]">{{ lines.labelOf((row as LineWipRow).productLine) || (row as LineWipRow).productLine }}</span>
        </template>
      </el-table-column>
      <el-table-column label="品类" min-width="120">
        <template #default="{ row }">{{ (row as LineWipRow).categoryName ?? '没分品类' }}</template>
      </el-table-column>
      <el-table-column label="在做的单" prop="orderCount" width="100" class-name="num" align="right" />
      <el-table-column label="在做件数" prop="wipQty" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as LineWipRow).wipQty) }}</template>
      </el-table-column>
      <el-table-column label="其中逾期" prop="lateQty" width="100" class-name="num" align="right">
        <template #default="{ row }">
          <span :class="{ late: (row as LineWipRow).lateQty > 0 }">{{ formatQty((row as LineWipRow).lateQty) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="`${monthLabel}新排`" prop="monthNewQty" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as LineWipRow).monthNewQty) }}</template>
      </el-table-column>
      <el-table-column :label="`${monthLabel}回的良品`" prop="monthGoodQty" width="134" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as LineWipRow).monthGoodQty) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.late {
  color: var(--lm-danger-text);
}
</style>
