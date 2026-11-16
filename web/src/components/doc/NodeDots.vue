<script setup lang="ts">
import { NODE_STATE, type NodeDot } from '@/types/production'

/**
 * 生产单的节点进度条（原型 NodeBar）：每个节点一格，颜色按后端算好的 NodeState（完成绿、进行中铜褐、临期琥珀、延期红），
 * 后面跟当前节点的名字。生产单列表、看板、手机报工的卡片都用它。
 *
 *   <NodeDots :nodes="row.nodes" :current-name="row.currentNode" />
 *
 * 没有节点（草稿、待审核还没生成）显示"—"
 */

defineProps<{
  nodes: NodeDot[]
  currentName?: string | null
}>()
</script>

<template>
  <div v-if="nodes.length" class="node-wrap">
    <div class="dots">
      <span
        v-for="(n, idx) in nodes"
        :key="`${n.nodeName}-${idx}`"
        class="dot"
        :class="`st-${n.state.toLowerCase()}`"
        :title="`${n.nodeName}：${NODE_STATE[n.state].label}`"
      />
    </div>
    <span v-if="currentName" class="cur dim">{{ currentName }}</span>
    <span v-else class="cur done">全部完成</span>
  </div>
  <span v-else class="dim">—</span>
</template>

<style scoped>
.node-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.dot {
  width: 10px;
  height: 8px;
  background: var(--lm-sunken);
  border: 1px solid var(--lm-line);
  border-radius: 2px;
}

.dot.st-done {
  background: var(--lm-done-text);
  border-color: var(--lm-done-text);
}

.dot.st-running {
  background: var(--lm-accent);
  border-color: var(--lm-accent);
}

.dot.st-warn {
  background: var(--lm-pending-text);
  border-color: var(--lm-pending-text);
}

.dot.st-late {
  background: var(--lm-danger-text);
  border-color: var(--lm-danger-text);
}

.cur {
  font-size: 12px;
  white-space: nowrap;
}

.cur.done {
  color: var(--lm-done-text);
}
</style>
