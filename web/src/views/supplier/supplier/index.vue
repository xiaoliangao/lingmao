<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import type { TableInstance } from 'element-plus'
import { supplierApi } from '@/api/supplier'
import { SUPPLIER_STATUS, type SupplierRow, type SupplierSaveResult, type SupplierStatus } from '@/types/supplier'
import { useTable } from '@/composables/useTable'
import { useDict } from '@/composables/useDict'
import { useUserStore } from '@/stores/user'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import BatchBar from '@/components/BatchBar.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import type { BatchAction } from '@/components/batchBar'
import SupplierFormDialog from './SupplierFormDialog.vue'

const router = useRouter()
const store = useUserStore()
const categoryDict = useDict('supplier_category')
const settleDict = useDict('settle_method')

// ---------- 列表 ----------
const { query, page, size, pageSizes, list, total, loading, load, search, exporting, exportFile, exportSelected: exportSelectedRows } = useTable({
  fetch: supplierApi.page,
  query: {
    keyword: '',
    category: '',
    status: [] as SupplierStatus[],
    level: undefined as string | undefined,
    buyerId: undefined as string | null | undefined,
    toApprove: undefined as boolean | undefined,
    missingCredit: undefined as boolean | undefined,
    certExpiring: undefined as boolean | undefined,
  },
  exportUrl: '/api/suppliers/export',
  exportName: '供应商.xlsx',
})

/** 分类分段：全部 + 字典里启用的分类 */
const categoryOptions = computed(() => [
  { label: '全部', value: '' },
  ...categoryDict.enabled.value.map((option) => ({ label: option.label, value: option.value })),
])

const STATUS_OPTIONS = Object.entries(SUPPLIER_STATUS).map(([value, { label }]) => ({ value: value as SupplierStatus, label }))
const LEVELS = ['A', 'B', 'C', 'D']

/** "只看"里的三个开关：勾了是 true，没勾不发（undefined），后端不按它筛 */
const FLAGS = [
  { key: 'toApprove', label: '待我审批' },
  { key: 'certExpiring', label: '证照 30 天内到期' },
  { key: 'missingCredit', label: '缺信用代码' },
] as const
type FlagKey = (typeof FLAGS)[number]['key']
const flags = computed<FlagKey[]>({
  get: () => FLAGS.filter((flag) => query[flag.key]).map((flag) => flag.key),
  set: (keys) => {
    for (const flag of FLAGS) query[flag.key] = keys.includes(flag.key) ? true : undefined
  },
})

const statusText = computed(() => query.status.map((status) => SUPPLIER_STATUS[status].label).join('、'))
const flagText = computed(() => FLAGS.filter((flag) => query[flag.key]).map((flag) => flag.label).join('、'))
/**
 * 选了负责采购员以后小标签上的字。UserSelect 只给 id，名字从按他筛出来的行里取（这些行的采购员都是他）；
 * 一行都没有时只能写"已选"
 */
const buyerName = computed(() => (query.buyerId ? list.value.find((row) => row.buyerId === query.buyerId)?.buyerName : null) ?? null)
const buyerText = computed(() => (query.buyerId ? (buyerName.value ?? '已选') : ''))

const filtered = computed(
  () => !!(query.keyword.trim() || query.category || query.status.length || query.level || query.buyerId || flagText.value),
)

function categoryNames(row: SupplierRow) {
  return row.categories.map((value) => categoryDict.labelOf(value)).join('、')
}

function settleText(row: SupplierRow) {
  const method = settleDict.labelOf(row.settleMethod)
  return row.creditDays ? `${method} ${row.creditDays} 天`.trim() : method
}

/** 证照一列：几份；30 天内到期、过期的标出来 */
function certInfo(row: SupplierRow): { text: string; tone: '' | 'warn' | 'danger' } {
  if (row.certCount === 0) return { text: '没传', tone: '' }
  if (!row.certEarliestUntil) return { text: `${row.certCount} 份`, tone: '' }
  const days = dayjs(row.certEarliestUntil).diff(dayjs().startOf('day'), 'day')
  if (days < 0) return { text: `${row.certCount} 份，有过期的`, tone: 'danger' }
  if (days <= 30) return { text: `${row.certCount} 份，${days} 天后到期`, tone: 'warn' }
  return { text: `${row.certCount} 份`, tone: '' }
}

function openDetail(row: SupplierRow) {
  void router.push(`/suppliers/${row.id}`)
}

function onRowClick(row: SupplierRow, column: { type?: string } | null) {
  if (column?.type !== 'selection') openDetail(row)
}

// ---------- 新建 ----------
const formRef = ref<InstanceType<typeof SupplierFormDialog>>()

function onSaved(result: SupplierSaveResult, created: boolean) {
  if (created) {
    void router.push(`/suppliers/${result.id}`)
  }
  void load()
}

// ---------- 导入：合作中（领猫里在用的，要准入权限）或待准入 ----------
const canAdmit = computed(() => store.hasPerm('supplier:supplier:admit'))
const importVisible = ref(false)
const importStatus = ref<'ACTIVE' | 'PENDING_ADMIT'>('PENDING_ADMIT')

function openImport(status: 'ACTIVE' | 'PENDING_ADMIT') {
  importStatus.value = status
  importVisible.value = true
}

// ---------- 勾选、批量 ----------
const tableRef = ref<TableInstance>()
const selected = ref<SupplierRow[]>([])
const batchActions: BatchAction<SupplierRow>[] = [
  {
    label: '停用',
    perm: 'supplier:supplier:edit',
    can: (row) => row.status === 'ACTIVE',
    run: (row, options) => supplierApi.disable(row.id, options),
    confirm: '停用后新的采购单、生产单选不到它们；已有单据照常入库、回货。随时可以恢复合作。',
  },
  {
    label: '恢复合作',
    perm: 'supplier:supplier:edit',
    can: (row) => row.status === 'DISABLED',
    run: (row, options) => supplierApi.enable(row.id, options),
  },
]

const exportingSelected = ref(false)
async function exportSelected() {
  exportingSelected.value = true
  try {
    await exportSelectedRows(selected.value.map((row) => row.id))
  } finally {
    exportingSelected.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader>
      <template #actions>
        <template v-if="store.hasPerm('supplier:supplier:import')">
          <el-dropdown v-if="canAdmit" trigger="click" @command="openImport">
            <el-button icon="Upload">导入</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="ACTIVE">按「合作中」导入（领猫里在用的）</el-dropdown-item>
                <el-dropdown-item command="PENDING_ADMIT">按「待准入」导入（还要走准入审批）</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-else icon="Upload" @click="openImport('PENDING_ADMIT')">导入</el-button>
        </template>
        <el-button v-perm="'supplier:supplier:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
        <el-button v-perm="'supplier:supplier:add'" type="primary" icon="Plus" @click="formRef?.open()">新建供应商</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="编码、名称、联系人" @search="search">
      <template #left>
        <el-segmented v-model="query.category" :options="categoryOptions" @change="search" />
      </template>
      <FilterItem label="状态" :text="statusText" @clear="query.status = []">
        <el-checkbox-group v-model="query.status">
          <el-checkbox v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox>
        </el-checkbox-group>
      </FilterItem>
      <FilterItem label="只看" :text="flagText" @clear="flags = []">
        <el-checkbox-group v-model="flags">
          <el-checkbox v-for="flag in FLAGS" :key="flag.key" :value="flag.key">{{ flag.label }}</el-checkbox>
        </el-checkbox-group>
      </FilterItem>
      <FilterItem label="等级" :text="query.level ? `${query.level} 级` : ''" @clear="query.level = undefined">
        <el-select v-model="query.level" clearable filterable placeholder="全部">
          <el-option v-for="level in LEVELS" :key="level" :label="`${level} 级`" :value="level" />
        </el-select>
      </FilterItem>
      <FilterItem label="负责采购员" :text="buyerText" @clear="query.buyerId = undefined">
        <UserSelect v-model="query.buyerId" :label="buyerName" clearable />
      </FilterItem>
    </FilterBar>

    <el-table
      ref="tableRef"
      v-loading="loading"
      row-key="id"
      :data="list"
      class="clickable"
      :empty-text="filtered ? '没有符合条件的供应商' : '还没有供应商：点「新建供应商」，或者从领猫导出来再导入'"
      @selection-change="selected = $event"
      @row-click="onRowClick"
    >
      <el-table-column type="selection" width="40" reserve-selection />
      <el-table-column prop="code" label="编码" width="110" class-name="code" />
      <el-table-column label="简称" min-width="220">
        <template #default="{ row }">
          <span>{{ (row as SupplierRow).shortName }}</span>
          <span class="dim full-name">{{ (row as SupplierRow).name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="分类" min-width="130">
        <template #default="{ row }">{{ categoryNames(row as SupplierRow) }}</template>
      </el-table-column>
      <el-table-column prop="level" label="等级" width="64" />
      <el-table-column label="状态" width="196">
        <template #default="{ row }">
          <span class="status">
            <StatusTag :tone="SUPPLIER_STATUS[(row as SupplierRow).status].tone">{{ SUPPLIER_STATUS[(row as SupplierRow).status].label }}</StatusTag>
            <StatusTag v-if="(row as SupplierRow).changePending" tone="pending" title="关键信息改动等审批">改动待批</StatusTag>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="contact" label="联系人" min-width="90" />
      <el-table-column label="结算" min-width="110">
        <template #default="{ row }">{{ settleText(row as SupplierRow) }}</template>
      </el-table-column>
      <el-table-column prop="buyerName" label="负责采购员" min-width="100" />
      <el-table-column label="证照" min-width="150">
        <template #default="{ row }">
          <span :class="['cert', certInfo(row as SupplierRow).tone]">{{ certInfo(row as SupplierRow).text }}</span>
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

    <BatchBar :rows="selected" :table="tableRef" :actions="batchActions" unit="家" :row-name="(row) => `${row.code} ${row.shortName}`" @done="load">
      <el-button v-perm="'supplier:supplier:export'" size="small" :loading="exportingSelected" @click="exportSelected">导出选中</el-button>
    </BatchBar>

    <SupplierFormDialog ref="formRef" @saved="onSaved" />
    <ImportDialog
      v-model="importVisible"
      :title="importStatus === 'ACTIVE' ? '导入供应商（合作中）' : '导入供应商（待准入）'"
      template-url="/api/suppliers/import-template"
      :import-url="`/api/suppliers/import?status=${importStatus}`"
      @success="search"
    />
  </div>
</template>

<style scoped>
.clickable :deep(.el-table__row) {
  cursor: pointer;
}

.full-name {
  margin-left: 8px;
  font-size: 12px;
}

.status {
  display: inline-flex;
  gap: 4px;
}

.cert.warn {
  color: var(--lm-pending-text);
}

.cert.danger {
  color: var(--lm-danger-text);
}
</style>
