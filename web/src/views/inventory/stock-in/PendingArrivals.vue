<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type InputInstance, type TableColumnCtx, type TableInstance } from 'element-plus'
import { invDocApi } from '@/api/inventory'
import { pendingArrivalApi, purchaseApi } from '@/api/purchase'
import BatchBar from '@/components/BatchBar.vue'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import SourceInvDocDialog from '@/components/doc/SourceInvDocDialog.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { PURCHASE_TYPE_LABEL, type PendingArrivalCounts, type PendingArrivalRow } from '@/types/purchase'
import { formatDate, formatDateRange, formatQty } from '@/utils/format'
import { newClientAction, unknownActionResult } from '@/utils/clientAction'
import type { InvDocFromSourceReq } from '@/types/inventory'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'

/**
 * 物料入库单页的"待到料"页签（docs/api/04 §1.5、03 §3.4）：已审核、部分到货的采购单里没入齐的行。
 *
 *   <PendingArrivals @received="docList?.reload()" />
 *
 * - 认地址上的 ?risk=LATE|NEAR|CUT（首页待办点进来），只认 /stock-in 自己的地址
 * - 页签上的数单独取，取不到写"—"，不影响列表
 * - 行上的"入库"、改预计到货和跟进看行上的 actions（receive、follow），不只看权限
 * - 预计到货、跟进在格子里只显示；能改的点格子（或鼠标移上去出的小铅笔）弹小框改，点"保存"才存
 * - 勾几行批量入库：按采购单分组，每组建一张草稿（按未入库数）；一组失败不影响别的组，失败的列出原因、留着勾
 */
const emit = defineEmits<{
  /** 建了入库单（草稿或已过账）：入库单页签跟着刷新 */
  received: []
}>()

const route = useRoute()
const units = useDict('unit')

// 采购单号、勾选、入库按钮总在；采购类型、算料单、交货地点默认不显示，在"显示列"里勾
const cols = useColumns('stock-in-pending', [
  { key: 'supplier', label: '供应商' },
  { key: 'material', label: '物料' },
  { key: 'source', label: '订单 / 款' },
  { key: 'received', label: '已到 / 采购' },
  { key: 'pending', label: '未到' },
  { key: 'due', label: '交期' },
  { key: 'expect', label: '预计到货' },
  { key: 'need', label: '要料日期' },
  { key: 'follow', label: '跟进' },
  { key: 'purchaseType', label: '采购类型', hidden: true },
  { key: 'calc', label: '算料单', hidden: true },
  { key: 'warehouse', label: '交货地点', hidden: true },
])

type RiskTab = '' | 'LATE' | 'NEAR' | 'CUT'
const RISKS: RiskTab[] = ['LATE', 'NEAR', 'CUT']
const pickRisk = (value: unknown): RiskTab => (RISKS.includes(value as RiskTab) ? (value as RiskTab) : '')

const counts = ref<PendingArrivalCounts | null>(null)
const dateRange = ref<[string, string] | null>(null)
const supplierName = ref<string | null>(null)
const buyerName = ref<string | null>(null)
const tableRef = ref<TableInstance>()
const picked = ref<PendingArrivalRow[]>([])
const receiving = ref(false)
let countSeq = 0

/** 页签上的数：和列表分开取，失败了写"—" */
async function loadCounts(params: Parameters<typeof pendingArrivalApi.counts>[0]) {
  const seq = ++countSeq
  try {
    const res = await pendingArrivalApi.counts(params)
    if (seq === countSeq) counts.value = res
  } catch {
    if (seq === countSeq) counts.value = null
  }
}

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  fetch: ({ riskTab, ...rest }) => {
    void loadCounts(rest)
    return pendingArrivalApi.page({ ...rest, tab: riskTab || undefined })
  },
  query: {
    keyword: '',
    riskTab: pickRisk(route.query.risk),
    supplierId: undefined as number | null | undefined,
    buyerId: undefined as number | null | undefined,
    dueDateFrom: undefined as string | undefined,
    dueDateTo: undefined as string | undefined,
  },
})

watch(
  () => route.query.risk,
  (value) => {
    if (route.path !== '/stock-in') return
    const risk = pickRisk(value)
    if (risk !== query.riskTab) {
      query.riskTab = risk
      search()
    }
  },
)

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

const countText = (n: number | undefined) => (n === undefined ? '—' : String(n))
const riskOptions = computed(() => [
  { label: `全部 ${countText(counts.value?.all)}`, value: '' },
  { label: `已逾期 ${countText(counts.value?.late)}`, value: 'LATE' },
  { label: `3 天内 ${countText(counts.value?.near)}`, value: 'NEAR' },
  { label: `晚于开裁 ${countText(counts.value?.cut)}`, value: 'CUT' },
])

const filtered = computed(() => !!(query.keyword.trim() || query.riskTab || query.supplierId || query.buyerId || query.dueDateFrom))
const emptyText = computed(() => (filtered.value ? '没有符合条件的待到料：换个页签，或者清空筛选' : '没有待到料：采购单审核后，没入齐的行会列在这里'))

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.dueDateFrom = val?.[0]
  query.dueDateTo = val?.[1]
}

const canReceive = (row: PendingArrivalRow) => row.actions.includes('receive')
const canFollow = (row: PendingArrivalRow) => row.actions.includes('follow')
const canPick = (row: PendingArrivalRow) => canReceive(row)
const anyReceivable = computed(() => list.value.some(canReceive))

// ---------- 改预计到货、跟进：点格子弹小框 ----------
type FollowField = 'expect' | 'note'

/** 正在改的那一格；小框挂在这一格（td）上 */
const editing = ref<{ row: PendingArrivalRow; field: FollowField } | null>(null)
const editCell = ref<HTMLElement>()
const draft = reactive({ expectDate: null as string | null, followNote: '' })
/** 打开时的样子：改过了点外面不关，免得白写 */
let draftOpened = ''
const draftDirty = () => JSON.stringify(draft) !== draftOpened
const savingFollow = ref(false)
const noteInput = ref<InputInstance>()
/** 日历的 v-model 不收 null："空着"在日历上是 ''，存的是 null（按交期算） */
const draftDate = computed({
  get: () => draft.expectDate ?? '',
  set: (value: unknown) => {
    draft.expectDate = typeof value === 'string' && value ? value : null
  },
})

const followField = (column: TableColumnCtx<PendingArrivalRow>): FollowField | null =>
  column.columnKey === 'expect' || column.columnKey === 'note' ? column.columnKey : null

/** 能改的两格：手指光标 */
function followCellClass({ row, column }: { row: PendingArrivalRow; column: TableColumnCtx<PendingArrivalRow> }) {
  return followField(column) && canFollow(row) ? 'follow-edit' : ''
}

function onCellClick(row: PendingArrivalRow, column: TableColumnCtx<PendingArrivalRow>, cell: HTMLTableCellElement) {
  const field = followField(column)
  if (!field || !canFollow(row)) return
  const current = editing.value
  if (current) {
    // 改过了：留在原来那一格，点"取消"才丢掉；再点一下同一格：关掉
    if (draftDirty()) return
    editing.value = null
    if (current.row === row && current.field === field) return
  }
  draft.expectDate = row.expectDate
  draft.followNote = row.followNote ?? ''
  draftOpened = JSON.stringify(draft)
  editCell.value = cell
  editing.value = { row, field }
  if (field === 'note') void nextTick(() => noteInput.value?.focus())
}

function closeEdit() {
  editing.value = null
}

/** 点在小框外面就关；改过了不关。点格子交给 onCellClick */
function onPointerDown(event: PointerEvent) {
  if (!editing.value) return
  const target = event.target as Element | null
  if (!target || editCell.value?.contains(target) || target.closest('.el-popper')) return
  if (!draftDirty()) editing.value = null
}
document.addEventListener('pointerdown', onPointerDown, true)
onBeforeUnmount(() => document.removeEventListener('pointerdown', onPointerDown, true))

// 表格重新取了（翻页、筛选、存完）、切走了：原来那一格没了，小框跟着关
watch(list, closeEdit)
onDeactivated(closeEdit)

async function saveFollow() {
  const current = editing.value
  if (!current || savingFollow.value) return
  savingFollow.value = true
  try {
    const { row } = current
    await purchaseApi.follow(row.purchaseOrderId, row.purchaseItemId, { expectDate: draft.expectDate, followNote: orNull(draft.followNote) })
    ElMessage.success(current.field === 'expect' ? '预计到货存好了' : '跟进存好了')
    editing.value = null
    // 预计到货变了，逾期、临期跟着变
    void load()
  } finally {
    savingFollow.value = false
  }
}

// ---------- 单行入库 ----------
const receiveVisible = ref(false)
const receivePoId = ref<number | null>(null)
const receiveItemIds = ref<number[]>([])

function receiveOne(row: PendingArrivalRow) {
  receivePoId.value = row.purchaseOrderId
  receiveItemIds.value = [row.purchaseItemId]
  receiveVisible.value = true
}

function onCreated() {
  void load()
  emit('received')
}

// ---------- 批量入库 ----------
const pendingBatch = new Map<number, InvDocFromSourceReq>()
async function receivePicked() {
  const rows = picked.value.filter(canReceive)
  if (!rows.length) return
  const groups = new Map<number, PendingArrivalRow[]>()
  for (const r of rows) groups.set(r.purchaseOrderId, [...(groups.get(r.purchaseOrderId) ?? []), r])
  receiving.value = true
  const created: string[] = []
  const failed: { no: string; message: string }[] = []
  try {
    for (const [poId, groupRows] of groups) {
      try {
        const req = pendingBatch.get(poId) ?? newClientAction({
            docType: 'PURCHASE_IN',
            docDate: null,
            fromWarehouseId: null,
            toWarehouseId: null,
            supplierDeliveryNo: null,
            logisticsNo: null,
            reason: null,
            remark: null,
            items: null,
            itemIds: groupRows.map((r) => r.purchaseItemId),
            post: false,
          } satisfies Omit<InvDocFromSourceReq, 'clientActionId'>)
        pendingBatch.set(poId, req)
        const res = await invDocApi.fromPurchase(poId, req, { silent: true })
        pendingBatch.delete(poId)
        created.push(res.no)
        // 这一组成了：取消勾；失败的组留着勾，处理好再点一次
        for (const r of groupRows) tableRef.value?.toggleRowSelection(r, false)
      } catch (e) {
        if (!(e instanceof ApiError)) throw e
        if (!unknownActionResult(e.code)) pendingBatch.delete(poId)
        failed.push({ no: groupRows[0]?.purchaseNo ?? String(poId), message: `${e.message}${pendingBatch.has(poId) ? '；结果未知，再点批量入库会重试此采购单的原登记' : ''}` })
      }
    }
  } finally {
    receiving.value = false
  }
  if (created.length) {
    ElMessage.success(`建了 ${created.length} 张入库单草稿：去「入库单」页签里改实收数、填缸号后审核`)
    void load()
    emit('received')
  }
  if (failed.length) {
    await ElMessageBox.alert(failed.map((f) => `${f.no}：${f.message}`).join('\n'), `${failed.length} 张采购单没建成`, {
      confirmButtonText: '知道了',
    }).catch(() => undefined)
  }
}

const sourceText = (row: PendingArrivalRow) => [...row.orderNos, ...row.styleCodes].join(' · ')
</script>

<template>
  <div>
    <FilterBar v-model:keyword="query.keyword" placeholder="采购单号、物料、供应商、订单号、款号" :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.riskTab" :options="riskOptions" @change="search" />
      </template>
      <FilterItem label="供应商" :text="query.supplierId ? (supplierName ?? '已选') : ''" @clear="query.supplierId = undefined">
        <SupplierSelect v-model="query.supplierId" v-model:label="supplierName" usage="BULK" clearable placeholder="全部供应商" />
      </FilterItem>
      <FilterItem label="采购员" :text="query.buyerId ? (buyerName ?? '已选') : ''" @clear="query.buyerId = undefined">
        <UserSelect v-model="query.buyerId" v-model:label="buyerName" clearable placeholder="全部采购员" />
      </FilterItem>
      <FilterItem label="交期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
    </FilterBar>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="list"
      row-key="purchaseItemId"
      scrollbar-always-on
      :cell-class-name="followCellClass"
      :empty-text="emptyText"
      @selection-change="picked = $event"
      @cell-click="onCellClick"
    >
      <el-table-column v-if="anyReceivable" type="selection" width="40" fixed="left" reserve-selection :selectable="canPick" />
      <el-table-column label="采购单" width="140" fixed="left">
        <template #default="{ row }">
          <router-link :to="`/purchases/${row.purchaseOrderId}`" class="code">{{ row.purchaseNo }}</router-link>
          <div class="dim small">{{ row.buyerName }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.purchaseType" label="采购类型" width="96">
        <template #default="{ row }">{{ PURCHASE_TYPE_LABEL[(row as PendingArrivalRow).purchaseType] }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.supplier" label="供应商" prop="supplierName" width="120" />
      <el-table-column v-if="cols.show.material" label="物料" min-width="200">
        <template #default="{ row }">
          <span class="code">{{ row.skuCode }}</span>
          <div class="dim small line">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.source" label="订单 / 款" min-width="160">
        <template #default="{ row }">
          <span v-if="sourceText(row as PendingArrivalRow)" class="code small">{{ sourceText(row as PendingArrivalRow) }}</span>
          <span v-else class="dim">备货</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.calc" label="算料单" width="140" class-name="code">
        <template #default="{ row }">{{ row.calcNo ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.warehouse" label="交货地点" min-width="130" prop="warehouseName" />
      <el-table-column v-if="cols.show.received" label="已到 / 采购" width="130" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.receivedQty - row.returnedQty) }} / {{ formatQty(row.qty) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.pending" label="未到" width="110" class-name="num" align="right">
        <template #default="{ row }"><strong>{{ formatQty(row.pendingQty) }}</strong> {{ units.labelOf(row.purchaseUnit) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.due" label="交期" width="110" class-name="num">
        <template #default="{ row }">
          {{ formatDate(row.dueDate) }}
          <div><DaysLeftTag :days-left="row.daysLeft" :risk="row.risk" /></div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.expect" label="预计到货" width="136" class-name="num" column-key="expect">
        <template #default="{ row }">
          <template v-if="row.expectDate">{{ formatDate(row.expectDate) }}</template>
          <span v-else class="dim">—</span>
          <template v-if="canFollow(row as PendingArrivalRow)">
            <span v-if="!row.expectDate" class="fill">填</span>
            <button type="button" class="pen" :aria-label="`改 ${row.purchaseNo} ${row.skuCode} 的预计到货`"><el-icon><EditPen /></el-icon></button>
          </template>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.need" label="要料日期" width="150" class-name="num">
        <template #default="{ row }">
          <template v-if="row.needDate">{{ formatDate(row.needDate) }}</template>
          <el-tooltip v-else content="没被算料用上的备货采购没有要料日期" placement="top">
            <span class="dim">—</span>
          </el-tooltip>
          <div v-if="row.cutLateDays > 0" class="danger small">晚于开裁 {{ row.cutLateDays }} 天</div>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.follow" label="跟进" min-width="160" column-key="note">
        <template #default="{ row }">
          <template v-if="row.followNote">{{ row.followNote }}</template>
          <span v-else class="dim">—</span>
          <template v-if="canFollow(row as PendingArrivalRow)">
            <span v-if="!row.followNote" class="fill">填</span>
            <button type="button" class="pen" :aria-label="`改 ${row.purchaseNo} ${row.skuCode} 的跟进`"><el-icon><EditPen /></el-icon></button>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="" width="84" fixed="right" align="right" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <el-button v-if="canReceive(row as PendingArrivalRow)" link type="primary" @click="receiveOne(row as PendingArrivalRow)">入库</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 改预计到货、跟进的小框：一个，挂在点的那一格上 -->
    <el-popover :visible="!!editing" :virtual-ref="editCell" virtual-triggering placement="bottom-start" :width="editing?.field === 'expect' ? 'auto' : 300">
      <form v-if="editing" class="follow-form" @submit.prevent="saveFollow" @keydown.esc="closeEdit">
        <header class="follow-head">
          {{ editing.field === 'expect' ? '预计到货' : '跟进' }}
          <span class="dim">{{ editing.row.purchaseNo }} · {{ editing.row.skuCode }}</span>
        </header>
        <el-date-picker-panel v-if="editing.field === 'expect'" v-model="draftDate" value-format="YYYY-MM-DD" :border="false" />
        <el-input
          v-else
          ref="noteInput"
          v-model="draft.followNote"
          type="textarea"
          :rows="3"
          maxlength="100"
          show-word-limit
          placeholder="催货记录"
          @keydown.enter.meta.prevent="saveFollow"
          @keydown.enter.ctrl.prevent="saveFollow"
        />
        <footer class="follow-foot">
          <el-button v-if="editing.field === 'expect'" size="small" text :disabled="!draft.expectDate" @click="draft.expectDate = null">清空，按交期</el-button>
          <span class="spacer" />
          <el-button size="small" @click="closeEdit">取消</el-button>
          <el-button size="small" type="primary" native-type="submit" :loading="savingFollow">保存</el-button>
        </footer>
      </form>
    </el-popover>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />

    <BatchBar :rows="picked" :table="tableRef" unit="行">
      <el-button v-perm="'inventory:doc:add'" type="primary" size="small" :loading="receiving" @click="receivePicked">入库</el-button>
    </BatchBar>

    <SourceInvDocDialog
      v-model="receiveVisible"
      source-kind="PURCHASE"
      :source-id="receivePoId"
      doc-type="PURCHASE_IN"
      :item-ids="receiveItemIds"
      @created="onCreated"
    />
  </div>
</template>

<style scoped>
.small {
  font-size: 11.5px;
}

/* 物料名太长：末尾省略号，鼠标移上去看全文（表格的"看全文"按文字的实际宽度判断，省略号不挡它） */
.line {
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 预计到货、跟进：格子里只显示，点了弹小框改 ---------- */
:deep(td.follow-edit) {
  cursor: pointer;
}

.fill {
  margin-left: 6px;
  font-size: 12px;
  color: var(--lm-muted);
}

/*
 * 小铅笔：鼠标移到这一行才出来，压在格子右边；键盘移过来也看得见。
 * 不能压到格子的内边距里（right 比 12px 再多留几 px）：表格按格子里的内容有没有超出来决定出不出"看全文"，超了就会误出
 */
.pen {
  position: absolute;
  top: 50%;
  right: 16px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 12px;
  color: var(--lm-muted);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-sm);
  opacity: 0;
  transform: translateY(-50%);
  transition: opacity 0.12s var(--lm-ease);
}

.el-table__row:hover .pen,
.pen:focus-visible {
  opacity: 1;
}

.pen:hover {
  color: var(--lm-ink);
  border-color: var(--lm-line-strong);
}

.follow-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.follow-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 13px;
  font-weight: 600;
}

.follow-head .dim {
  font-size: 12px;
  font-weight: 400;
}

/* 日历直接铺在小框里，不再多一圈内边距 */
.follow-form :deep(.el-picker-panel__body-wrapper) {
  padding: 0;
}

.follow-foot {
  display: flex;
  align-items: center;
  gap: 8px;
}

.follow-foot .el-button + .el-button {
  margin-left: 0;
}

.spacer {
  flex: 1;
}


.danger {
  color: var(--lm-danger-text);
}
</style>
