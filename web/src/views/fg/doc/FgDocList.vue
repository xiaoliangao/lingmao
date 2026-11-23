<script setup lang="ts">
import { useUserStore } from '@/stores/user'
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { TableColumnCtx, TableInstance } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { fgDocApi } from '@/api/fg'
import { ApiError } from '@/utils/request'
import FgReadFeedback from '../FgReadFeedback.vue'
import { fgReadError, onFgIdentityChange, readFgPage, useFgRead } from '../fgRead'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useFieldAccess } from '@/composables/useFieldAccess'
import { useTable } from '@/composables/useTable'
import type { WarehouseOption } from '@/types/basic'
import { FG_DOC_STATUS, FG_DOC_TYPE, type FgDocKind, type FgDocRow, type FgDocStatus, type FgDocType } from '@/types/fg'
import { formatDate, formatDateRange, formatDateTime, formatMoney, formatQty } from '@/utils/format'
/** 本人范围的人不出"我的 / 全部"（走查 T-8a56c5） */
const userScope = useUserStore()

/**
 * 成品入库单、出库单列表（docs/api/07 §1.3）：一张表按 kind 分，两个菜单页各放一个，照物料的 DocList。
 *
 *   <FgDocList ref="listRef" kind="IN" />          页头"导出"调 listRef.value?.exportAll()，建了单调 listRef.value?.reload()
 *
 * - 分段控件按类型；筛选：状态、日期、仓库，入库单多一个加工厂；款号、订单号用关键字查；我的 / 全部
 * - 认地址上的 ?status=DRAFT，只认本页（/fg-in、/fg-out）自己的地址
 * - 勾选后批量审核过账（逐条做，失败的留着勾）、导出选中
 */
const props = withDefaults(defineProps<{ kind: FgDocKind; companyId: string | null; active?: boolean }>(), { active: true })

const route = useRoute()
const router = useRouter()
const { canSeeOrderPrice } = useFieldAccess()
const isIn = computed(() => props.kind === 'IN')

const cols = useColumns(
  isIn.value ? 'fg-in' : 'fg-out',
  isIn.value
    ? [
        { key: 'type', label: '类型' },
        { key: 'warehouse', label: '入仓' },
        { key: 'source', label: '来源' },
        { key: 'style', label: '款式' },
        { key: 'qty', label: '件数' },
        { key: 'date', label: '日期' },
        { key: 'factory', label: '加工厂', hidden: true },
        { key: 'prdOrder', label: '生产单', hidden: true },
        { key: 'created', label: '制单', hidden: true },
        { key: 'audited', label: '审核人', hidden: true },
        { key: 'remark', label: '备注', hidden: true },
      ]
    : [
        { key: 'type', label: '类型' },
        { key: 'warehouse', label: '出仓' },
        { key: 'source', label: '需求订单 / 原因' },
        { key: 'shipTo', label: '发到' },
        { key: 'style', label: '款式' },
        { key: 'qty', label: '件数' },
        { key: 'logistics', label: '物流单号' },
        { key: 'date', label: '日期' },
        { key: 'amount', label: '金额', hidden: true },
        { key: 'created', label: '制单', hidden: true },
        { key: 'audited', label: '审核人', hidden: true },
        { key: 'remark', label: '备注', hidden: true },
      ],
)
const reasons = useDict(isIn.value ? 'fg_in_reason' : 'fg_out_reason')
const reasonText = (row: FgDocRow) => (row.reason ? reasons.labelOf(row.reason) : '—')

const basePath = computed(() => (isIn.value ? '/fg-in' : '/fg-out'))
const docName = computed(() => (isIn.value ? '成品入库单' : '成品出库单'))

const TYPES = (Object.keys(FG_DOC_TYPE) as FgDocType[]).filter((t) => FG_DOC_TYPE[t].kind === props.kind)
const TYPE_OPTIONS = [{ label: '全部', value: '' }, ...TYPES.map((t) => ({ label: FG_DOC_TYPE[t].label, value: t }))]
const STATUSES: FgDocStatus[] = ['DRAFT', 'APPROVED', 'VOIDED']
const pickStatus = (value: unknown): FgDocStatus | '' => (STATUSES.includes(value as FgDocStatus) ? (value as FgDocStatus) : '')

const warehouses = ref<WarehouseOption[]>([])
const warehouseLoading = ref(false)
const warehouseError = ref<string | null>(null)
function clearWarehouses() { warehouses.value = []; warehouseLoading.value = false; warehouseError.value = null }
const warehouseRead = useFgRead(clearWarehouses)
const dateRange = ref<[string, string] | null>(null)
const factoryName = ref<string | null>(null)
const tableRef = ref<TableInstance>()
const selected = ref<FgDocRow[]>([])

const { query, page, size, pageSizes, list, total, loading, error, cancel, load, search, exporting, exportFile, exportSelected } = useTable({
  immediate: false,
  normalize: ({ docTypeTab, statusTab, ...rest }) => ({ ...rest, kind: props.kind, docType: docTypeTab || undefined, status: statusTab ? [statusTab] : undefined }),
  fetch: (params) => readFgPage(fgDocApi.page(params, { silent: true })),
  exportUrl: '/api/fg-docs/export',
  exportName: `${docName.value}.xlsx`,
  query: {
    companyId: props.companyId,
    receiptId: typeof route.query.receiptId === 'string' ? route.query.receiptId : undefined,
    keyword: '',
    docTypeTab: '' as FgDocType | '',
    statusTab: pickStatus(route.query.status),
    warehouseId: undefined as string | undefined,
    factoryId: undefined as string | null | undefined,
    docDateFrom: undefined as string | undefined,
    docDateTo: undefined as string | undefined,
    mine: false,
  },
})

function clearSelection() { selected.value = []; tableRef.value?.clearSelection() }
watch([page, size, list], clearSelection, { flush: 'sync' })
watch(query, clearSelection, { deep: true, flush: 'sync' })
function resetScope() { cancel(); clearSelection(); list.value = []; total.value = 0; error.value = null; warehouseRead.cancel(); clearWarehouses() }
onFgIdentityChange(resetScope)
watch(() => [props.companyId, props.active] as const, () => {
  resetScope()
  query.companyId = props.companyId
  if (props.active && props.companyId) { search(); void loadWarehouses() }
}, { flush: 'sync' })
watch(() => route.query.receiptId, (value) => {
  if (route.path !== basePath.value) return
  query.receiptId = typeof value === 'string' ? value : undefined
  if (props.active && props.companyId) search()
})

watch(
  () => route.query.status,
  (value) => {
    if (route.path !== basePath.value) return
    const st = pickStatus(value)
    if (st !== query.statusTab) {
      query.statusTab = st
      if (props.active && props.companyId) search()
    }
  },
)

async function loadWarehouses() {
  const read = warehouseRead.begin()
  warehouses.value = []; warehouseError.value = null; warehouseLoading.value = true
  try {
    const rows = await read.run(() => warehouseApi.options('PRODUCT', { signal: read.signal, silent: true }))
    if (read.current()) warehouses.value = rows
  } catch (cause) { if (read.current()) warehouseError.value = fgReadError(cause) }
  finally { if (read.current()) warehouseLoading.value = false }
}
onMounted(() => {
  if (props.active && props.companyId) { void load(); void loadWarehouses() }
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (props.active && props.companyId) { void load(); void loadWarehouses() }
})

function onDateChange(val: [string, string] | null | undefined) {
  dateRange.value = val ?? null
  query.docDateFrom = val?.[0]
  query.docDateTo = val?.[1]
}

const warehouseText = computed(() => (query.warehouseId ? (warehouses.value.find((w) => w.id === query.warehouseId)?.name ?? '已选') : ''))
const filtered = computed(
  () => !!(query.keyword.trim() || query.docTypeTab || query.statusTab || query.warehouseId || query.factoryId || query.docDateFrom),
)
const emptyText = computed(() => {
  if (filtered.value) return `没有符合条件的${docName.value}：换个类型，或者清空筛选`
  if (query.mine) return `还没有你建的${docName.value}，切到「全部」看所有人的`
  return isIn.value
    ? '还没有成品入库单：回货在「待入库」点「入库」，期初、退回的货点「其他入库」'
    : '还没有出库单：成品仓有货以后点「发货出库」，拿样品、报废点「其他出库」'
})

function onRowClick(row: FgDocRow, column: TableColumnCtx<FgDocRow> | null) {
  if (column?.type !== 'selection' && !loading.value && !error.value) void router.push(`${basePath.value}/${row.id}?companyId=${row.companyId}`)
}

const batchActions: BatchAction<FgDocRow>[] = [
  {
    label: '审核',
    perm: 'fg:doc:audit',
    can: (r) => r.actions.includes('audit'),
    confirm: isIn.value ? '审核后马上加库存。' : '审核后马上扣库存；库存不够、超过订单能发的会失败，留着勾。',
    run: (r, o) => {
      if (!props.active || props.companyId !== r.companyId || !selected.value.includes(r)) throw new ApiError(409, '查询已变化，请重新选择当前页单据')
      return fgDocApi.act(r.id, 'audit', { version: r.version }, { ...o, companyId: r.companyId })
    },
  },
]

const exportAll = () => exportFile()
const exportPicked = () => exportSelected(selected.value.map((r) => r.id))

defineExpose({ reload: load, exportAll, exporting })
</script>

<template>
  <div>
    <FilterBar
      v-model:keyword="query.keyword"
      :placeholder="isIn ? '单号、回货单号、生产单号、款号' : '单号、订单号、物流单号、款号'"
      :columns="cols"
      @search="search"
    >
      <template #left>
        <el-segmented v-model="query.docTypeTab" :options="TYPE_OPTIONS" @change="search" />
      </template>
      <FilterItem label="状态" :text="query.statusTab ? FG_DOC_STATUS[query.statusTab].label : ''" @clear="query.statusTab = ''">
        <el-select v-model="query.statusTab" filterable clearable placeholder="全部状态" value-on-clear="">
          <el-option v-for="st in STATUSES" :key="st" :label="FG_DOC_STATUS[st].label" :value="st" />
        </el-select>
      </FilterItem>
      <FilterItem label="日期" :text="formatDateRange(dateRange)" @clear="onDateChange(null)">
        <DateRange :model-value="dateRange" @update:model-value="onDateChange" />
      </FilterItem>
      <FilterItem label="仓库" :text="warehouseText" @clear="query.warehouseId = undefined">
        <el-select v-model="query.warehouseId" filterable clearable placeholder="全部成品仓" :value-on-clear="undefined" :loading="warehouseLoading" :disabled="warehouseLoading || !!warehouseError">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </FilterItem>
      <FilterItem v-if="isIn" label="加工厂" :text="query.factoryId ? (factoryName ?? '已选') : ''" @clear="query.factoryId = undefined">
        <SupplierSelect v-model="query.factoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" clearable placeholder="全部加工厂" />
      </FilterItem>
      <template #right>
        <el-segmented v-if="userScope.hasPerm('data:all')" v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
      </template>
    </FilterBar>

    <p v-if="warehouseLoading || warehouseError" class="page"><FgReadFeedback :error="warehouseError" :loading="warehouseLoading" label="仓库选项" @retry="loadWarehouses" /></p>
    <p v-if="!companyId" class="page dim">请先选择主体，再查询成品单据。</p>
    <p v-else-if="error" class="page"><FgReadFeedback :error="error" :loading="loading" label="单据列表" @retry="load" /></p>
    <el-table
      v-else
      ref="tableRef"
      v-loading="loading"
      :data="list"
      row-key="id"
      row-class-name="clickable"
      scrollbar-always-on
      :empty-text="emptyText"
      @selection-change="selected = ($event as FgDocRow[]).filter((r) => list.some((item) => item.id === r.id)).slice(0, 100)"
      @row-click="onRowClick"
    >
      <el-table-column type="selection" width="40" fixed="left" />
      <el-table-column label="单号" prop="no" width="140" fixed="left" class-name="code" />
      <el-table-column v-if="cols.show.type" label="类型" width="96">
        <template #default="{ row }">{{ FG_DOC_TYPE[(row as FgDocRow).docType].label }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.warehouse" :label="isIn ? '入仓' : '出仓'" width="130" prop="warehouseName" />
      <el-table-column v-if="cols.show.source" :label="isIn ? '来源' : '需求订单 / 原因'" width="150">
        <template #default="{ row }">
          <span v-if="isIn && row.receiptNo" class="code">{{ row.receiptNo }}</span>
          <span v-else-if="!isIn && row.orderNo" class="code">{{ row.orderNo }}</span>
          <template v-else>{{ reasonText(row as FgDocRow) }}</template>
        </template>
      </el-table-column>
      <el-table-column v-if="!isIn && cols.show.shipTo" label="发到" min-width="140">
        <template #default="{ row }">{{ row.shipTo || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.style" label="款式" min-width="200">
        <template #default="{ row }">
          <div v-if="row.firstStyle" class="style-cell">
            <ThumbImage v-if="row.firstStyle.image" :src="row.firstStyle.image.thumbUrl" alt="" class="thumb" loading="lazy" />
            <span class="code">{{ row.firstStyle.styleCode }}</span>
            <span class="dim">{{ row.firstStyle.styleName }}<template v-if="row.styleCount > 1"> 等 {{ row.styleCount }} 款</template></span>
          </div>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.qty" label="件数" width="90" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.totalQty) }}</template>
      </el-table-column>
      <el-table-column v-if="!isIn && canSeeOrderPrice && cols.show.amount" label="金额" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ row.totalAmount != null ? formatMoney(row.totalAmount) : '—' }}</template>
      </el-table-column>
      <el-table-column v-if="!isIn && cols.show.logistics" label="物流单号" width="140">
        <template #default="{ row }"><span class="code">{{ row.logisticsNo || '—' }}</span></template>
      </el-table-column>
      <el-table-column v-if="isIn && cols.show.factory" label="加工厂" width="120">
        <template #default="{ row }">{{ row.factoryName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="isIn && cols.show.prdOrder" label="生产单" width="140">
        <template #default="{ row }"><span class="code">{{ row.prdOrderNo ?? '—' }}</span></template>
      </el-table-column>
      <el-table-column v-if="cols.show.date" label="日期" width="104" class-name="num">
        <template #default="{ row }">{{ formatDate(row.docDate) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.created" label="制单" width="190">
        <template #default="{ row }">
          {{ row.createByName ?? '—' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.audited" label="审核人" width="96">
        <template #default="{ row }">{{ row.auditByName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.remark" label="备注" min-width="140">
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="112" fixed="right">
        <template #default="{ row }">
          <StatusTag :tone="FG_DOC_STATUS[(row as FgDocRow).status].tone">{{ FG_DOC_STATUS[(row as FgDocRow).status].label }}</StatusTag>
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

    <BatchBar :key="companyId ?? 'none'" :rows="loading || error ? [] : selected" :table="tableRef" unit="张" :actions="batchActions" @done="load">
      <el-button v-perm="'fg:doc:export'" size="small" :loading="exporting" @click="exportPicked">导出选中</el-button>
    </BatchBar>
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

.thumb {
  flex: none;
  width: 24px;
  height: 24px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.style-cell .dim {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.small {
  font-size: 11.5px;
}
</style>
