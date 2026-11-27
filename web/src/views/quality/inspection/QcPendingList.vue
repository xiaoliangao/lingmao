<script setup lang="ts">
import { computed, onActivated } from 'vue'
import { qcApi } from '@/api/quality'
import FilterBar from '@/components/FilterBar.vue'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import { QC_KIND, type QcKind, type QcPendingRow } from '@/types/quality'
import { formatDate, formatQty } from '@/utils/format'
import { orderLink, sourceLink } from './qcLinks'

/**
 * 质检页的"待检"页签：最近 30 天已审核还没验的采购入库、回货，加上返修完成等再验的回货（排在最前）。
 *
 *   <QcPendingList ref="pendingRef" @inspect="(kind, sourceId) => …" />
 */
const emit = defineEmits<{ inspect: [kind: QcKind, sourceId: string] }>()
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('quality:inspection:edit'))
const unitLabel = useDict('unit').labelOf

const KIND_OPTIONS = [
  { label: '全部', value: '' },
  { label: '回货', value: 'FINAL' },
  { label: '来料', value: 'INCOMING' },
]

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: qcApi.pending,
  query: { keyword: '', kind: '' as '' | 'FINAL' | 'INCOMING' },
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

const emptyText = computed(() => (query.keyword.trim() || query.kind
  ? '没有符合条件的待检单据：清空筛选看全部'
  : '没有待检的：最近 30 天审核过的采购入库、登记的回货都验过了'))

defineExpose({ reload: load })
</script>

<template>
  <div>
    <FilterBar v-model:keyword="query.keyword" placeholder="来源单号、生产单号、供应商、货品" @search="search">
      <template #left>
        <el-segmented v-model="query.kind" :options="KIND_OPTIONS" @change="search" />
      </template>
    </FilterBar>

    <el-table v-loading="loading" :data="list" :row-key="(r: QcPendingRow) => `${r.kind}-${r.sourceId}`" scrollbar-always-on :empty-text="emptyText">
      <el-table-column label="来源单" width="170" fixed="left">
        <template #default="{ row }">
          <router-link v-if="sourceLink(row as QcPendingRow)" class="code link" :to="sourceLink(row as QcPendingRow)!">{{ row.sourceNo }}</router-link>
          <span v-else class="code">{{ row.sourceNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          {{ QC_KIND[(row as QcPendingRow).kind] }}
          <span v-if="(row as QcPendingRow).attemptNo > 1" class="flag">第 {{ row.attemptNo }} 次</span>
        </template>
      </el-table-column>
      <el-table-column label="供应商" min-width="130" prop="partnerName" />
      <el-table-column label="货品" min-width="200" prop="targetDesc" />
      <el-table-column label="生产单" width="160">
        <template #default="{ row }">
          <router-link v-if="orderLink(row as QcPendingRow)" class="code link" :to="orderLink(row as QcPendingRow)!">{{ row.prdOrderNo }}</router-link>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column label="批量" width="130" class-name="num" align="right">
        <template #default="{ row }">
          <template v-if="row.lotQty != null">{{ formatQty(row.lotQty) }} {{ unitLabel(row.unit) }}</template>
          <span v-else class="dim">几种单位</span>
        </template>
      </el-table-column>
      <el-table-column label="到货" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.sourceDate) }}</template>
      </el-table-column>
      <el-table-column label="主体" width="120" prop="companyName" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit" link type="primary" @click="emit('inspect', row.kind, row.sourceId)">验货</el-button>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination" layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes" :total="total" />
  </div>
</template>

<style scoped>
.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.flag {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: var(--lm-radius-sm);
  background: var(--lm-pending-bg);
  color: var(--lm-pending-text);
  font-size: 12px;
}
</style>
