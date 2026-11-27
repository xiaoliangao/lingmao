<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { statementApi } from '@/api/settle'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTable } from '@/composables/useTable'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { STATEMENT_STATUS, type PendingStatementRow, type StatementRow, type StatementStatus } from '@/types/settle'
import { businessToday } from '@/utils/businessDate'
import { formatDate, formatDateRange, formatDateTime, formatMoney } from '@/utils/format'

/**
 * 供应商对账（/statements，RFC 019 §5.1，甲方 11-27 答 Q68）：
 * - 「对账单」：已生成的，筛状态、供应商、期间；点行进详情
 * - 「待对账」：按主体 × 供应商 × 月份列出还没进对账单的入库、退货、回货、扣款，点「生成对账单」
 * - 页头「生成对账单」：自己选主体、供应商、期间（默认上个月）
 */
const router = useRouter()
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('settle:statement:edit'))

const view = ref<'list' | 'pending'>('list')

// ---------- 对账单 ----------
const cols = useColumns('statements', [
  { key: 'company', label: '主体' },
  { key: 'period', label: '期间' },
  { key: 'total', label: '货款' },
  { key: 'deduct', label: '扣款' },
  { key: 'payable', label: '实付' },
  { key: 'compare', label: '比对' },
  { key: 'items', label: '行数', hidden: true },
  { key: 'created', label: '制单', hidden: true },
  { key: 'remark', label: '备注', hidden: true },
])
const STATUSES: StatementStatus[] = ['DRAFT', 'SENT', 'CONFIRMED', 'VOIDED']
const partnerName = ref<string | null>(null)
const period = ref<[string, string] | null>(null)

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  normalize: ({ statusTab, ...rest }) => ({ ...rest, status: statusTab ? [statusTab] : undefined }),
  fetch: statementApi.page,
  query: {
    keyword: '',
    statusTab: '' as StatementStatus | '',
    partnerId: undefined as string | null | undefined,
    periodFrom: undefined as string | undefined,
    periodTo: undefined as string | undefined,
    mine: false,
  },
})

function onPeriod(val: [string, string] | null | undefined) {
  period.value = val ?? null
  query.periodFrom = val?.[0]
  query.periodTo = val?.[1]
}

const filtered = computed(() => !!(query.keyword.trim() || query.statusTab || query.partnerId || query.periodFrom))
const emptyText = computed(() => {
  if (filtered.value) return '没有符合条件的对账单：换个状态，或者清空筛选'
  if (query.mine) return '还没有你生成的对账单，切到「全部」看所有人的'
  return '还没有对账单：到「待对账」挑一家点「生成对账单」'
})

const compareText = (row: StatementRow) => {
  if (row.theirAmount == null) return '—'
  return row.diffCount ? `${row.diffCount} 处不一致` : '全部一致'
}

// ---------- 待对账 ----------
const pending = ref<PendingStatementRow[]>([])
const pendingLoading = ref(false)
const pendingFilter = reactive({ companyId: undefined as string | null | undefined, partnerId: undefined as string | null | undefined })
const pendingPartnerName = ref<string | null>(null)
const pendingReader = useLatestRequest()
const pendingCompanyText = computed(() =>
  pendingFilter.companyId ? (pending.value.find((r) => r.companyId === pendingFilter.companyId)?.companyName ?? '已选') : '',
)

async function loadPending() {
  const read = pendingReader.begin()
  pendingLoading.value = true
  try {
    const rows = await read.run(() => statementApi.pending({ companyId: pendingFilter.companyId ?? undefined, partnerId: pendingFilter.partnerId }))
    if (read.current()) pending.value = rows
  } finally {
    if (read.current()) pendingLoading.value = false
  }
}

function switchView(v: 'list' | 'pending') {
  view.value = v
  if (v === 'pending') void loadPending()
  else void load()
}

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    void loadPending()
    return
  }
  void (view.value === 'pending' ? loadPending() : load())
})

const VIEW_OPTIONS = computed(() => [
  { label: '对账单', value: 'list' },
  { label: pending.value.length ? `待对账 ${pending.value.length}` : '待对账', value: 'pending' },
])

// ---------- 生成 ----------
const visible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  companyId: null as string | null,
  partnerId: null as string | null,
  partnerName: null as string | null,
  period: null as [string, string] | null,
  remark: '',
})
const guard = useUnsavedChanges(form, visible)
const creating = ref(false)
const rules: FormRules = {
  companyId: [{ required: true, message: '请选主体', trigger: 'change' }],
  partnerId: [{ required: true, message: '请选供应商或加工厂', trigger: 'change' }],
  period: [{ required: true, message: '请选期间', trigger: 'change' }],
}

/** 上个月 1 号到月底（东八区的业务日） */
function lastMonth(): [string, string] {
  const [y, m] = businessToday().split('-').map(Number) as [number, number]
  const year = m === 1 ? y - 1 : y
  const month = m === 1 ? 12 : m - 1
  const end = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const mm = String(month).padStart(2, '0')
  return [`${year}-${mm}-01`, `${year}-${mm}-${String(end).padStart(2, '0')}`]
}

function openCreate(row?: PendingStatementRow) {
  Object.assign(form, row
    ? { companyId: row.companyId, partnerId: row.partnerId, partnerName: row.partnerName, period: [row.periodStart, row.periodEnd], remark: '' }
    : { companyId: user.defaultCompanyId, partnerId: null, partnerName: null, period: lastMonth(), remark: '' })
  guard.markSaved()
  visible.value = true
}

async function create() {
  await formRef.value?.validate()
  if (!form.companyId || !form.partnerId || !form.period) return
  creating.value = true
  try {
    const created = await statementApi.create({
      companyId: form.companyId, partnerId: form.partnerId, periodStart: form.period[0], periodEnd: form.period[1],
      remark: form.remark.trim() || null,
    })
    ElMessage.success(`生成了 ${created.no}，${created.added} 行`)
    guard.markSaved()
    visible.value = false
    await router.push(`/statements/${created.id}`)
  } finally {
    creating.value = false
  }
}

/** 待对账一行直接生成：不弹框 */
async function createFrom(row: PendingStatementRow) {
  const created = await statementApi.create({ companyId: row.companyId, partnerId: row.partnerId, periodStart: row.periodStart, periodEnd: row.periodEnd })
  ElMessage.success(`生成了 ${created.no}，${created.added} 行`)
  await router.push(`/statements/${created.id}`)
}
</script>

<template>
  <div>
    <PageHeader sub="按月拉出每家的入库、退货、回货和扣款，导出发给对方核对，传回对方的 Excel 自动比对">
      <template #actions>
        <el-button v-if="canEdit" type="primary" icon="Plus" @click="openCreate()">生成对账单</el-button>
      </template>
    </PageHeader>

    <template v-if="view === 'list'">
      <FilterBar v-model:keyword="query.keyword" placeholder="对账单号、供应商" :columns="cols" @search="search">
        <template #left>
          <el-segmented :model-value="view" :options="VIEW_OPTIONS" @change="switchView($event as 'list' | 'pending')" />
        </template>
        <FilterItem label="状态" :text="query.statusTab ? STATEMENT_STATUS[query.statusTab].label : ''" @clear="query.statusTab = ''">
          <el-select v-model="query.statusTab" filterable clearable placeholder="全部状态" value-on-clear="">
            <el-option v-for="st in STATUSES" :key="st" :label="STATEMENT_STATUS[st].label" :value="st" />
          </el-select>
        </FilterItem>
        <FilterItem label="供应商" :text="query.partnerId ? (partnerName ?? '已选') : ''" @clear="query.partnerId = undefined">
          <SupplierSelect v-model="query.partnerId" v-model:label="partnerName" clearable placeholder="全部供应商、加工厂" />
        </FilterItem>
        <FilterItem label="期间" :text="formatDateRange(period)" @clear="onPeriod(null)">
          <DateRange :model-value="period" @update:model-value="onPeriod" />
        </FilterItem>
        <template #right>
          <el-segmented v-if="user.canSeeAll" v-model="query.mine" :options="[{ label: '我的', value: true }, { label: '全部', value: false }]" @change="search" />
        </template>
      </FilterBar>

      <el-table v-loading="loading" :data="list" row-key="id" row-class-name="clickable" scrollbar-always-on :empty-text="emptyText"
        @row-click="(row: StatementRow) => router.push(`/statements/${row.id}`)">
        <el-table-column label="单号" prop="no" width="150" fixed="left" class-name="code" />
        <el-table-column label="供应商" min-width="150">
          <template #default="{ row }">{{ (row as StatementRow).partnerName }}</template>
        </el-table-column>
        <el-table-column v-if="cols.show.company" label="主体" prop="companyName" width="120" />
        <el-table-column v-if="cols.show.period" label="期间" width="200" class-name="num">
          <template #default="{ row }">{{ formatDate(row.periodStart) }} ~ {{ formatDate(row.periodEnd) }}</template>
        </el-table-column>
        <el-table-column v-if="cols.show.total" label="货款" width="120" class-name="num" align="right">
          <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column v-if="cols.show.deduct" label="扣款" width="110" class-name="num" align="right">
          <template #default="{ row }">{{ row.deductAmount ? formatMoney(row.deductAmount) : '—' }}</template>
        </el-table-column>
        <el-table-column v-if="cols.show.payable" label="实付" width="120" class-name="num" align="right">
          <template #default="{ row }"><b>{{ formatMoney(row.payableAmount) }}</b></template>
        </el-table-column>
        <el-table-column v-if="cols.show.compare" label="比对" width="120">
          <template #default="{ row }">
            <StatusTag v-if="(row as StatementRow).diffCount" tone="danger">{{ compareText(row as StatementRow) }}</StatusTag>
            <span v-else :class="{ dim: (row as StatementRow).theirAmount == null }">{{ compareText(row as StatementRow) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="cols.show.items" label="行数" prop="itemCount" width="70" class-name="num" align="right" />
        <el-table-column v-if="cols.show.created" label="制单" width="190">
          <template #default="{ row }">{{ row.creatorName ?? '—' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span></template>
        </el-table-column>
        <el-table-column v-if="cols.show.remark" label="备注" min-width="140">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96" fixed="right">
          <template #default="{ row }">
            <StatusTag :tone="STATEMENT_STATUS[(row as StatementRow).status].tone">{{ STATEMENT_STATUS[(row as StatementRow).status].label }}</StatusTag>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination" layout="total, sizes, prev, pager, next"
        :page-sizes="pageSizes" :total="total" />
    </template>

    <template v-else>
      <FilterBar @search="loadPending">
        <template #left>
          <el-segmented :model-value="view" :options="VIEW_OPTIONS" @change="switchView($event as 'list' | 'pending')" />
        </template>
        <FilterItem label="供应商" :text="pendingFilter.partnerId ? (pendingPartnerName ?? '已选') : ''" @clear="pendingFilter.partnerId = undefined">
          <SupplierSelect v-model="pendingFilter.partnerId" v-model:label="pendingPartnerName" clearable placeholder="全部供应商、加工厂" />
        </FilterItem>
        <FilterItem label="主体" :text="pendingCompanyText" @clear="pendingFilter.companyId = undefined">
          <CompanySelect v-model="pendingFilter.companyId" placeholder="全部主体" />
        </FilterItem>
      </FilterBar>

      <el-table v-loading="pendingLoading" :data="pending" :row-key="(r: PendingStatementRow) => `${r.companyId}-${r.partnerId}-${r.month}`"
        scrollbar-always-on empty-text="入库、退货、回货、扣款都已经进了对账单">
        <el-table-column label="供应商" min-width="160" fixed="left">
          <template #default="{ row }">
            <span class="code dim">{{ (row as PendingStatementRow).partnerCode }}</span> {{ (row as PendingStatementRow).partnerName }}
          </template>
        </el-table-column>
        <el-table-column label="主体" prop="companyName" width="140" />
        <el-table-column label="月份" prop="month" width="96" class-name="num" />
        <el-table-column label="单据" width="80" class-name="num" align="right">
          <template #default="{ row }">{{ (row as PendingStatementRow).docCount }} 张</template>
        </el-table-column>
        <el-table-column label="实付（估）" width="130" class-name="num" align="right">
          <template #default="{ row }">{{ formatMoney((row as PendingStatementRow).amount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <template v-if="canEdit">
              <el-button link type="primary" @click="createFrom(row as PendingStatementRow)">生成对账单</el-button>
              <el-button link @click="openCreate(row as PendingStatementRow)">改期间再生成</el-button>
            </template>
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog v-model="visible" title="生成对账单" width="min(560px, 94vw)" :before-close="guard.beforeClose">
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="主体" prop="companyId">
          <CompanySelect v-model="form.companyId" placeholder="选主体" />
        </el-form-item>
        <el-form-item label="供应商或加工厂" prop="partnerId">
          <SupplierSelect v-model="form.partnerId" v-model:label="form.partnerName" placeholder="选一家" />
        </el-form-item>
        <el-form-item label="期间" prop="period" class="span-all">
          <DateRange v-model="form.period" :clearable="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注" class="span-all">
          <el-input v-model="form.remark" maxlength="500" placeholder="选填" />
        </el-form-item>
      </el-form>
      <div class="dim small">把这段时间里这家还没对过的已审核采购入库、采购退货、已登记回货和扣款都拉进来，生成草稿。</div>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">生成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.small {
  font-size: 11.5px;
}
</style>
