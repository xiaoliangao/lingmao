<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import ObjectTags from '@/components/tags/ObjectTags.vue'
import { useUserStore } from '@/stores/user'
import type { TagObjectType } from '@/types/tag'
const user = useUserStore()
const route = useRoute()
const type = ref((route.query.type as TagObjectType) || 'ORDER')
const id = ref('9007199254746001')
const active = ref(true)
const editable = ref(route.query.readonly !== '1')
const company = computed(() => route.query.nullCompany === '1' ? null : user.defaultCompanyId)
</script>
<template>
  <main class="page">
    <h1>实际对象标签组件</h1>
    <div class="fixture-actions">
      <el-button data-testid="tag-next-object" @click="id = '9007199254746002'">切换对象</el-button>
      <el-button data-testid="tag-close-object" @click="active = false">关闭对象</el-button>
      <el-button data-testid="tag-disable-object" @click="editable = false">对象变为只读</el-button>
    </div>
    <div class="tag-panel"><ObjectTags :type="type" :object-id="id" :company-id="company" :editable="editable" :active="active" /></div>
  </main>
</template>
<style scoped>
h1 { font-size: 18px; }
.fixture-actions { display: flex; gap: 8px; margin: 16px 0; }
.tag-panel { max-width: 440px; }
</style>
