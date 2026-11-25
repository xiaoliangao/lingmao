<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { materialApi, materialCategoryApi, type MaterialForm } from '@/api/basic'
import { MATERIAL_SOURCE_LABEL, type MaterialDetail, type MaterialSource, type TreeNode } from '@/types/basic'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { reloadDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { orNull } from '../common'
import SkuPicker from './SkuPicker.vue'
import { invalidSpecs, specsOf, type SpecCodeOverrides } from './sku'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()

/**
 * 新建、修改物料的对话框（RFC 003 §7）。新建时顺带选颜色、填规格建第一批 SKU；修改时分类、库存单位灰掉（建好不改）。
 * 保存前查重（同分类同名、同供应商同货号），有就问一声，不拦。
 * 默认供应商只列合作中的；原来选的那家后来停用了，照样显示、能原样保存（SupplierSelect）
 *
 *   <MaterialForm ref="formDialog" @saved="onSaved" />
 *   formDialog.value?.openCreate(categoryId)      formDialog.value?.openEdit(detail)
 */
const emit = defineEmits<{ saved: [id: string, created: boolean] }>()

type Num = number | null | undefined

/** 一期代工开关按"关"做（RFC 003 §3.3），默认来源不给选客供 */
const SOURCE_OPTIONS: MaterialSource[] = ['PURCHASE', 'FACTORY']

const visible = ref(false)
const detail = ref<MaterialDetail | null>(null)
const formRef = ref<FormInstance>()
const categories = ref<TreeNode[]>([])

const EMPTY = {
  categoryId: null as string | null,
  materialType: '' as string | null,
  name: '',
  stockUnit: '' as string | null,
  purchaseUnit: null as string | null,
  conversionFactor: 1 as Num,
  widthCm: undefined as Num,
  weightGsm: undefined as Num,
  composition: '',
  texture: '',
  fabricFunction: '',
  withTag: null as boolean | null,
  tagNames: '',
  defaultSupplierId: null as string | null,
  supplierItemNo: '',
  refPrice: undefined as Num,
  moq: undefined as Num,
  minPackQty: 1 as Num,
  leadDays: undefined as Num,
  year: undefined as Num,
  season: null as string | null,
  defaultSource: 'PURCHASE' as MaterialSource,
  defaultLossPct: undefined as Num,
  reservePct: undefined as Num,
  remark: '',
  // 新建时的第一批 SKU
  colorIds: [] as string[],
  specs: '',
  specCodes: {} as SpecCodeOverrides,
}
const form = reactive(structuredClone(EMPTY))
const guard = useUnsavedChanges(form, visible)

/** 领猫导进来的带吊牌物料没有吊牌名称（领猫没有这一列）：没改成别的时名称可以先空着，后端同样放行；新选"是"的必填 */
const tagNamesOptional = computed(() => detail.value?.material.withTag === true && !detail.value.material.tagNames)
const rules = computed<FormRules>(() => ({
  categoryId: [{ required: true, message: '请选分类', trigger: 'change' }],
  materialType: [{ required: true, message: '请选物料类型', trigger: 'change' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  stockUnit: [{ required: true, message: '请选库存单位', trigger: 'change' }],
  tagNames: tagNamesOptional.value ? [] : [{ required: true, whitespace: true, message: '带功能吊牌的要写吊牌名称', trigger: 'blur' }],
}))

/** 采购单位不填或和库存单位一样：系数只能是 1 */
const sameUnit = computed(() => !form.purchaseUnit || form.purchaseUnit === form.stockUnit)
/** 面料、里料才填面料功能（RFC 012 §5.1） */
const isFabric = computed(() => form.materialType === 'FABRIC' || form.materialType === 'LINING')
watch(sameUnit, (same) => {
  if (same) form.conversionFactor = 1
})

/** 分类下拉：启用的物料分类，只能选二级（一级只是分组） */
interface CategoryOption { value: string; label: string; disabled?: boolean; children?: CategoryOption[] }
const categoryOptions = computed<CategoryOption[]>(() =>
  categories.value.map((group) => ({
    value: group.id,
    label: group.name,
    disabled: true,
    children: (group.children ?? []).map((c) => ({ value: c.id, label: `${c.code ?? ''} ${c.name}` })),
  })),
)
const categoryCode = computed(() => {
  for (const group of categories.value) {
    const hit = group.children?.find((c) => c.id === form.categoryId)
    if (hit) return hit.code ?? ''
  }
  return ''
})
// 编码预览：按编码规则（RFC 018，可能按物料类型分），选了分类和类型就问后端；只看不取，保存时才编
const previewCode = ref('')
let previewSeq = 0
watch(
  () => [form.categoryId, form.materialType, categoryCode.value] as const,
  async ([categoryId, materialType, code]) => {
    const current = ++previewSeq
    previewCode.value = ''
    if (detail.value || !categoryId || !code || !materialType) return
    try {
      const preview = await materialApi.codePreview(categoryId, materialType)
      if (current === previewSeq) previewCode.value = preview
    } catch (e) {
      if (current === previewSeq) previewCode.value = e instanceof Error ? e.message : ''
    }
  },
)
const codePreview = computed(() => {
  if (!categoryCode.value) return '选了分类才有'
  if (!form.materialType) return '选了物料类型才有'
  return previewCode.value || '…'
})

async function openCreate(categoryId?: string | null) {
  categories.value = await materialCategoryApi.enabledTree()
  detail.value = null
  // 左边选的是二级分类就带上；选的是一级或"全部"就空着
  const leaf = categories.value.some((g) => g.children?.some((c) => c.id === categoryId))
  // 损耗率、备料比由生产评估后填，新建时不预填（PRD Q18，甲方 11-18）
  Object.assign(form, structuredClone(EMPTY), { categoryId: leaf ? categoryId : null })
  visible.value = true
}

function openEdit(target: MaterialDetail) {
  detail.value = target
  const m = target.material
  Object.assign(form, structuredClone(EMPTY), {
    categoryId: m.categoryId, materialType: m.materialType, name: m.name, stockUnit: m.stockUnit,
    purchaseUnit: m.purchaseUnit === m.stockUnit ? null : m.purchaseUnit, conversionFactor: m.conversionFactor,
    widthCm: m.widthCm ?? undefined, weightGsm: m.weightGsm ?? undefined, composition: m.composition ?? '', texture: m.texture ?? '',
    fabricFunction: m.fabricFunction ?? '', withTag: m.withTag, tagNames: m.tagNames ?? '',
    defaultSupplierId: m.defaultSupplierId, supplierItemNo: m.supplierItemNo ?? '', refPrice: m.refPrice ?? undefined, moq: m.moq ?? undefined, minPackQty: m.minPackQty,
    leadDays: m.leadDays ?? undefined, year: m.year ?? undefined, season: m.season, defaultSource: m.defaultSource,
    defaultLossPct: m.defaultLossPct ?? undefined, reservePct: m.reservePct ?? undefined, remark: m.remark ?? '',
  })
  visible.value = true
}

function body(): MaterialForm {
  return {
    name: form.name.trim(),
    categoryId: form.categoryId,
    materialType: form.materialType ?? '',
    stockUnit: form.stockUnit ?? '',
    purchaseUnit: form.purchaseUnit || null,
    conversionFactor: sameUnit.value ? 1 : (form.conversionFactor ?? null),
    widthCm: form.widthCm ?? null,
    weightGsm: form.weightGsm ?? null,
    composition: orNull(form.composition),
    texture: orNull(form.texture),
    fabricFunction: isFabric.value ? orNull(form.fabricFunction) : null,
    withTag: form.withTag,
    tagNames: form.withTag === true ? orNull(form.tagNames) : null,
    defaultSupplierId: form.defaultSupplierId,
    supplierItemNo: orNull(form.supplierItemNo),
    refPrice: form.refPrice ?? null,
    moq: form.moq ?? null,
    minPackQty: form.minPackQty ?? null,
    leadDays: form.leadDays ?? null,
    year: form.year ?? null,
    season: form.season || null,
    defaultSource: form.defaultSource,
    defaultLossPct: form.defaultLossPct ?? null,
    reservePct: form.reservePct ?? null,
    remark: orNull(form.remark),
  }
}

/** 查重只提示不拦：有可能重复的，问一声，点"仍然保存"才继续 */
async function confirmDuplicates(data: MaterialForm) {
  const dups = await materialApi.dupCheck({
    categoryId: data.categoryId,
    name: data.name,
    defaultSupplierId: data.defaultSupplierId,
    supplierItemNo: data.supplierItemNo,
    excludeId: detail.value?.material.id,
  })
  if (!dups.length) return
  const lines = dups.map((d) => `${d.code} ${d.name}（${d.reason}）`).join('；')
  await ElMessageBox.confirm(`可能和这些物料重复：${lines}。确定${detail.value ? '保存' : '新建'}？`, '可能重复', {
    confirmButtonText: detail.value ? '仍然保存' : '仍然新建',
    cancelButtonText: '回去改',
  })
}

const saving = ref(false)

async function save() {
  await formRef.value?.validate()
  const bad = detail.value ? [] : invalidSpecs(form.specs, form.specCodes)
  if (bad.length) {
    ElMessage.warning(`规格 ${bad.join('、')} 的规格码不对：只能是字母、数字、小数点，最长 16 位，可以在"规格码"里改`)
    return
  }
  const data = body()
  await confirmDuplicates(data)
  saving.value = true
  try {
    if (detail.value) {
      await materialApi.update(detail.value.material.id, data)
      ElMessage.success('已保存')
      visible.value = false
      emit('saved', detail.value.material.id, false)
    } else {
      const id = await materialApi.create({ ...data, colorIds: form.colorIds, specs: specsOf(form.specs, form.specCodes) })
      ElMessage.success(`建好了物料 ${data.name}`)
      visible.value = false
      emit('saved', id, true)
    }
  } finally {
    saving.value = false
  }
}

/** 物料类型下拉里找不到就当场加（PRD §6.1.5 规则 6）：加的是字典"物料类型"的一项，加完直接选上 */
const typeSelect = ref<InstanceType<typeof DictSelect>>()
const typeDialog = reactive({ visible: false, label: '', reservePct: undefined as Num, saving: false })

function openAddType() {
  typeSelect.value?.blur()
  Object.assign(typeDialog, { visible: true, label: '', reservePct: undefined })
}

async function addType() {
  const label = typeDialog.label.trim()
  if (!label) {
    ElMessage.warning('请填物料类型的名称')
    return
  }
  typeDialog.saving = true
  try {
    const option = await materialApi.addType({ label, reservePct: typeDialog.reservePct ?? null })
    await reloadDict('material_type')
    form.materialType = option.value
    typeDialog.visible = false
    ElMessage.success(`加了物料类型「${option.label}」，已经选上`)
  } finally {
    typeDialog.saving = false
  }
}

defineExpose({ openCreate, openEdit })
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="detail ? `编辑物料 ${detail.material.code}` : '新建物料'"
    width="min(760px, 96vw)"
    top="6vh"
    :before-close="guard.beforeClose"
  >
    <el-form ref="formRef" class="form-grid three" :model="form" :rules="rules" label-position="top">
      <el-form-item label="分类" prop="categoryId">
        <span v-if="detail" class="fixed">
          <span class="code">{{ detail.categoryCode }}</span> {{ [detail.parentCategoryName, detail.categoryName].filter(Boolean).join(' / ') }}
        </span>
        <el-tree-select
          v-else
          v-model="form.categoryId"
          :data="categoryOptions"
          filterable
          default-expand-all
          :render-after-expand="false"
          placeholder="选二级分类"
          no-data-text="还没有启用的物料分类，在 资料 › 基础 › 物料分类 里加"
        />
      </el-form-item>
      <el-form-item label="物料类型" prop="materialType">
        <DictSelect ref="typeSelect" v-model="form.materialType" type="material_type">
          <template #footer>
            <el-button link type="primary" @click="openAddType">新增物料类型</el-button>
          </template>
        </DictSelect>
      </el-form-item>
      <el-form-item label="编码">
        <span class="fixed code">{{ detail ? detail.material.code : codePreview }}</span>
      </el-form-item>

      <el-form-item label="名称" prop="name" class="span2">
        <el-input v-model="form.name" maxlength="200" placeholder="如 220g 斜纹棉" />
      </el-form-item>
      <el-form-item label="季节">
        <DictSelect v-model="form.season" type="season" clearable />
      </el-form-item>

      <el-form-item label="库存单位" prop="stockUnit">
        <DictSelect v-model="form.stockUnit" type="unit" :disabled="!!detail" :placeholder="detail ? '' : '建好不改'" />
      </el-form-item>
      <el-form-item label="采购单位">
        <DictSelect v-model="form.purchaseUnit" type="unit" clearable placeholder="不填 = 库存单位" />
      </el-form-item>
      <el-form-item label="换算系数">
        <el-input-number
          v-model="form.conversionFactor"
          :min="0.000001"
          :step="1"
          :disabled="sameUnit"
          controls-position="right"
          :value-on-clear="null"
        />
        <span v-if="!sameUnit" class="dim hint">1 采购单位 = 这么多库存单位</span>
      </el-form-item>

      <el-form-item label="门幅（厘米）">
        <el-input-number v-model="form.widthCm" :min="0" :controls="false" :value-on-clear="null" />
      </el-form-item>
      <el-form-item label="克重（g/㎡）">
        <el-input-number v-model="form.weightGsm" :min="0" :controls="false" :value-on-clear="null" />
      </el-form-item>
      <el-form-item label="年份">
        <el-input-number v-model="form.year" :min="1900" :max="2999" :controls="false" :value-on-clear="null" />
      </el-form-item>

      <el-form-item label="成分" class="span2">
        <el-input v-model="form.composition" maxlength="255" placeholder="如 100% 棉" />
      </el-form-item>
      <el-form-item label="组织 / 纹理">
        <el-input v-model="form.texture" maxlength="64" placeholder="如 斜纹" />
      </el-form-item>

      <el-form-item v-if="isFabric" label="面料功能" class="span2">
        <el-input v-model="form.fabricFunction" maxlength="255" placeholder="如 防水、抗皱" />
      </el-form-item>
      <el-form-item label="带功能吊牌">
        <el-select v-model="form.withTag" clearable placeholder="没填">
          <el-option label="是" :value="true" />
          <el-option label="否" :value="false" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.withTag === true" label="吊牌名称" prop="tagNames" class="span2">
        <el-input v-model="form.tagNames" maxlength="255" :placeholder="tagNamesOptional ? '领猫导入时没有，知道了再补；几个用顿号隔开' : '几个用顿号隔开，如 coolmax 吊牌'" />
      </el-form-item>

      <el-form-item label="供应商货号">
        <el-input v-model="form.supplierItemNo" maxlength="64" />
      </el-form-item>
      <el-form-item v-if="canSeeCost" label="参考单价（元 / 采购单位）">
        <el-input-number v-model="form.refPrice" :min="0" :controls="false" :value-on-clear="null" />
      </el-form-item>
      <el-form-item label="起订量（采购单位）">
        <el-input-number v-model="form.moq" :min="0" :controls="false" :value-on-clear="null" />
      </el-form-item>

      <el-form-item label="最小包装量（采购单位）">
        <el-input-number v-model="form.minPackQty" :min="0.001" :controls="false" :value-on-clear="null" placeholder="不填 = 1" />
      </el-form-item>
      <el-form-item label="采购周期（天）">
        <el-input-number v-model="form.leadDays" :min="0" :max="3650" :step="1" step-strictly :controls="false" :value-on-clear="null" />
      </el-form-item>
      <el-form-item label="默认物料来源">
        <el-select v-model="form.defaultSource">
          <el-option v-for="s in SOURCE_OPTIONS" :key="s" :label="MATERIAL_SOURCE_LABEL[s]" :value="s" />
          <el-option v-if="!SOURCE_OPTIONS.includes(form.defaultSource)" :label="MATERIAL_SOURCE_LABEL[form.defaultSource]" :value="form.defaultSource" />
        </el-select>
      </el-form-item>

      <el-form-item label="默认损耗率（%）">
        <el-input-number v-model="form.defaultLossPct" :min="0" :max="100" :controls="false" :value-on-clear="null" placeholder="由生产评估，不填 = 0" />
      </el-form-item>
      <el-form-item label="备料比（%）">
        <el-input-number v-model="form.reservePct" :min="0" :max="100" :controls="false" :value-on-clear="null" placeholder="不填按物料类型" />
      </el-form-item>
      <el-form-item label="默认供应商">
        <SupplierSelect v-model="form.defaultSupplierId" usage="BULK" :label="detail?.defaultSupplierName" clearable />
      </el-form-item>

      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>

      <el-form-item v-if="!detail" label="SKU" class="full">
        <SkuPicker v-model:color-ids="form.colorIds" v-model:specs="form.specs" v-model:spec-codes="form.specCodes" :material-code="categoryCode ? `${categoryCode}····` : '····'" class="picker" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ detail ? '保存' : '新建物料' }}</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="typeDialog.visible" title="新增物料类型" width="min(360px, 96vw)" append-to-body>
    <el-form label-position="top" @submit.prevent="addType">
      <el-form-item label="名称" required>
        <el-input v-model="typeDialog.label" maxlength="64" placeholder="如 织带" />
      </el-form-item>
      <el-form-item label="备料比（%）">
        <el-input-number v-model="typeDialog.reservePct" :min="0" :max="100" :controls="false" :value-on-clear="null" placeholder="由生产评估，不填 = 0" />
        <span class="dim hint">这类物料上没设备料比时，算料按它多备</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="typeDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="typeDialog.saving" @click="addType">新增</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.form-grid.three .span2 {
  grid-column: span 2;
}

@media (max-width: 600px) {
  .form-grid.three {
    grid-template-columns: minmax(0, 1fr);
  }

  .form-grid.three .span2 {
    grid-column: auto;
  }
}

.form-grid :deep(.el-input-number) {
  width: 100%;
}

.form-grid :deep(.el-input-number .el-input__inner) {
  text-align: left;
}

.fixed {
  line-height: 32px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.picker {
  width: 100%;
}
</style>
