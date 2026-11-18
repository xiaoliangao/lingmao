<script lang="ts">
import type { ColumnDef } from '@/composables/useColumns'

export type ProductionColumn =
  | 'kind'
  | 'style'
  | 'factory'
  | 'qty'
  | 'amount'
  | 'due'
  | 'nodes'
  | 'kitting'
  | 'issue'
  | 'returned'
  | 'issues'
  | 'merch'
  | 'order'
  | 'unitPrice'
  | 'defect'

/**
 * 能挑的列（单号、勾选、状态总在）。list 是外发加工单 / 成品采购单列表，board 是看板"按单"：
 *   const cols = useColumns('production', productionColumns('list'))
 */
export function productionColumns(mode: 'list' | 'board'): ColumnDef<ProductionColumn>[] {
  return [
    ...(mode === 'board' ? [{ key: 'kind' as const, label: '种类' }] : []),
    { key: 'style', label: '款式' },
    { key: 'factory', label: '加工厂 · 方式' },
    { key: 'qty', label: '数量' },
    ...(mode === 'list' ? [{ key: 'amount' as const, label: '金额' }] : []),
    { key: 'due', label: '交期' },
    { key: 'nodes', label: '节点' },
    { key: 'kitting', label: '齐料' },
    ...(mode === 'list' ? [{ key: 'issue' as const, label: '领料' }] : []),
    { key: 'returned', label: '回货' },
    ...(mode === 'board' ? [{ key: 'issues' as const, label: '异常' }] : []),
    { key: 'merch', label: '跟单' },
    { key: 'order', label: '订单号', hidden: true },
    { key: 'unitPrice', label: '单价', hidden: true },
    { key: 'defect', label: '次品', hidden: true },
  ]
}
</script>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { kittingApi } from '@/api/purchase'
import StatusTag from '@/components/StatusTag.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import NodeDots from '@/components/doc/NodeDots.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import type { ColumnsState } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import {
  MATERIAL_ISSUE_STATUS,
  PRODUCTION_KIND,
  PRODUCTION_PARTIAL,
  PRODUCTION_STATUS,
  type ProductionRow,
  type ProductionStatus,
} from '@/types/production'
import { KITTING_STATUS, type OrderKitting } from '@/types/purchase'
import { formatDate, formatMoney, formatPrice, formatQty } from '@/utils/format'

/**
 * 生产单表格：外发加工单 / 成品采购单列表和看板"按单"共用（docs/api/05 §1.4，两边的行都是 ProductionRow）。
 *
 *   列表：<ProductionTable ref="tableRef" mode="list" :rows="list" :loading="loading" :columns="cols" :empty-text="emptyText"
 *                          @selection-change="selected = $event" />
 *         <BatchBar :rows="selected" :table="tableRef" …/>      组件把 toggleRowSelection、clearSelection 暴露出来，直接当 BatchBar 的 table
 *   看板：<ProductionTable mode="board" :rows="list" :loading="loading" :columns="cols" empty-text="…" />
 *   columns 是 useColumns(页面, productionColumns(mode)) 的结果，页面把同一个传给 FilterBar
 *
 * - list：有勾选、金额、领料；board：没有这三列，多种类、异常两列
 * - 勾选、单号固定在左边，状态固定在右边；列多了横着滚
 * - 齐料由这里拿当前页的 orderId 另取（kittingApi.byOrders），只给已下单、生产中、已完成的行显示；没回来、取不到写"—"，不拖住主表
 * - 交期显示"预计交期 ?? 交期"，有预计交期时原交期划掉放下面；剩余天数的颜色按后端的 risk
 * - 回货：草稿、待审核、已作废写"—"
 * - 状态后面：有没关闭的异常一个琥珀色感叹号，上游变更了一个"变更"小图标
 * - 点一行去详情（按行上的 kind 定地址）；点勾选列不跳
 */
const props = defineProps<{
  mode: 'list' | 'board'
  rows: ProductionRow[]
  loading: boolean
  emptyText: string
  columns: ColumnsState<ProductionColumn>
}>()

const emit = defineEmits<{
  'selection-change': [rows: ProductionRow[]]
}>()

const router = useRouter()
const modes = useDict('process_mode')
const issueTypes = useDict('issue_type')

// ---------- 齐料（另取） ----------
const KITTING_STATUSES: ProductionStatus[] = ['APPROVED', 'RUNNING', 'DONE']
const kittingMap = ref<Record<string, OrderKitting>>({})
let kittingSeq = 0
watch(
  () => props.rows,
  async (rows) => {
    const seq = ++kittingSeq
    kittingMap.value = {}
    const ids = [...new Set(rows.filter((r) => KITTING_STATUSES.includes(r.status)).map((r) => r.orderId))]
    try {
      const kits = await kittingApi.byOrders(ids)
      if (seq === kittingSeq) kittingMap.value = Object.fromEntries(kits.map((k) => [k.orderId, k]))
    } catch {
      // 取不到：齐料列写"—"
    }
  },
  { immediate: true },
)
const kittingOf = (row: ProductionRow) => (KITTING_STATUSES.includes(row.status) ? kittingMap.value[row.orderId] : undefined)

const statusOf = (row: ProductionRow) => (row.status === 'RUNNING' && row.returnedQty > 0 ? PRODUCTION_PARTIAL : PRODUCTION_STATUS[row.status])
/** 回货进度：审核以后才有 */
const showReturned = (row: ProductionRow) => !['DRAFT', 'PENDING', 'VOIDED'].includes(row.status)

function onRowClick(row: ProductionRow, col: TableColumnCtx<ProductionRow> | null) {
  if (col?.type !== 'selection') void router.push(`${PRODUCTION_KIND[row.kind].path}/${row.id}`)
}

const tableRef = ref<TableInstance>()
defineExpose({
  toggleRowSelection: (row: ProductionRow, selected?: boolean) => tableRef.value?.toggleRowSelection(row, selected),
  clearSelection: () => tableRef.value?.clearSelection(),
})
</script>

<template>
  <el-table
    ref="tableRef"
    v-loading="loading"
    :data="rows"
    row-key="id"
    row-class-name="clickable"
    scrollbar-always-on
    :empty-text="emptyText"
    @selection-change="emit('selection-change', $event)"
    @row-click="onRowClick"
  >
    <el-table-column v-if="mode === 'list'" type="selection" width="40" fixed="left" reserve-selection />
    <el-table-column label="单号" prop="no" width="140" fixed="left" class-name="code" />
    <el-table-column v-if="mode === 'board' && columns.show.kind" label="种类" width="96">
      <template #default="{ row }">{{ PRODUCTION_KIND[(row as ProductionRow).kind].short }}</template>
    </el-table-column>
    <el-table-column v-if="columns.show.order" label="订单号" width="140" class-name="code" prop="orderNo" />
    <el-table-column v-if="columns.show.style" label="款式" min-width="180">
      <template #default="{ row }">
        <span class="st-cell">
          <img v-if="row.image" :src="row.image.thumbUrl ?? row.image.url" alt="" class="thumb" loading="lazy" />
          <span v-else class="thumb blank" />
          <span>
            <span class="code">{{ row.styleCode }}</span>
            <div class="dim small">{{ row.styleName }}</div>
          </span>
        </span>
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.factory" label="加工厂 · 方式" width="140">
      <template #default="{ row }">
        <div>{{ row.factoryShortName }}</div>
        <div class="dim small">{{ modes.labelOf(row.processMode) }}</div>
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.qty" label="数量" width="90" class-name="num" align="right">
      <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
    </el-table-column>
    <el-table-column v-if="columns.show.unitPrice" label="单价" width="100" class-name="num" align="right">
      <template #default="{ row }">{{ formatPrice(row.unitPrice) }}</template>
    </el-table-column>
    <el-table-column v-if="mode === 'list' && columns.show.amount" label="金额" width="120" class-name="num" align="right">
      <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
    </el-table-column>
    <el-table-column v-if="columns.show.due" label="交期" width="130" class-name="num">
      <template #default="{ row }">
        <div>{{ formatDate(row.expectDate ?? row.dueDate) }}</div>
        <del v-if="row.expectDate" class="dim small">{{ formatDate(row.dueDate) }}</del>
        <DaysLeftTag :days-left="row.daysLeft" :risk="row.risk" />
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.nodes" label="节点" width="150">
      <template #default="{ row }">
        <NodeDots :nodes="row.nodes" :current-name="row.currentNodeName" />
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.kitting" label="齐料" width="140">
      <template #default="{ row }">
        <StatusTag v-if="kittingOf(row as ProductionRow)" :tone="KITTING_STATUS[kittingOf(row as ProductionRow)!.status].tone">{{ kittingOf(row as ProductionRow)!.label }}</StatusTag>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="mode === 'list' && columns.show.issue" label="领料" width="112">
      <template #default="{ row }">
        <StatusTag v-if="row.issueStatus" :tone="MATERIAL_ISSUE_STATUS[(row as ProductionRow).issueStatus!].tone">{{ MATERIAL_ISSUE_STATUS[(row as ProductionRow).issueStatus!].label }}</StatusTag>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.returned" label="回货" width="112">
      <template #default="{ row }">
        <QtyProgress v-if="showReturned(row as ProductionRow)" :done="row.returnedQty" :total="row.totalQty" tone="done" />
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.defect" label="次品" width="90" class-name="num" align="right">
      <template #default="{ row }">{{ row.defectQty ? formatQty(row.defectQty) : '—' }}</template>
    </el-table-column>
    <el-table-column v-if="mode === 'board' && columns.show.issues" label="异常" min-width="120">
      <template #default="{ row }">
        <span v-if="row.openIssueCount > 0" class="danger-txt">
          {{ issueTypes.labelOf(row.firstOpenIssueType) }}<template v-if="row.openIssueCount > 1"> 等 {{ row.openIssueCount }} 个</template>
        </span>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="columns.show.merch" label="跟单" width="96">
      <template #default="{ row }">{{ row.merchName ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="160" fixed="right">
      <template #default="{ row }">
        <span class="st-flags">
          <StatusTag :tone="statusOf(row as ProductionRow).tone">{{ statusOf(row as ProductionRow).label }}</StatusTag>
          <el-icon v-if="row.risk.issue" class="flag-issue" title="有没关闭的异常"><WarningFilled /></el-icon>
          <el-icon v-if="row.risk.upstream" class="flag-change" title="上游订单变更了，还没处理"><Switch /></el-icon>
        </span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.st-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.thumb {
  flex: none;
  width: 28px;
  height: 28px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.thumb.blank {
  background: var(--lm-greige-2);
}

.small {
  font-size: 11.5px;
}

.danger-txt {
  color: var(--lm-danger-text);
}

.st-flags {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.flag-issue {
  color: var(--lm-pending-text);
}

.flag-change {
  color: var(--lm-accent);
}

:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
