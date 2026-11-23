<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TableInstance } from 'element-plus'
import { fgDocApi } from '@/api/fg'
import { ApiError } from '@/utils/request'
import FgReadFeedback from '../FgReadFeedback.vue'
import { onFgIdentityChange, readFgPage } from '../fgRead'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useColumns } from '@/composables/useColumns'
import { useTable } from '@/composables/useTable'
import { useUserStore } from '@/stores/user'
import type { FgPendingReceiptRow } from '@/types/fg'
import { PRODUCTION_KIND } from '@/types/production'
import { newClientAction } from '@/utils/clientAction'
import { formatDate, formatDateRange, formatQty } from '@/utils/format'
import ReceiveDialog from './ReceiveDialog.vue'

/**
 * 成品入库单页的"待入库"页签（docs/api/07 §1.3、§3.3）：已登记、还有良品没入成品仓的回货单。
 *
 *   <PendingReceipts @received="docList?.reload()" />
 *
 * - 行上"入库"开 ReceiveDialog（矩阵能改少）；勾几行批量"入库"：逐张调生成接口、每行还没入的全入，存成草稿
 *   （自己建的单不能自己审核过账，甲方 11-21 答 Q10），没成的列出原因、留着勾
 * - 只列启用成品模块以后登记的回货（之前的当作已经在仓里，走"其他入库 · 期初"）
 */
const emit = defineEmits<{
  /** 建了入库单：入库单页签跟着刷新 */
  received: []
}>()

const props = withDefaults(defineProps<{ companyId: string | null; active?: boolean }>(), { active: true })
const router = useRouter()
const user = useUserStore()

const cols = useColumns('fg-in-pending', [
  { key: 'prdOrder', label: '生产单' },
  { key: 'style', label: '款式' },
  { key: 'factory', label: '加工厂' },
  { key: 'qty', label: '回货' },
  { key: 'defect', label: '次品' },
  { key: 'good', label: '良品' },
  { key: 'stocked', label: '已过账' },
  { key: 'reserved', label: '草稿占用' },
  { key: 'remain', label: '还没入' },
  { key: 'warehouse', label: '收货仓' },
  { key: 'date', label: '回货日期' },
])

const dateRange = ref<[string, string] | null>(null)
const factoryName = ref<string | null>(null)
const tableRef = ref<TableInstance>()
const selected = ref<FgPendingReceiptRow[]>([])

const { query, page, size, pageSizes, list, total, loading, error, cancel, load, search } = useTable({
  immediate: false,
  fetch: (params) => readFgPage(fgDocApi.pendingReceipts(params, { silent: true })),
  query: {
    companyId: props.companyId,
    keyword: '',
    factoryId: undefined as string | null | undefined,
    receiptDateFrom: undefined as string | undefined,
    receiptDateTo: undefined as string | undefined,
  },
})

function clearSelection() { selected.value = []; tableRef.value?.clearSelection() }
watch([page, size, list], clearSelection, { flush: 'sync' })
watch(query, clearSelection, { deep: true, flush: 'sync' })
function resetScope() { cancel(); clearSelection(); list.value = []; total.value = 0; error.value = null; receiveVisible.value = false }
onFgIdentityChange(resetScope)
watch(() => [props.companyId, props.active] as const, () => {
  resetScope()
  query.companyId = props.companyId
  if (props.active && props.companyId) search()
}, { flush: 'sync' })
onMounted(() => { if (props.active && props.companyId) void load() })
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (props.active && props.companyId) void load()
})

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.receiptDateFrom = val?.[0]
  query.receiptDateTo = val?.[1]
}

const filtered = computed(() => !!(query.keyword.trim() || query.factoryId || query.receiptDateFrom))
const emptyText = computed(() => (filtered.value ? '没有符合条件的回货：清空筛选再看' : '回货都入库了'))

// ---------- 入库 ----------
const receiveVisible = ref(false)
const receiveId = ref<string | null>(null)

function openReceive(row: FgPendingReceiptRow) {
  if (loading.value || error.value || row.companyId !== props.companyId) return
  receiveId.value = row.receiptId
  receiveVisible.value = true
}

function onCreated(id: string, companyId: string) {
  emit('received')
  void load()
  void router.push(`/fg-in/${id}?companyId=${companyId}`)
}

const batchActions: BatchAction<FgPendingReceiptRow>[] = [
  {
    label: '入库',
    perm: 'fg:doc:add',
    confirm: '每张回货单还没入的良品全入，入仓用回货单的收货仓（不是成品仓的用第一个成品仓），存成草稿，要别人审核过账才加库存。',
    run: (r, o) => {
      if (!props.active || props.companyId !== r.companyId || !selected.value.includes(r)) throw new ApiError(409, '查询已变化，请重新选择当前页回货单')
      return fgDocApi.fromReceipt(r.receiptId, newClientAction({ companyId: r.companyId, post: false, docDate: null, warehouseId: null, remark: null, items: null }), { ...o, companyId: r.companyId })
    },
  },
]

function onBatchDone() {
  emit('received')
  void load()
}

const canAdd = computed(() => user.hasPerm('fg:doc:add'))
</script>

<template>
  <div>
    <FilterBar v-model:keyword="query.keyword" placeholder="回货单号、生产单号、款号" :columns="cols" @search="search">
      <FilterItem label="加工厂" :text="query.factoryId ? (factoryName ?? '已选') : ''" @clear="query.factoryId = undefined">
        <SupplierSelect v-model="query.factoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" clearable placeholder="全部加工厂" />
      </FilterItem>
      <FilterItem label="回货日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
    </FilterBar>

    <p v-if="!companyId" class="page dim">请先选择主体，再查询待入库回货。</p>
    <p v-else-if="error" class="page"><FgReadFeedback :error="error" :loading="loading" label="待入库回货" @retry="load" /></p>
    <el-table
      v-else
      ref="tableRef"
      v-loading="loading"
      :data="list"
      row-key="receiptId"
      scrollbar-always-on
      :empty-text="emptyText"
      @selection-change="selected = ($event as FgPendingReceiptRow[]).filter((r) => list.some((item) => item.receiptId === r.receiptId)).slice(0, 100)"
    >
      <el-table-column type="selection" width="40" fixed="left" />
      <el-table-column label="回货单" width="140" fixed="left">
        <template #default="{ row }"><span class="code">{{ row.receiptNo }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.prdOrder" label="生产单" width="140">
        <template #default="{ row }">
          <router-link v-if="user.hasPerm('production:order:list')" class="code link" :to="`${PRODUCTION_KIND[(row as FgPendingReceiptRow).prdOrderKind].path}/${row.prdOrderId}`">{{ row.prdOrderNo }}</router-link>
          <span v-else class="code">{{ row.prdOrderNo }}</span>
          <div class="dim small">{{ PRODUCTION_KIND[(row as FgPendingReceiptRow).prdOrderKind].label }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.style" label="款式" min-width="180">
        <template #default="{ row }">
          <div class="style-cell">
            <ThumbImage v-if="row.image" :src="row.image.thumbUrl" alt="" class="thumb" loading="lazy" />
            <span class="code">{{ row.styleCode }}</span>
            <span class="dim">{{ row.styleName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.factory" label="加工厂" width="120" prop="factoryName" />
      <el-table-column v-if="cols.show.qty" label="回货" width="80" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.qty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.defect" label="次品" width="72" class-name="num" align="right">
        <template #default="{ row }">{{ row.defectQty ? formatQty(row.defectQty) : '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.good" label="良品" width="80" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.goodQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.stocked" label="已过账" width="80" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.postedQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.reserved" label="草稿占用" width="90" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.reservedQty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.remain" label="还没入" width="80" class-name="num" align="right">
        <template #default="{ row }"><strong>{{ formatQty(row.remainingQty) }}</strong></template>
      </el-table-column>
      <el-table-column v-if="cols.show.warehouse" label="收货仓" width="120">
        <template #default="{ row }">{{ row.warehouseName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.date" label="回货日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.receiptDate) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right" align="right">
        <template #default="{ row }">
          <el-button v-if="canAdd" link type="primary" @click="openReceive(row as FgPendingReceiptRow)">入库</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="companyId && !error" class="selection-note dim">勾选仅限当前页</p>
    <el-pagination
      v-if="companyId && !error"
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />

    <BatchBar :key="companyId ?? 'none'" :rows="loading || error ? [] : selected" :table="tableRef" unit="张" :actions="batchActions" @done="onBatchDone" />

    <ReceiveDialog v-model="receiveVisible" :receipt-id="receiveId" :company-id="companyId" @created="onCreated" />
  </div>
</template>

<style scoped>
.selection-note { padding: 0 24px; margin: 8px 0 0; font-size: 12px; }
.style-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.style-cell .dim {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thumb {
  flex: none;
  width: 24px;
  height: 24px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.small {
  font-size: 11.5px;
}
</style>
