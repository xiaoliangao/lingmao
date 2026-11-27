<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { useUserStore } from '@/stores/user'
import type { QcKind, QcRow } from '@/types/quality'
import QcInspectDialog from './QcInspectDialog.vue'
import QcPendingList from './QcPendingList.vue'
import QcRecordList from './QcRecordList.vue'
import QcReworkList from './QcReworkList.vue'
import QcSupplierStats from './QcSupplierStats.vue'

/**
 * 生产 › 质检（/qc，RFC 019 §6，docs/api/10）。四个页签：
 * - 待检（?tab=pending，默认）：最近 30 天审核过的采购入库、登记的回货还没验的，返修完成等再验的回货排最前；点「验货」
 * - 不合格、返修（?tab=rework）：回货验货不合格 → 返修 → 返修完成（回到待检再验一次）
 * - 质检记录（?tab=records）：每次检验一条，能改、删；质量扣款跟着走
 * - 供应商质量（?tab=stats）
 * 页头「记产前样、中检」：对生产单记，在对话框里选单
 */
const route = useRoute()
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('quality:inspection:edit'))

type Tab = 'pending' | 'rework' | 'records' | 'stats'
const TABS: Tab[] = ['pending', 'rework', 'records', 'stats']
const pickTab = (value: unknown): Tab => (TABS.includes(value as Tab) ? (value as Tab) : 'pending')
const tab = ref<Tab>(pickTab(route.query.tab))
watch(
  () => route.query.tab,
  (value) => {
    if (route.path !== '/qc' || value === undefined) return
    tab.value = pickTab(value)
  },
)

const pendingRef = ref<InstanceType<typeof QcPendingList>>()
const reworkRef = ref<InstanceType<typeof QcReworkList>>()
const recordsRef = ref<InstanceType<typeof QcRecordList>>()
const statsRef = ref<InstanceType<typeof QcSupplierStats>>()

const dialogOpen = ref(false)
const target = ref<{ kind: QcKind; sourceId?: string } | null>(null)
const record = ref<QcRow | null>(null)

function inspect(kind: QcKind, sourceId?: string) {
  record.value = null
  target.value = { kind, sourceId }
  dialogOpen.value = true
}

function edit(row: QcRow) {
  target.value = null
  record.value = row
  dialogOpen.value = true
}

/** 记了、改了：几个页签的数都可能变 */
function reloadAll() {
  void pendingRef.value?.reload()
  void reworkRef.value?.reload()
  void recordsRef.value?.reload()
  void statsRef.value?.reload()
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <el-button v-if="canEdit" type="primary" icon="Plus" @click="inspect('MIDDLE')">记产前样、中检</el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane label="待检" name="pending" />
      <el-tab-pane label="不合格、返修" name="rework" />
      <el-tab-pane label="质检记录" name="records" />
      <el-tab-pane label="供应商质量" name="stats" />
    </el-tabs>

    <QcPendingList v-show="tab === 'pending'" ref="pendingRef" @inspect="inspect" />
    <QcReworkList v-show="tab === 'rework'" ref="reworkRef" @done="pendingRef?.reload()" />
    <QcRecordList v-show="tab === 'records'" ref="recordsRef" @edit="edit" />
    <QcSupplierStats v-if="tab === 'stats'" ref="statsRef" />

    <QcInspectDialog v-model="dialogOpen" :target="target" :record="record" @saved="reloadAll" />
  </div>
</template>

<style scoped>
/* 页签条接在页头下面（同成品入库单） */
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
