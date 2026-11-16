<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { companyApi, materialCategoryApi, warehouseApi } from '@/api/basic'
import { countApi } from '@/api/inventory'
import DataPick from '@/components/DataPick.vue'
import UserSelect from '@/components/UserSelect.vue'
import { todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { CompanyOption, MaterialPickRow, TreeNode, WarehouseOption } from '@/types/basic'
import { COUNT_PURPOSE_LABEL, COUNT_SCOPE_LABEL, type CountOpenWarehouse, type CountPurpose, type CountScope } from '@/types/inventory'
import type { StylePickRow } from '@/types/style'
import { orNull } from '@/views/basic/common'

/**
 * 新建盘点单（docs/api/04 §1.5、§3.5.4）。
 *
 *   <CountCreateDialog v-model="creating" :default-purpose="purpose" @created="(id) => router.push(`/stock-takes/${id}`)" />
 *
 * - 有没审核盘点单的仓库灰掉，后面写"PD… 还没审"
 * - 期初录入：范围固定手选，可以一个都不选（建空单再导入）；日常盘点手选至少一个
 */
const visible = defineModel<boolean>({ required: true })

const props = withDefaults(
  defineProps<{
    defaultPurpose?: CountPurpose
  }>(),
  { defaultPurpose: 'DAILY' },
)

const emit = defineEmits<{
  created: [id: number]
}>()

const user = useUserStore()
const companies = ref<CompanyOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const openWhs = ref<CountOpenWarehouse[]>([])
const categories = ref<TreeNode[]>([])
const pickedSkus = ref<{ id: number; code: string; name: string }[]>([])
const skuPicking = ref(false)
const saving = ref(false)

const form = reactive({
  companyId: null as number | null,
  warehouseId: null as number | null,
  purpose: 'DAILY' as CountPurpose,
  scope: 'ALL' as CountScope,
  categoryIds: [] as number[],
  countDate: todayStr(),
  counterId: user.info?.id ?? null,
  counterName: user.info?.nickname ?? null,
  remark: '',
})

const guard = useUnsavedChanges(() => ({ form, pickedSkus: pickedSkus.value }), visible)

const whOptions = computed(() =>
  warehouses.value.filter((w) => w.type === 'MATERIAL' || w.type === 'FACTORY'),
)

const openWhMap = computed(() => new Map(openWhs.value.map((x) => [x.warehouseId, x])))
const whLabel = (w: WarehouseOption) => {
  const open = openWhMap.value.get(w.id)
  return open ? `${w.name}（${open.countNo} 还没审）` : w.name
}
const PURPOSE_OPTIONS = Object.entries(COUNT_PURPOSE_LABEL).map(([value, label]) => ({ label, value: value as CountPurpose }))
const SCOPE_OPTIONS = Object.entries(COUNT_SCOPE_LABEL).map(([value, label]) => ({ label, value: value as CountScope }))
const pickedIds = computed(() => pickedSkus.value.map((s) => s.id))

const level2Categories = computed(() =>
  categories.value.flatMap((top) =>
    (top.children ?? []).map((sub) => ({
      id: sub.id,
      label: `${top.name} / ${sub.name}`,
    })),
  ),
)

async function init() {
  if (!visible.value) return
  const [comps, whs, opens, tree] = await Promise.all([
    companyApi.options(),
    warehouseApi.options(),
    countApi.openWarehouses(),
    materialCategoryApi.enabledTree(),
  ])
  companies.value = comps
  warehouses.value = whs
  openWhs.value = opens
  categories.value = tree

  form.companyId = comps[0]?.id ?? null
  form.purpose = props.defaultPurpose
  form.scope = props.defaultPurpose === 'OPENING' ? 'PICKED' : 'ALL'
  form.categoryIds = []
  form.countDate = todayStr()
  form.remark = ''
  pickedSkus.value = []

  const firstFree = whOptions.value.find((w) => !openWhMap.value.has(w.id))
  form.warehouseId = firstFree?.id ?? null
  guard.markSaved()
}

watch(visible, (on) => {
  if (on) void init()
})

watch(
  () => form.purpose,
  (p) => {
    if (p === 'OPENING') form.scope = 'PICKED'
  },
)

function onPickedSkus(rows: MaterialPickRow[] | StylePickRow[]) {
  for (const r of rows as MaterialPickRow[]) {
    if (!pickedSkus.value.some((s) => s.id === r.skuId)) {
      pickedSkus.value.push({ id: r.skuId, code: r.skuCode, name: r.materialName })
    }
  }
}

async function submit() {
  if (!form.companyId) return void ElMessage.warning('先选主体')
  if (!form.warehouseId) return void ElMessage.warning('先选仓库')
  if (!form.counterId) return void ElMessage.warning('先选盘点人')
  if (form.scope === 'CATEGORY' && !form.categoryIds.length) return void ElMessage.warning('至少选一个分类')
  if (form.purpose === 'DAILY' && form.scope === 'PICKED' && !pickedSkus.value.length) return void ElMessage.warning('至少选一种物料')

  saving.value = true
  try {
    const res = await countApi.create({
      companyId: form.companyId,
      warehouseId: form.warehouseId,
      purpose: form.purpose,
      scope: form.scope,
      categoryIds: form.scope === 'CATEGORY' ? form.categoryIds : [],
      skuIds: form.scope === 'PICKED' ? pickedSkus.value.map((s) => s.id) : [],
      countDate: form.countDate,
      counterId: form.counterId,
      remark: orNull(form.remark),
    })
    ElMessage.success(`${res.no} 建好了，带出 ${res.itemCount} 行账面数`)
    guard.markSaved()
    visible.value = false
    emit('created', res.id)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="form.purpose === 'OPENING' ? '录期初库存' : '新建盘点单'"
    width="640px"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
  >
    <el-form label-position="top" class="form-grid">
      <el-form-item label="用途">
        <el-segmented v-model="form.purpose" :options="PURPOSE_OPTIONS" />
      </el-form-item>

      <el-form-item label="仓库" required>
        <el-select v-model="form.warehouseId" filterable placeholder="选仓库">
          <el-option v-for="w in whOptions" :key="w.id" :label="whLabel(w)" :value="w.id" :disabled="openWhMap.has(w.id)" />
        </el-select>
      </el-form-item>

      <el-form-item label="主体" required>
        <el-select v-model="form.companyId" filterable placeholder="选主体">
          <el-option v-for="c in companies" :key="c.id" :label="c.shortName" :value="c.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="盘点日期">
        <el-date-picker v-model="form.countDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
      </el-form-item>

      <el-form-item label="盘点人" required>
        <UserSelect v-model="form.counterId" :label="form.counterName" />
      </el-form-item>

      <el-form-item label="范围" class="span-all">
        <el-segmented v-model="form.scope" :disabled="form.purpose === 'OPENING'" :options="SCOPE_OPTIONS" />
        <p v-if="form.purpose === 'OPENING'" class="tip dim">可以先不选物料，建好后导入</p>
      </el-form-item>

      <el-form-item v-if="form.scope === 'CATEGORY'" label="物料分类" class="span-all">
        <el-select v-model="form.categoryIds" multiple filterable placeholder="选一个或几个分类">
          <el-option v-for="c in level2Categories" :key="c.id" :label="c.label" :value="c.id" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="form.scope === 'PICKED'" label="物料" class="span-all">
        <div class="picked-box">
          <span v-for="(s, i) in pickedSkus" :key="s.id" class="sku-chip">
            <span class="code">{{ s.code }}</span> {{ s.name }}
            <button type="button" class="x" @click="pickedSkus.splice(i, 1)">×</button>
          </span>
          <el-button size="small" @click="skuPicking = true">从物料库选</el-button>
        </div>
      </el-form-item>

      <el-form-item label="备注" class="span-all">
        <el-input v-model="form.remark" maxlength="160" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">新建</el-button>
    </template>
  </el-dialog>

  <DataPick v-model="skuPicking" kind="sku" :have="pickedIds" @pick="onPickedSkus" />
</template>

<style scoped>
.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor) {
  width: 100%;
}

.span-all {
  grid-column: 1 / -1;
}

.tip {
  margin: 4px 0 0;
  font-size: 12px;
}

.picked-box {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.sku-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 12px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line);
  border-radius: 99px;
}

.x {
  padding: 0;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: none;
}
</style>
