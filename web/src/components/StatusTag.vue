<script setup lang="ts">
/**
 * 状态标签。实线 = 定下来了，虚线 = 还没定（像衣服正式缝之前的疏缝）：
 * done    完成、启用、已审核（苔绿实线）
 * pending 待处理、未审核（栀黄虚线）
 * off     停用（灰色虚线）
 * danger  失败、作废（茜红实线）
 */
withDefaults(defineProps<{ tone?: 'done' | 'pending' | 'off' | 'danger' }>(), { tone: 'done' })
</script>

<template>
  <span class="status-tag" :class="tone"><slot /></span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border: 1px solid;
  border-radius: 3px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}

.done {
  color: var(--lm-done-text);
  background: var(--lm-done-bg);
  border-color: var(--lm-done-line);
}

.pending {
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border-style: dashed;
  border-color: var(--lm-pending-line);
}

.off {
  color: var(--lm-muted);
  background: var(--lm-greige-2);
  border-style: dashed;
  border-color: #b7bcc4;
}

.danger {
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border-color: var(--lm-danger-line);
}
</style>
