<script setup lang="ts">
withDefaults(defineProps<{ loading?: boolean; error?: string | null; label?: string; uncertain?: boolean }>(), { label: '内容' })
defineEmits<{ retry: [] }>()
</script>
<template>
  <p v-if="loading" class="sample-feedback dim" role="status">正在读取{{ label }}…</p>
  <div v-else-if="error" class="sample-feedback error" role="alert">
    <span>{{ error }}</span><slot><el-button link @click="$emit('retry')">重试</el-button></slot>
    <p v-if="uncertain" class="dim">结果尚未确认，重试会沿用同一份提交；先不要新建另一笔。</p>
  </div>
</template>
<style scoped>
.sample-feedback { margin: 8px 0; font-size: 12px; overflow-wrap: anywhere; }
.error { color: var(--lm-danger-text); }
.sample-feedback p { margin: 5px 0; }
</style>
