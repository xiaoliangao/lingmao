<script setup lang="ts">
/**
 * 状态标签（原型的 St）：小胶囊，前面一个点。实心点 = 定了、在走；空心点 = 还没开始或停用。
 *
 *   active   启用、已审核、已下单、合作中（白底细边 + 铜褐点）
 *   progress 进行中、部分到货、生产中（铜褐浅底）
 *   pending  待处理、待审核、已驳回（琥珀）
 *   done     完成、成功、已到齐（绿）
 *   off      草稿、未开始、停用（灰底空心点）
 *   closed   已结案、已关闭（灰字）
 *   void     已作废、已取消（灰字 + 删除线）
 *   danger   失败、黑名单、不合格（红）
 *
 *   <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
 *
 * dot：只出一个小圆点（图卡角上这种放不下字的地方），字放进 title
 */
type Tone = 'active' | 'progress' | 'pending' | 'done' | 'off' | 'closed' | 'void' | 'danger'
withDefaults(defineProps<{ tone?: Tone; dot?: boolean }>(), { tone: 'active', dot: false })
</script>

<template>
  <span class="status-tag" :class="[tone, { dot }]"><slot v-if="!dot" /></span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5.5px;
  height: 22px;
  padding: 0 8px;
  font-size: 12px;
  font-weight: 400;
  line-height: 1;
  color: var(--lm-muted);
  white-space: nowrap;
  vertical-align: middle;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: 99px;
}

.status-tag:not(.dot)::before {
  flex: none;
  width: 6px;
  height: 6px;
  content: '';
  background: currentcolor;
  border-radius: 50%;
}

.active {
  color: var(--lm-ink);
  background: var(--lm-greige-2);
  border-color: var(--lm-line-strong);
}

.active:not(.dot)::before {
  background: var(--lm-accent);
}

.progress {
  font-weight: 500;
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.pending {
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border-color: var(--lm-pending-line);
}

.done {
  color: var(--lm-done-text);
  background: var(--lm-done-bg);
  border-color: var(--lm-done-line);
}

.off {
  color: var(--lm-muted);
  background: var(--lm-greige-2);
}

/* 空心点：还没开始、停用 */
.off:not(.dot)::before {
  background: transparent;
  box-shadow: inset 0 0 0 1.5px var(--lm-faint);
}

.closed,
.void {
  color: var(--lm-faint);
  background: var(--lm-greige-2);
  border-color: var(--lm-line-soft);
}

.void {
  text-decoration: line-through;
}

.danger {
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}

/* 只有一个点 */
.dot {
  width: 8px;
  height: 8px;
  padding: 0;
  border: 0;
  border-radius: 50%;
}

.dot.active,
.dot.progress {
  background: var(--lm-accent);
}

.dot.pending {
  background: var(--lm-pending-text);
}

.dot.done {
  background: var(--lm-done-text);
}

.dot.off,
.dot.closed,
.dot.void {
  background: var(--lm-faint);
}

.dot.danger {
  background: var(--lm-danger-text);
}
</style>
