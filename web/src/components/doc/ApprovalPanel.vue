<script setup lang="ts">
import { computed } from 'vue'
import type { ApprovalView } from '@/types/biz'
import StatusTag from '@/components/StatusTag.vue'
import { formatDateTime, formatMoney } from '@/utils/format'

/**
 * 详情侧栏的"审批"块（docs/api/README.md §4.4，架构 §4.8）：每一级谁审、门槛、谁什么时候审的、现在等谁。
 * 需求订单、采购单、外发加工单、成品采购单才有（详情里的 approval），别的单据 approval 是 null，不显示。
 *
 *   <ApprovalPanel :approval="detail.approval" :status="detail.status" />
 *
 * 待审核时一直显示；审完了只有多于一级的留着。能不能审看详情的 actions，这里只显示：
 * 轮到我审时等待条下面多一行字，指到页头的"审核""驳回"（不做成按钮或实心标签，免得看着像能点）
 */
const props = defineProps<{
  approval: ApprovalView | null
  status: string
}>()

const visible = computed(() => {
  const a = props.approval
  if (!a) return false
  if (props.status === 'PENDING') return true
  return a.levels.length > 1
})

function levelTone(st: string) {
  if (st === 'PASSED') return 'done' as const
  if (st === 'CURRENT') return 'pending' as const
  if (st === 'CANCELLED') return 'closed' as const
  return 'off' as const
}

function levelLabel(st: string) {
  if (st === 'PASSED') return '已通过'
  if (st === 'CURRENT') return '待审批'
  if (st === 'CANCELLED') return '已取消'
  return '等待前序'
}
</script>

<template>
  <section v-if="visible && approval" class="panel">
    <div class="panel-head">
      <span>多级审批</span>
      <span v-if="approval.round > 1" class="acts dim">第 {{ approval.round }} 轮提交</span>
    </div>
    <div class="panel-body">
      <div v-if="approval.waitingFor" class="wait-banner" :class="{ mine: approval.canPass }">
        <span>当前等待：<strong>{{ approval.waitingFor }}</strong></span>
        <span v-if="approval.canPass" class="mine-line">轮到你审：在右上角审核或驳回</span>
      </div>
      <ol class="levels">
        <li v-for="lv in approval.levels" :key="lv.levelNo" :class="lv.status.toLowerCase()">
          <div class="row">
            <span class="badge">{{ lv.levelNo }}</span>
            <span class="who">{{ lv.approverLabel }}</span>
            <span class="gap" />
            <StatusTag :tone="levelTone(lv.status)">{{ levelLabel(lv.status) }}</StatusTag>
          </div>
          <div v-if="lv.overAmount != null || lv.passByName" class="sub dim">
            <span v-if="lv.overAmount != null">满 ¥{{ formatMoney(lv.overAmount) }} 加审</span>
            <span v-if="lv.passByName">{{ lv.passByName }} · {{ formatDateTime(lv.passTime) }}</span>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>

<style scoped>
.wait-banner {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius-sm);
}

.wait-banner.mine {
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.mine-line {
  font-weight: 500;
}

.levels {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.levels li {
  padding: 8px 10px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.levels li.current {
  background: var(--lm-paper);
  border-color: var(--lm-pending-line);
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}

.badge {
  display: inline-grid;
  place-items: center;
  width: 18px;
  height: 18px;
  font-size: 11px;
  font-weight: 600;
  color: var(--lm-ink-2);
  background: var(--lm-sunken);
  border-radius: 99px;
}

.who {
  font-weight: 500;
  color: var(--lm-ink);
}

.gap {
  flex: 1;
}

.sub {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px;
  margin-top: 4px;
  padding-left: 26px;
  font-size: 11.5px;
}
</style>
