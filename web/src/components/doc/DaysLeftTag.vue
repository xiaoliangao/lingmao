<script setup lang="ts">
import { computed } from 'vue'
import type { ProductionRisk } from '@/types/production'
import type { ArrivalRisk } from '@/types/purchase'

/**
 * 离交期（到料日）还有几天的小标签："逾 3 天"（红）、"剩 5 天"（琥珀）、"剩 20 天"（灰）。
 *
 *   生产单、看板、手机页——颜色按后端算好的 risk（05 §4：逾期、临期由后端按 nearDays 定，前端不自己按天数算）：
 *     <DaysLeftTag :days-left="row.daysLeft" :risk="row.risk" />
 *   待到料——ARRIVAL_RISK：<DaysLeftTag :days-left="row.daysLeft" :risk="row.risk" />
 *   需求订单详情（02 §4.3：7 天内琥珀、过了红）——没有 risk，按 warnDays 算：<DaysLeftTag :days-left="daysLeft" />
 *
 * daysLeft 是 null 不显示
 */
const props = withDefaults(
  defineProps<{
    daysLeft: number | null | undefined
    /** 后端给的风险：生产单的 ProductionRisk、待到料的 ArrivalRisk。给了就按它定颜色 */
    risk?: ProductionRisk | ArrivalRisk | null
    /** 没有 risk 时：还剩几天以内算临期 */
    warnDays?: number
  }>(),
  { risk: null, warnDays: 7 },
)

type Level = 'late' | 'warn' | 'ok'

const level = computed<Level>(() => {
  const risk = props.risk
  const d = props.daysLeft ?? 0
  if (risk == null) return d < 0 ? 'late' : d <= props.warnDays ? 'warn' : 'ok'
  if (typeof risk === 'string') return risk === 'LATE' ? 'late' : risk === 'NEAR' ? 'warn' : 'ok'
  return risk.late ? 'late' : risk.near ? 'warn' : 'ok'
})

const text = computed(() => {
  const d = props.daysLeft
  if (d == null) return null
  if (d < 0) return `逾 ${Math.abs(d)} 天`
  if (d === 0) return '今天到期'
  return `剩 ${d} 天`
})
</script>

<template>
  <span v-if="text" class="days-pill num" :class="level">{{ text }}</span>
</template>

<style scoped>
.days-pill {
  display: inline-block;
  padding: 0 6px;
  font-size: 11.5px;
  line-height: 18px;
  white-space: nowrap;
  border-radius: 99px;
}

.days-pill.late {
  font-weight: 600;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
}

.days-pill.warn {
  font-weight: 500;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
}

.days-pill.ok {
  color: var(--lm-muted);
  background: var(--lm-subtle);
}
</style>
