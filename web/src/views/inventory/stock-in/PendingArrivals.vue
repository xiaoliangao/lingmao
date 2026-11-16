<script setup lang="ts">
import { computed, onActivated, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
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
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import type { PendingArrivalCounts, PendingArrivalRow } from '@/types/purchase'
import { formatDate, formatDateRange, formatQty } from '@/utils/format'
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
 * - 勾几行批量入库：按采购单分组，每组建一张草稿（按未入库数）；一组失败不影响别的组，失败的列出原因、留着勾
 */
const emit = defineEmits<{
  /** 建了入库单（草稿或已过账）：入库单页签跟着刷新 */
  received: []
}>()

const route = useRoute()
const units = useDict('unit')

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
  { label: `赶不上开裁 ${countText(counts.value?.cut)}`, value: 'CUT' },
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

// ---------- 跟进 ----------
async function saveFollow(row: PendingArrivalRow) {
  await purchaseApi.follow(row.purchaseOrderId, row.purchaseItemId, { expectDate: row.expectDate, followNote: orNull(row.followNote ?? '') })
  ElMessage.success('跟进存好了')
  // 预计到货变了，逾期、临期跟着变
  void load()
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
        const res = await invDocApi.fromPurchase(
          poId,
          {
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
          },
          { silent: true },
        )
        created.push(res.no)
        // 这一组成了：取消勾；失败的组留着勾，处理好再点一次
        for (const r of groupRows) tableRef.value?.toggleRowSelection(r, false)
      } catch (e) {
        if (!(e instanceof ApiError)) throw e
        failed.push({ no: groupRows[0]?.purchaseNo ?? String(poId), message: e.message })
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
    <FilterBar v-model:keyword="query.keyword" placeholder="采购单号、物料、供应商、订单号、款号" @search="search">
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

    <el-table ref="tableRef" v-loading="loading" :data="list" row-key="purchaseItemId" :empty-text="emptyText" @selection-change="picked = $event">
      <el-table-column v-if="anyReceivable" type="selection" width="40" reserve-selection :selectable="canPick" />
      <el-table-column label="采购单" width="150">
        <template #default="{ row }">
          <router-link :to="`/purchases/${row.purchaseOrderId}`" class="code">{{ row.purchaseNo }}</router-link>
          <div class="dim small">{{ row.buyerName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="供应商" prop="supplierName" width="110" show-overflow-tooltip />
      <el-table-column label="物料" min-width="180">
        <template #default="{ row }">
          <span class="code">{{ row.skuCode }}</span>
          <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
        </template>
      </el-table-column>
      <el-table-column label="订单 / 款" min-width="140">
        <template #default="{ row }">
          <span v-if="sourceText(row as PendingArrivalRow)" class="code small">{{ sourceText(row as PendingArrivalRow) }}</span>
          <span v-else class="dim">备货</span>
        </template>
      </el-table-column>
      <el-table-column label="已到 / 采购" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.receivedQty - row.returnedQty) }} / {{ formatQty(row.qty) }}</template>
      </el-table-column>
      <el-table-column label="未到" width="96" class-name="num" align="right">
        <template #default="{ row }"><strong>{{ formatQty(row.pendingQty) }}</strong> {{ units.labelOf(row.purchaseUnit) }}</template>
      </el-table-column>
      <el-table-column label="交期" width="110" class-name="num">
        <template #default="{ row }">
          {{ formatDate(row.dueDate) }}
          <div><DaysLeftTag :days-left="row.daysLeft" :risk="row.risk" /></div>
        </template>
      </el-table-column>
      <el-table-column label="预计到货" width="140">
        <template #default="{ row }">
          <el-date-picker
            v-if="canFollow(row as PendingArrivalRow)"
            v-model="row.expectDate"
            size="small"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="同交期"
            @change="saveFollow(row as PendingArrivalRow)"
          />
          <span v-else class="num">{{ row.expectDate ? formatDate(row.expectDate) : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="要料日期" width="120" class-name="num">
        <template #default="{ row }">
          <template v-if="row.needDate">{{ formatDate(row.needDate) }}</template>
          <el-tooltip v-else content="没被算料用上的备货采购没有要料日期" placement="top">
            <span class="dim">—</span>
          </el-tooltip>
          <div v-if="row.cutLateDays > 0" class="danger small">赶不上开裁，晚 {{ row.cutLateDays }} 天</div>
        </template>
      </el-table-column>
      <el-table-column label="跟进" min-width="150">
        <template #default="{ row }">
          <el-input
            v-if="canFollow(row as PendingArrivalRow)"
            v-model="row.followNote"
            size="small"
            maxlength="100"
            placeholder="催货记录"
            @change="saveFollow(row as PendingArrivalRow)"
          />
          <span v-else :class="{ dim: !row.followNote }">{{ row.followNote || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="64" align="right">
        <template #default="{ row }">
          <el-button v-if="canReceive(row as PendingArrivalRow)" link type="primary" @click="receiveOne(row as PendingArrivalRow)">入库</el-button>
        </template>
      </el-table-column>
    </el-table>

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

.danger {
  color: var(--lm-danger-text);
}
</style>
