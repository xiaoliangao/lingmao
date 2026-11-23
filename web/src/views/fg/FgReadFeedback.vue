<script setup lang="ts">
defineProps<{ loading?: boolean; error: string | null; label?: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <span v-if="loading" class="fg-feedback dim" role="status">{{ label ?? '成品信息' }}读取中…</span>
  <span v-else-if="error" class="fg-feedback" role="alert">
    {{ label ?? '成品信息' }}：{{ error }}
    <el-button link type="primary" size="small" @click="$emit('retry')">重试</el-button>
  </span>
</template>

<style scoped>
.fg-feedback { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 4px 8px; font-size: 12px; }
.fg-feedback[role="alert"] { color: var(--lm-danger-text); }
</style>
