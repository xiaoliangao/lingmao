<script setup lang="ts">
/**
 * 空白提示（原型的 .empty）：居中一行浅灰字，下面可以放一个按钮。404、功能没上线、面板里没数据时用。
 * 字要短，写清楚为什么空，如"暂无待办""找不到这个页面"；不写大段说明
 *
 *   <BlankState title="找不到这个页面"><el-button @click="…">回首页</el-button></BlankState>
 */
defineProps<{ title: string }>()
</script>

<template>
  <div class="blank-state">
    <p>{{ title }}</p>
    <div v-if="$slots.default" class="actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.blank-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 52px 24px;
  text-align: center;
}

p {
  font-size: 13px;
  color: var(--lm-faint);
  text-wrap: balance;
}

.actions {
  margin-top: 16px;
}
</style>
