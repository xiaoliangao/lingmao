<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import { fgDocApi } from '@/api/fg'

const route = useRoute()
const logs = ref<InstanceType<typeof DocLogList>>()
const compact = route.query.layout !== 'table'
const load = () => fgDocApi.logs('9007199254743001', { companyId: '1', silent: true })
</script>

<template>
  <PageHeader title="实际操作记录组件">
    <template #actions><el-button @click="logs?.reload()">重新加载记录</el-button></template>
  </PageHeader>
  <div class="page">
    <section class="panel" :class="{ compact }" data-testid="log-panel">
      <div class="panel-head">操作记录</div>
      <div class="panel-body"><DocLogList ref="logs" :load="load" :compact="compact" /></div>
    </section>
  </div>
</template>

<style scoped>
.panel { max-width: 1000px; }
.compact { width: 300px; }
</style>
