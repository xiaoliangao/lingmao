<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { useUserStore } from '@/stores/user'
import FgDocFormDialog from '../doc/FgDocFormDialog.vue'
import FgDocList from '../doc/FgDocList.vue'
import PendingReceipts from './PendingReceipts.vue'

/**
 * 成品入库单（/fg-in，docs/api/07 §1.3）。两个页签：
 * - 待入库（?tab=pending，有 fg:doc:add 时默认）：已登记、良品还没入完的回货单
 * - 入库单（?tab=docs）：入库单列表（和出库单共用 FgDocList）
 * 页头"其他入库"：期初、退回的货这些不是回货来的
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()

const canAdd = computed(() => user.hasPerm('fg:doc:add'))
type Tab = 'pending' | 'docs'
const pickTab = (value: unknown): Tab => (value === 'docs' || !canAdd.value ? 'docs' : 'pending')
const tab = ref<Tab>(pickTab(route.query.tab))

watch(
  () => route.query.tab,
  (value) => {
    if (route.path !== '/fg-in' || value === undefined) return
    tab.value = pickTab(value)
  },
)

const listRef = ref<InstanceType<typeof FgDocList>>()
const formVisible = ref(false)

function openDoc(id: string) {
  void router.push(`/fg-in/${id}`)
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-if="tab === 'docs'" v-perm="'fg:doc:export'" icon="Download" :loading="listRef?.exporting" @click="listRef?.exportAll()">
          导出
        </el-button>
        <el-button v-perm="'fg:doc:add'" type="primary" icon="Plus" @click="formVisible = true">其他入库</el-button>
      </template>
    </PageHeader>

    <el-tabs v-if="canAdd" v-model="tab" class="tabs">
      <el-tab-pane label="待入库" name="pending" />
      <el-tab-pane label="入库单" name="docs" />
    </el-tabs>

    <PendingReceipts v-if="canAdd" v-show="tab === 'pending'" @received="listRef?.reload()" />
    <FgDocList v-show="tab === 'docs'" ref="listRef" kind="IN" />

    <FgDocFormDialog v-model="formVisible" doc-type="OTHER_IN" @saved="openDoc" />
  </div>
</template>

<style scoped>
/* 页签条接在页头下面，左边和页头标题对齐（同物料入库单）；下面每个页签是"筛选一行 + 表格" */
.tabs > :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 var(--lm-table-edge, 24px);
  background: var(--lm-paper);
}

.tabs > :deep(.el-tabs__header .el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--lm-line-soft);
}
</style>
