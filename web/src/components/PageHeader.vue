<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

// 页面标题行：左边标题（默认取菜单名）和一句说明，右边放这个页面最主要的操作（如"新增用户"）
const props = defineProps<{ title?: string; description?: string }>()
const route = useRoute()
const text = computed(() => props.title ?? (route.meta.title as string | undefined) ?? '')
</script>

<template>
  <header class="page-header">
    <div>
      <h1>{{ text }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
}

p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--lm-muted);
}

.actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}
</style>
