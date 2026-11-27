<script setup lang="ts">
import { computed, onActivated, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { categoryApi } from '@/api/basic'
import { capacityApi } from '@/api/production'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { TreeNode } from '@/types/basic'
import type { CapacityRow } from '@/types/production'
import { businessToday } from '@/utils/businessDate'
import { formatDateTime, formatQty } from '@/utils/format'
import { confirmRemove } from '@/views/basic/common'

/**
 * 工厂产能（/factory-capacity，PRD 08.02，甲方 11-27 答 Q60）：理单、生产跟单每月登记每家厂、每个品类一天能做多少件。
 * 哪个月没再登记，沿用前面最近一次的（标"本月没更新"）。排单、改草稿的加工厂或交期时，交期前还没做完的 + 这次的
 * 超过 日产能 × 天数 就拦住；没登记产能的厂不查
 */
/** 这个月按东八区算（电脑时区不对、零点前后也不会错月） */
const thisMonth = () => businessToday().slice(0, 7)
const query = reactive({ month: thisMonth(), keyword: '', factoryId: undefined as string | undefined })
const factoryName = ref<string | null>(null)
const rows = ref<CapacityRow[]>([])
const loading = ref(false)
const reader = useLatestRequest()

async function load() {
  const read = reader.begin()
  loading.value = true
  try {
    const list = await read.run(() => capacityApi.list({ month: query.month, keyword: query.keyword || undefined, factoryId: query.factoryId }))
    if (read.current()) rows.value = list
  } finally {
    if (read.current()) loading.value = false
  }
}
onActivated(load)

const monthLabel = (ym: string) => `${Number(ym.slice(0, 4))} 年 ${Number(ym.slice(5))} 月`
const shortMonth = (ym: string) => (ym.slice(0, 4) === query.month.slice(0, 4) ? `${Number(ym.slice(5))} 月` : monthLabel(ym))
const emptyText = computed(() =>
  query.keyword || query.factoryId ? '没有符合条件的产能' : `${monthLabel(query.month)}还没有登记产能的工厂：没登记的工厂排单时不查产能`,
)

/** 占用：本月交期还没做完的 / 本月能做的 */
function usage(row: CapacityRow) {
  if (!row.monthCapacity) return { pct: 0, tone: '' as const }
  const pct = Math.round((row.monthLoad / row.monthCapacity) * 100)
  return { pct, tone: pct > 100 ? ('danger' as const) : pct >= 90 ? ('pending' as const) : ('' as const) }
}

// ---------- 品类 ----------
const categories = ref<TreeNode[]>([])
const categoryOptions = computed(() => {
  const map = (nodes: TreeNode[] = []): { value: string; label: string; children: unknown[] }[] =>
    nodes.map((n) => ({ value: n.id, label: n.name, children: map(n.children) }))
  return map(categories.value)
})

// ---------- 登记、改 ----------
const visible = ref(false)
const editing = ref<CapacityRow | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  factoryId: null as string | null,
  factoryName: null as string | null,
  categoryId: null as string | null,
  month: thisMonth(),
  dailyQty: undefined as number | undefined,
  remark: '',
})
const guard = useUnsavedChanges(form, visible)
const saving = ref(false)
const rules: FormRules = {
  factoryId: [{ required: true, message: '请选加工厂', trigger: 'change' }],
  month: [{ required: true, message: '请选从哪个月起', trigger: 'change' }],
  dailyQty: [{ required: true, message: '请填日产能', trigger: 'blur' }],
}

async function ensureCategories() {
  if (!categories.value.length) categories.value = await categoryApi.enabledTree()
}

async function openCreate() {
  editing.value = null
  Object.assign(form, { factoryId: query.factoryId ?? null, factoryName: factoryName.value, categoryId: null, month: query.month, dailyQty: undefined, remark: '' })
  visible.value = true
  await ensureCategories()
}

async function openEdit(row: CapacityRow) {
  editing.value = row
  Object.assign(form, {
    factoryId: row.factoryId, factoryName: row.factoryShortName, categoryId: row.categoryId,
    month: query.month, dailyQty: row.dailyQty, remark: row.remark ?? '',
  })
  visible.value = true
  await ensureCategories()
}

async function save() {
  await formRef.value?.validate()
  if (!form.factoryId || !form.dailyQty) return
  saving.value = true
  try {
    await capacityApi.save({
      factoryId: form.factoryId, categoryId: form.categoryId, month: form.month, dailyQty: form.dailyQty,
      remark: form.remark.trim() || null,
    })
    ElMessage.success(`登记好了：${shortMonth(form.month)}起每天 ${formatQty(form.dailyQty)} 件`)
    guard.markSaved()
    visible.value = false
    void load()
  } finally {
    saving.value = false
  }
}

async function remove(row: CapacityRow) {
  const what = `${row.factoryShortName}${row.categoryName ? ` 「${row.categoryName}」` : ''} ${shortMonth(row.startMonth)}登记的产能`
  await confirmRemove(`删除${what}`, '删了以后按更早登记的产能算；更早也没有，排单时就不再查这家厂这个品类的产能。')
  await capacityApi.remove(row.id)
  ElMessage.success('删了')
  void load()
}
</script>

<template>
  <div>
    <PageHeader sub="每家厂、每个品类一天能做多少件；排单时交期前排不下就拦住">
      <template #actions>
        <el-date-picker v-model="query.month" type="month" value-format="YYYY-MM" format="YYYY 年 M 月" :clearable="false"
          style="width: 140px" @change="load" />
        <el-button v-perm="'production:capacity:edit'" type="primary" icon="Plus" @click="openCreate">登记产能</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="加工厂、品类" @search="load">
      <FilterItem label="加工厂" :text="query.factoryId ? (factoryName ?? '已选') : ''" @clear="query.factoryId = undefined">
        <SupplierSelect v-model="query.factoryId" v-model:label="factoryName" :category="['GARMENT', 'PROCESSOR']" clearable />
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="rows" row-key="id" scrollbar-always-on :empty-text="emptyText">
      <el-table-column label="加工厂" prop="factoryShortName" min-width="120" fixed="left" />
      <el-table-column label="品类" min-width="150">
        <template #default="{ row }">
          <span v-if="(row as CapacityRow).categoryName">{{ (row as CapacityRow).categoryName }}</span>
          <span v-else class="dim">不分品类</span>
        </template>
      </el-table-column>
      <el-table-column label="日产能（件）" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as CapacityRow).dailyQty) }}</template>
      </el-table-column>
      <el-table-column label="从哪月起" width="210">
        <template #default="{ row }">
          <span>{{ shortMonth((row as CapacityRow).startMonth) }}</span>
          <StatusTag v-if="(row as CapacityRow).startMonth !== query.month" tone="pending" class="ml-8">本月没更新</StatusTag>
        </template>
      </el-table-column>
      <el-table-column label="本月能做" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as CapacityRow).monthCapacity) }}</template>
      </el-table-column>
      <el-table-column label="本月交期没做完" width="130" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as CapacityRow).monthLoad) }}</template>
      </el-table-column>
      <el-table-column label="占用" width="100" class-name="num" align="right">
        <template #default="{ row }">
          <StatusTag v-if="usage(row as CapacityRow).tone" :tone="usage(row as CapacityRow).tone || undefined">
            {{ usage(row as CapacityRow).pct }}%
          </StatusTag>
          <span v-else>{{ usage(row as CapacityRow).pct }}%</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" prop="remark" min-width="140" />
      <el-table-column label="最后改" width="230">
        <template #default="{ row }">{{ (row as CapacityRow).updateByName ?? '' }} {{ formatDateTime((row as CapacityRow).updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'production:capacity:edit'" link @click="openEdit(row as CapacityRow)">改</el-button>
          <el-button v-perm="'production:capacity:edit'" link type="danger" @click="remove(row as CapacityRow)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="editing ? `改产能 · ${editing.factoryShortName}` : '登记产能'" width="min(560px, 94vw)"
      :before-close="guard.beforeClose">
      <el-form ref="formRef" class="form-grid" :model="form" :rules="rules" label-position="top">
        <el-form-item label="加工厂" prop="factoryId">
          <SupplierSelect v-model="form.factoryId" v-model:label="form.factoryName" :category="['GARMENT', 'PROCESSOR']" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="品类">
          <el-tree-select v-model="form.categoryId" :data="categoryOptions" check-strictly filterable clearable default-expand-all
            :disabled="!!editing" placeholder="不分品类（这家厂所有款合计）" :value-on-clear="null" />
        </el-form-item>
        <el-form-item label="从哪个月起" prop="month">
          <el-date-picker v-model="form.month" type="month" value-format="YYYY-MM" format="YYYY 年 M 月" :clearable="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="日产能（件 / 天）" prop="dailyQty">
          <el-input-number v-model="form.dailyQty" :min="1" :max="1000000" :step="10" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注" class="full">
          <el-input v-model="form.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <div class="dim small">按自然日算（含周末），填平均每天能出多少件。品类挂在上级上就管它下面所有的；到下一次登记之前都按这个数。</div>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ml-8 { margin-left: 8px; }
</style>
