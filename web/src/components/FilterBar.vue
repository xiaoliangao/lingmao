<script setup lang="ts">
// 列表页的筛选栏：放进来的输入框按回车、点"查询"都触发 search；"重置"触发 reset
defineEmits<{ search: []; reset: [] }>()
</script>

<template>
  <el-form class="filter-bar" inline @submit.prevent="$emit('search')">
    <slot />
    <el-form-item>
      <el-button type="primary" native-type="submit">查询</el-button>
      <el-button @click="$emit('reset')">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.filter-bar {
  padding: 16px 20px 2px;
  border-bottom: 1px solid var(--lm-line-soft);
}

.filter-bar :deep(.el-form-item) {
  margin: 0 20px 14px 0;
}

.filter-bar :deep(.el-input),
.filter-bar :deep(.el-select) {
  width: 180px;
}
</style>
