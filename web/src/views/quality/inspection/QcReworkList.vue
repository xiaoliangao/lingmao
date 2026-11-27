<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { qcApi } from '@/api/quality'
import StatusTag from '@/components/StatusTag.vue'
import { reloadOnConflict } from '@/components/doc/docUtils'
import { useUserStore } from '@/stores/user'
import type { QcRow } from '@/types/quality'
import { formatDate, formatDateTime, formatQty } from '@/utils/format'
import { orderLink, sourceLink } from './qcLinks'

/**
 * 质检页的"不合格、返修"页签：尾期验货不合格还没返修完的回货。
 * 「返修」写返修说明（回货拉回工厂）→ 返修中；「返修完成」→ 这批回货回到待检，再验一次（emit done，待检页签刷新）
 */
const emit = defineEmits<{ done: [] }>()
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('quality:inspection:edit'))
const rows = ref<QcRow[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await qcApi.reworks()
  } finally {
    loading.value = false
  }
}
onMounted(load)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

async function rework(row: QcRow) {
  let note: string
  try {
    ({ value: note } = await ElMessageBox.prompt(
      '回货拉回工厂返修，这批变「返修中」。工厂返修回来点「返修完成」，回到待检再验一次；这次的验货记录留着。',
      `返修 ${row.sourceNo}`,
      { inputPlaceholder: '返修说明：返修什么、多少件、工厂哪天送回', confirmButtonText: '返修', cancelButtonText: '取消',
        inputValidator: (v) => (v && v.trim() ? true : '要写返修说明') },
    ))
  } catch {
    return
  }
  try {
    await qcApi.rework(row.id, { version: row.version, note: note.trim() }, row.companyId)
    ElMessage.success(`${row.sourceNo} 返修中，返修回来点「返修完成」`)
    void load()
  } catch (e) {
    if (reloadOnConflict(e, load)) return
    throw e
  }
}

async function reworkDone(row: QcRow) {
  try {
    await ElMessageBox.confirm(`${row.sourceNo} 返修回来了，回到待检，再验第 ${row.attemptNo + 1} 次。`, '返修完成',
      { confirmButtonText: '返修完成', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await qcApi.reworkDone(row.id, { version: row.version }, row.companyId)
    ElMessage.success(`${row.sourceNo} 回到待检，验第 ${row.attemptNo + 1} 次`)
    void load()
    emit('done')
  } catch (e) {
    if (reloadOnConflict(e, load)) return
    throw e
  }
}

defineExpose({ reload: load })
</script>

<template>
  <el-table v-loading="loading" :data="rows" row-key="id" scrollbar-always-on
    empty-text="没有不合格、在返修的回货：尾期验货不合格的回货会列在这里，等安排返修">
    <el-table-column label="回货单" width="170" fixed="left">
      <template #default="{ row }">
        <router-link v-if="sourceLink(row as QcRow)" class="code link" :to="sourceLink(row as QcRow)!">{{ row.sourceNo }}</router-link>
        <span v-else class="code">{{ row.sourceNo }}</span>
      </template>
    </el-table-column>
    <el-table-column label="生产单 · 加工厂" min-width="190">
      <template #default="{ row }">
        <router-link v-if="orderLink(row as QcRow)" class="code link" :to="orderLink(row as QcRow)!">{{ row.prdOrderNo }}</router-link>
        <span class="dim"> · {{ row.partnerName }}</span>
      </template>
    </el-table-column>
    <el-table-column label="货品" min-width="160" prop="targetDesc" />
    <el-table-column label="最近一次验货" min-width="240" :show-overflow-tooltip="false">
      <template #default="{ row }">
        第 {{ row.attemptNo }} 次 · {{ formatDate(row.inspectDate) }} · 抽 {{ formatQty(row.sampleQty) }}，不合格 {{ formatQty(row.defectQty) }}
        <div class="dim small">{{ row.note }}</div>
      </template>
    </el-table-column>
    <el-table-column label="返修说明" min-width="220" :show-overflow-tooltip="false">
      <template #default="{ row }">
        <template v-if="row.reworkNote">
          {{ row.reworkNote }}
          <div class="dim small">{{ row.reworkByName ?? '—' }} {{ formatDateTime(row.reworkTime) }}</div>
        </template>
        <span v-else class="dim">还没安排</span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="96">
      <template #default="{ row }">
        <StatusTag :tone="row.reworkTime ? 'progress' : 'danger'">{{ row.reworkTime ? '返修中' : '不合格' }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="110" fixed="right">
      <template #default="{ row }">
        <template v-if="canEdit">
          <el-button v-if="!row.reworkTime" link type="primary" @click="rework(row as QcRow)">返修</el-button>
          <el-button v-else link type="primary" @click="reworkDone(row as QcRow)">返修完成</el-button>
        </template>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.small {
  font-size: 12px;
}
</style>
