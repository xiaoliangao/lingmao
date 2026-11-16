<script setup lang="ts">
import { computed } from 'vue'
import { formatQty } from '@/utils/format'

/**
 * 件数进度条：上面一根细条，下面"已做/总数"。订单列表的排产、回货，生产单列表、生产看板的回货都用它，
 * 颜色、算法只在这一处。超过 100%（溢装）条画满，数字照实写。
 *
 *   <QtyProgress :done="row.scheduledQty" :total="row.totalQty" />
 *   <QtyProgress :done="row.receivedQty" :total="row.totalQty" tone="done" />
 *
 * 还没开始（审核前没有排产、没排产就没有回货）由页面自己显示"—"，不要传 0 进来画空条
 */
const props = withDefaults(defineProps<{ done: number; total: number; tone?: 'accent' | 'done' }>(), { tone: 'accent' })

const pct = computed(() => (props.total > 0 ? Math.min(100, Math.round((props.done / props.total) * 100)) : 0))
</script>

<template>
  <div class="qty-progress num" :title="`${pct}%`">
    <span class="bar" :class="tone" role="progressbar" :aria-valuenow="pct" aria-valuemin="0" aria-valuemax="100">
      <i :style="{ width: `${pct}%` }" />
    </span>
    <span>{{ formatQty(done) }}/{{ formatQty(total) }}</span>
  </div>
</template>

<style scoped>
.qty-progress {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11.5px;
}

.bar {
  display: block;
  width: 100%;
  height: 5px;
  overflow: hidden;
  background: var(--lm-sunken);
  border-radius: 99px;
}

.bar i {
  display: block;
  height: 100%;
  background: var(--lm-accent);
  border-radius: 99px;
}

.bar.done i {
  background: var(--lm-done-text);
}
</style>
