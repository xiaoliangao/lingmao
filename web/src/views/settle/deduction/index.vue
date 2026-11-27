<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { deductionApi } from '@/api/settle'
import DateRange from '@/components/DateRange.vue'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import { reloadOnConflict, todayStr } from '@/components/doc/docUtils'
import { useTable } from '@/composables/useTable'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { STATEMENT_STATUS, type DeductionRow } from '@/types/settle'
import { formatDate, formatDateRange, formatDateTime, formatMoney } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'

/**
 * 扣款登记（/deductions，RFC 019 §5.2）：延期、质量问题这类要扣的钱，发生了就登记；月底生成对账单时按日期拉进去。
 * 进了没作废的对账单的不能改、删（后端 1000 说是哪张），表格里也不给按钮
 */
const user = useUserStore()
const canEdit = computed(() => user.hasPerm('settle:deduction:edit'))
const partnerName = ref<string | null>(null)
const dates = ref<[string, string] | null>(null)

const SETTLED_OPTIONS = [
  { label: '全部', value: '' },
  { label: '没对账', value: 'no' },
  { label: '已进对账单', value: 'yes' },
]

const { query, page, size, pageSizes, list, total, loading, load, search } = useTable({
  // 空值在这之前已经去掉了：「全部」到这里是 undefined，不能当成 false（只看没对账的）
  normalize: ({ settledTab, ...rest }) => ({ ...rest, settled: settledTab ? settledTab === 'yes' : undefined }),
  fetch: deductionApi.page,
  query: {
    keyword: '',
    settledTab: '' as '' | 'yes' | 'no',
    partnerId: undefined as string | null | undefined,
    dateFrom: undefined as string | undefined,
    dateTo: undefined as string | undefined,
  },
})

let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  void load()
})

function onDates(val: [string, string] | null | undefined) {
  dates.value = val ?? null
  query.dateFrom = val?.[0]
  query.dateTo = val?.[1]
}

const emptyText = computed(() =>
  query.keyword.trim() || query.settledTab || query.partnerId || query.dateFrom
    ? '没有符合条件的扣款：清空筛选看全部'
    : '还没有登记扣款：延期、质量问题要扣钱的，点「登记扣款」；回货的次品生成对账单时自动扣，不用登记',
)

// ---------- 登记、改 ----------
const visible = ref(false)
const editing = ref<DeductionRow | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  companyId: null as string | null,
  partnerId: null as string | null,
  partnerName: null as string | null,
  bizDate: '',
  amount: undefined as number | undefined,
  reason: '',
  refNo: '',
})
const guard = useUnsavedChanges(form, visible)
const saving = ref(false)
const rules: FormRules = {
  companyId: [{ required: true, message: '请选主体', trigger: 'change' }],
  partnerId: [{ required: true, message: '请选扣谁的', trigger: 'change' }],
  bizDate: [{ required: true, message: '请选日期', trigger: 'change' }],
  amount: [{ required: true, message: '请填金额', trigger: 'blur' }],
  reason: [{ required: true, message: '请写原因', trigger: 'blur' }],
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    companyId: user.defaultCompanyId, partnerId: query.partnerId ?? null, partnerName: partnerName.value, bizDate: todayStr(),
    amount: undefined, reason: '', refNo: '',
  })
  guard.markSaved()
  visible.value = true
}

function openEdit(row: DeductionRow) {
  editing.value = row
  Object.assign(form, {
    companyId: row.companyId, partnerId: row.partnerId, partnerName: row.partnerName, bizDate: row.bizDate,
    amount: row.amount, reason: row.reason, refNo: row.refNo ?? '',
  })
  guard.markSaved()
  visible.value = true
}

const afterToday = (d: Date) => {
  const s = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return s > todayStr()
}

async function save() {
  await formRef.value?.validate()
  if (!form.companyId || !form.partnerId || !form.amount) return
  const body = {
    companyId: form.companyId, partnerId: form.partnerId, bizDate: form.bizDate, amount: form.amount,
    reason: form.reason.trim(), refNo: form.refNo.trim() || null,
  }
  saving.value = true
  try {
    if (editing.value) await deductionApi.update(editing.value.id, { ...body, version: editing.value.version })
    else await deductionApi.create(body)
    ElMessage.success(editing.value ? '改好了' : `登记了：${form.partnerName ?? ''} ${formatMoney(form.amount)} 元`)
    guard.markSaved()
    visible.value = false
    void load()
  } catch (e) {
    if (reloadOnConflict(e, () => { visible.value = false; void load() })) return
    throw e
  } finally {
    saving.value = false
  }
}

async function remove(row: DeductionRow) {
  await confirmRemove(`删除 ${row.partnerName} ${formatMoney(row.amount)} 元的扣款`, '删了以后对账单里不会再扣这笔。')
  await deductionApi.remove(row.id, row.companyId)
  ElMessage.success('删了')
  void load()
}
</script>

<template>
  <div>
    <PageHeader sub="延期、质量问题这类要扣的钱，发生了就登记；生成对账单时按日期算进那个月">
      <template #actions>
        <el-button v-if="canEdit" type="primary" icon="Plus" @click="openCreate">登记扣款</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="原因、相关单号、供应商" @search="search">
      <template #left>
        <el-segmented v-model="query.settledTab" :options="SETTLED_OPTIONS" @change="search" />
      </template>
      <FilterItem label="供应商" :text="query.partnerId ? (partnerName ?? '已选') : ''" @clear="query.partnerId = undefined">
        <SupplierSelect v-model="query.partnerId" v-model:label="partnerName" clearable placeholder="全部供应商、加工厂" />
      </FilterItem>
      <FilterItem label="日期" :text="formatDateRange(dates)" @clear="onDates(null)">
        <DateRange :model-value="dates" @update:model-value="onDates" />
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="list" row-key="id" scrollbar-always-on :empty-text="emptyText">
      <el-table-column label="日期" width="104" class-name="num" fixed="left">
        <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
      </el-table-column>
      <el-table-column label="扣谁的" min-width="140">
        <template #default="{ row }">{{ (row as DeductionRow).partnerName }}</template>
      </el-table-column>
      <el-table-column label="主体" prop="companyName" width="120" />
      <el-table-column label="金额" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="原因" min-width="200" prop="reason" />
      <el-table-column label="相关单号" width="140">
        <template #default="{ row }"><span class="code">{{ row.refNo || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="对账单" width="190">
        <template #default="{ row }">
          <template v-if="(row as DeductionRow).statementId">
            <router-link class="code link" :to="`/statements/${row.statementId}`">{{ row.statementNo }}</router-link>
            <StatusTag v-if="(row as DeductionRow).statementStatus" class="ml-6"
              :tone="STATEMENT_STATUS[(row as DeductionRow).statementStatus!].tone">{{ STATEMENT_STATUS[(row as DeductionRow).statementStatus!].label }}</StatusTag>
          </template>
          <span v-else class="dim">没对账</span>
        </template>
      </el-table-column>
      <el-table-column label="登记" width="190">
        <template #default="{ row }">{{ row.creatorName ?? '—' }} <span class="dim small">{{ formatDateTime(row.createTime) }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <template v-if="canEdit && !(row as DeductionRow).statementId">
            <el-button link @click="openEdit(row as DeductionRow)">改</el-button>
            <el-button link type="danger" @click="remove(row as DeductionRow)">删除</el-button>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination" layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes" :total="total" />

    <el-dialog v-model="visible" :title="editing ? '改扣款' : '登记扣款'" width="min(560px, 94vw)" :before-close="guard.beforeClose">
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="主体" prop="companyId">
          <CompanySelect v-model="form.companyId" placeholder="选主体" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="扣谁的" prop="partnerId">
          <SupplierSelect v-model="form.partnerId" v-model:label="form.partnerName" placeholder="供应商或加工厂" />
        </el-form-item>
        <el-form-item label="日期" prop="bizDate">
          <el-date-picker v-model="form.bizDate" value-format="YYYY-MM-DD" :disabled-date="afterToday" :clearable="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额（元）" prop="amount">
          <el-input-number v-model="form.amount" :min="0.01" :max="999999999999" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因" prop="reason" class="span-all">
          <el-input v-model="form.reason" maxlength="500" placeholder="延期几天、什么质量问题" />
        </el-form-item>
        <el-form-item label="相关单号" class="span-all">
          <el-input v-model="form.refNo" maxlength="64" placeholder="选填：生产单号、回货单号，写字就行" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ml-6 {
  margin-left: 6px;
}

.small {
  font-size: 11.5px;
}

.link {
  color: var(--lm-accent);
  text-decoration: none;
}
</style>
