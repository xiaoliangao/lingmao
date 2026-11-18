<script setup lang="ts">
import { ref, watch } from 'vue'
const props = withDefaults(defineProps<{ src?: string | null; alt?: string }>(), { src: null, alt: '' })
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
</script>
<template>
  <img v-if="src && !failed" :src="src" :alt="alt" loading="lazy" decoding="async" @error="failed = true" />
  <span v-else class="thumb-placeholder" role="img" :aria-label="alt || '暂无缩略图'">无图</span>
</template>
<style scoped>
.thumb-placeholder { display: inline-flex; align-items: center; justify-content: center; min-width: 28px; min-height: 28px; font-size: 10px; color: var(--lm-muted); background: var(--lm-subtle); border: 1px solid var(--lm-line-soft); overflow: hidden; }
</style>
