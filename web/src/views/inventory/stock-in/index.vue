<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import { useUserStore } from '@/stores/user'
import { INV_DOC_TYPE, type InvDocType } from '@/types/inventory'
import DocList from '../doc/DocList.vue'
import InvDocFormDialog from '../doc/InvDocFormDialog.vue'
import PendingArrivals from './PendingArrivals.vue'

/**
 * 物料入库单（/stock-in，docs/api/04 §1.5）。两个页签：
 * - 待到料（?tab=pending，默认）：只给有 purchase:order:list 的人，没有的默认进"入库单"
 * - 入库单（?tab=docs）：入库单列表（和出库单共用 DocList）
 * 页头"新建"下拉：退料入库、其他入库（INV_DOC_TYPE 里 kind = IN、能手建的）
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()

const canSeePending = computed(() => user.hasPerm('purchase:order:list'))
type Tab = 'pending' | 'docs'
const pickTab = (value: unknown): Tab => (value === 'docs' || !canSeePending.value ? 'docs' : 'pending')
const tab = ref<Tab>(pickTab(route.query.tab))

watch(
  () => route.query.tab,
  (value) => {
    if (route.path !== '/stock-in' || value === undefined) return
    tab.value = pickTab(value)
  },
)

const listRef = ref<InstanceType<typeof DocList>>()

const NEW_TYPES = (Object.keys(INV_DOC_TYPE) as InvDocType[]).filter((t) => INV_DOC_TYPE[t].kind === 'IN' && !INV_DOC_TYPE[t].sourceRequired)
const formVisible = ref(false)
const formType = ref<InvDocType>('OTHER_IN')

function openNew(type: InvDocType) {
  formType.value = type
  formVisible.value = true
}

function openDoc(id: number) {
  void router.push(`/stock-in/${id}`)
}

// 退料入库挂了源生产单：走生成接口
const returnVisible = ref(false)
const returnProdId = ref<number | null>(null)

function openReturn(productionOrderId: number) {
  returnProdId.value = productionOrderId
  returnVisible.value = true
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-if="tab === 'docs'" v-perm="'inventory:doc:export'" icon="Download" :loading="listRef?.exporting" @click="listRef?.exportAll()">
          导出
        </el-button>
        <el-dropdown v-perm="'inventory:doc:add'" trigger="click" @command="openNew">
          <el-button type="primary" icon="Plus">新建</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="t in NEW_TYPES" :key="t" :command="t">{{ INV_DOC_TYPE[t].label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </template>
    </PageHeader>

    <el-tabs v-if="canSeePending" v-model="tab" class="tabs">
      <el-tab-pane label="待到料" name="pending" />
      <el-tab-pane label="入库单" name="docs" />
    </el-tabs>

    <PendingArrivals v-if="canSeePending" v-show="tab === 'pending'" @received="listRef?.reload()" />
    <DocList v-show="tab === 'docs'" ref="listRef" kind="IN" />

    <InvDocFormDialog v-model="formVisible" :doc-type="formType" @saved="openDoc" @from-production="openReturn" />
    <SourceInvDocDialog v-model="returnVisible" source-kind="PRODUCTION" :source-id="returnProdId" doc-type="RETURN_IN" @created="listRef?.reload()" />
  </div>
</template>

<style scoped>
/* 页签条接在页头下面，左边和页头标题对齐（同基础资料页）；下面每个页签是"筛选一行 + 表格" */
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
