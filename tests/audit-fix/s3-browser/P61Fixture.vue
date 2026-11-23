<script setup lang="ts">
import { ref } from 'vue'
import SamplesFixture from './SamplesFixture.vue'
import TagHistoryDialog from '@/components/tags/TagHistoryDialog.vue'
const visible = ref(false)
const tag = { id: '9007199254741666', name: '合成已改名-bccd88a4', archived: true, version: 2 }
</script>
<template>
  <SamplesFixture />
  <button class="p61-open" data-testid="p61-history" @click="visible = true">标签记录</button>
  <TagHistoryDialog v-model="visible" :tag="tag" />
</template>
<style scoped>
.p61-open { position: fixed; bottom: 28px; right: 24px; z-index: 4000; }
</style>
