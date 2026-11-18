<script setup lang="ts">
import { businessToday, addBusinessDays, businessMonth, pickerDate } from '@/utils/businessDate'
/**
 * 日期范围：Element Plus 的 daterange，只是把常用的设好——一个月的日历卡片，上面一排快捷项
 * （今天、近 7 天、近 30 天、本月），值是 "2026-11-01" 这种字符串。
 * v-model 是 [开始, 结束]，清空是 null。放进 useTable 的 query 里，发给后端是 key=开始&key=结束，后端用 List 接。
 * 小标签上的字用 utils/format.ts 的 formatDateRange。el-date-picker 的其他属性直接写在它上面
 *
 *   <FilterItem label="下单日期" :text="formatDateRange(query.orderDate)" @clear="query.orderDate = null">
 *     <DateRange v-model="query.orderDate" />
 *   </FilterItem>
 */
const model = defineModel<[string, string] | null>()

const day = (offset: number) => pickerDate(addBusinessDays(businessToday(), offset))
const SHORTCUTS = [
  { text: '今天', value: () => [day(0), day(0)] },
  { text: '近 7 天', value: () => [day(-6), day(0)] },
  { text: '近 30 天', value: () => [day(-29), day(0)] },
  { text: '本月', value: () => businessMonth().map(pickerDate) },
]
</script>

<template>
  <el-date-picker
    v-model="model"
    type="daterange"
    value-format="YYYY-MM-DD"
    range-separator="→"
    start-placeholder="开始日期"
    end-placeholder="结束日期"
    :shortcuts="SHORTCUTS"
    single-panel
    unlink-panels
  />
</template>
