<script setup lang="ts">
import type { KittingError } from '@/composables/useOrderKitting'

defineProps<{ loading: boolean; error: KittingError }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <span v-if="loading" class="dim" data-testid="kitting-loading">读取中…</span>
  <span v-else class="kitting-feedback" data-testid="kitting-failure" :title="error === 'unavailable' ? '无权限或单据已不可见，可重试' : '齐料信息读取失败，请重试'">
    <span class="dim">{{ error === 'unavailable' ? '不可查看' : '读取失败' }}</span>
    <el-button link type="primary" size="small" aria-label="重试齐料状态" @click.stop="$emit('retry')">重试</el-button>
  </span>
</template>

<style scoped>
.kitting-feedback { display: inline-flex; align-items: center; gap: 4px; max-width: 100%; white-space: nowrap; }
</style>
