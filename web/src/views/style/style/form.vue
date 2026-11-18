<script setup lang="ts">
import { routeId } from '@/utils/entityId'
import { businessToday } from '@/utils/businessDate'
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { categoryApi, sizeGroupApi } from '@/api/basic'
import { styleApi, type StyleCreateForm } from '@/api/style'
import type { MaterialPickRow, SizeGroup, TreeNode } from '@/types/basic'
import type { StylePickRow } from '@/types/style'
import PageHeader from '@/components/PageHeader.vue'
import BlankState from '@/components/BlankState.vue'
import DictSelect from '@/components/DictSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import ColorSelect from '@/components/ColorSelect.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import DataPick from '@/components/DataPick.vue'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'
import DesignFiles from './DesignFiles.vue'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()

/**
 * 新建、编辑款式（/styles/new、/styles/:id/edit，RFC 005 §9）：字段多，整页表单，照原型 StyleForm 的分组。
 * - 款号：新建时选"自动编号"（大类 + 今天 + 设计师编号 + 2 位流水，保存时才取号，这里只是预览）或"手填"；建好不改
 * - 大类进了款号，建好不改；已经生成 SKU 的不能换尺码组（后端也拦）
 * - 新建时能带颜色、图片、设计附件：按 颜色 × 尺码组 当场生成 SKU。编辑时颜色、图片、附件在详情页改
 * 一个地址一个页签（router/index.ts 的 DETAIL_ROUTES）：id 在这里取一次
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()
const path = route.path
const id = route.params.id ? routeId(route.params.id) : null
const editing = id !== null

const classes = useDict('style_class')
const sources = useDict('style_source')

const thisYear = Number(businessToday().slice(0, 4))
const form = reactive<StyleCreateForm>({
  codeMode: 'AUTO',
  code: '',
  name: '',
  aliasName: '',
  styleClass: '',
  brand: '',
  categoryId: null,
  year: thisYear + 1,
  season: '',
  wave: null,
  styleTag: null,
  series: null,
  gender: null,
  designerId: user.info?.id ?? null,
  patternMakerId: null,
  source: 'OWN',
  sizeGroupId: null,
  mainMaterialId: null,
  productName: '',
  composition: '',
  execStandard: null,
  safetyCategory: null,
  tagPrice: null,
  refCost: null,
  defaultFactoryId: null,
  launchDate: null,
  remark: '',
  colorIds: [],
  images: [],
  files: [],
})
const guard = useUnsavedChanges(form)
/** 默认加工厂只列成衣厂、加工厂（RFC 005 §9）；只是默认值，存的时候不查（架构 A23） */
const FACTORY_CATEGORIES = ['GARMENT', 'PROCESSOR']

/** 编辑时给下拉框显示名字用（它们还没搜过，不知道 id 叫什么），以及只读的几项 */
const names = reactive({
  code: '',
  designer: (user.info?.nickname ?? null) as string | null,
  patternMaker: null as string | null,
  factory: null as string | null,
  material: null as string | null,
  categoryName: null as string | null,
})
const skuCount = ref(0)
const loaded = ref(!editing)
const notFound = ref(false)

// ---------- 下拉的选项 ----------
const categories = shallowRef<TreeNode[]>([])
const sizeGroups = shallowRef<SizeGroup[]>([])

const categoryOptions = computed(() => {
  const map = (nodes: TreeNode[] = []): { value: string; label: string; children: unknown[] }[] =>
    nodes.map((n) => ({ value: n.id, label: n.name, children: map(n.children) }))
  const options = map(categories.value)
  // 编辑旧款：品类停用了不在启用的树里，单独列出来，不换也能原样保存
  const has = (nodes: { value: string; children: unknown[] }[]): boolean =>
    nodes.some((n) => n.value === form.categoryId || has(n.children as typeof nodes))
  if (form.categoryId && names.categoryName && !has(options)) {
    options.unshift({ value: form.categoryId, label: `${names.categoryName}（已停用）`, children: [] })
  }
  return options
})
const sizeGroupOptions = computed(() => sizeGroups.value)
const sizeGroup = computed(() => sizeGroups.value.find((g) => g.id === form.sizeGroupId))
const years = computed(() => [...new Set([thisYear - 1, thisYear, thisYear + 1, thisYear + 2, form.year].filter((y): y is number => !!y))].sort())

async function loadOptions() {
  const [tree, groups] = await Promise.all([categoryApi.enabledTree(), sizeGroupApi.options()])
  categories.value = tree
  sizeGroups.value = groups
}

async function load() {
  if (!editing) {
    await loadOptions()
    const firstClass = classes.enabled.value[0]?.value
    if (firstClass && !form.styleClass) form.styleClass = firstClass
    form.sizeGroupId ??= sizeGroups.value[0]?.id ?? null
    guard.markSaved()
    return
  }
  try {
    const [d] = await Promise.all([styleApi.get(id!), loadOptions()])
    Object.assign(form, {
      name: d.name, aliasName: d.aliasName ?? '', styleClass: d.styleClass, brand: d.brand, categoryId: d.categoryId, year: d.year,
      season: d.season, wave: d.wave, styleTag: d.styleTag, series: d.series, gender: d.gender, designerId: d.designerId,
      patternMakerId: d.patternMakerId, source: d.source, sizeGroupId: d.sizeGroupId, mainMaterialId: d.mainMaterialId,
      productName: d.productName ?? '', composition: d.composition ?? '', execStandard: d.execStandard, safetyCategory: d.safetyCategory,
      tagPrice: d.tagPrice, refCost: d.refCost, defaultFactoryId: d.defaultFactoryId, launchDate: d.launchDate, remark: d.remark ?? '',
    })
    Object.assign(names, {
      code: d.code, designer: d.designerName, patternMaker: d.patternMakerName, factory: d.defaultFactoryName,
      material: d.mainMaterialCode ? `${d.mainMaterialCode} ${d.mainMaterialName}` : null, categoryName: d.categoryName,
    })
    skuCount.value = d.skuCount
    // 编辑旧款：尺码组停用了也留着（不在启用的列表里，补进去）
    if (!sizeGroups.value.some((g) => g.id === d.sizeGroupId)) {
      sizeGroups.value = [{ id: d.sizeGroupId, name: `${d.sizeGroupName}（已停用）`, baseSize: '', status: 0, sizes: d.sizes }, ...sizeGroups.value]
    }
    tabs.rename(path, `编辑 ${d.code}`)
    loaded.value = true
    guard.markSaved()
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

onMounted(load)
// 大类字典晚到：新建时默认选第一个启用的大类
watch(
  () => classes.enabled.value,
  (options) => {
    if (!editing && !form.styleClass && options[0]) {
      form.styleClass = options[0].value
      guard.markSaved()
    }
  },
)

// ---------- 款号预览：自动编号时，大类、设计师一变就问后端；不占号，编不出来的原因写在下面 ----------
const preview = reactive({ code: '', error: '', loading: false })
let previewSeq = 0
let previewTimer: ReturnType<typeof setTimeout> | undefined

async function refreshPreview() {
  const current = ++previewSeq
  if (!form.styleClass) {
    Object.assign(preview, { code: '', error: '先选大类', loading: false })
    return
  }
  preview.loading = true
  try {
    const code = await styleApi.codePreview(form.styleClass, form.designerId)
    if (current === previewSeq) Object.assign(preview, { code, error: '' })
  } catch (e) {
    if (current === previewSeq) Object.assign(preview, { code: '', error: e instanceof Error ? e.message : '编不出款号，请手填' })
  } finally {
    if (current === previewSeq) preview.loading = false
  }
}

watch(
  () => [form.codeMode, form.styleClass, form.designerId] as const,
  ([mode]) => {
    if (editing || mode !== 'AUTO') return
    clearTimeout(previewTimer)
    previewTimer = setTimeout(refreshPreview, 250)
  },
  { immediate: true },
)

// ---------- 主力面料：从物料库挑一个 SKU，记它的物料 ----------
const materialPicking = ref(false)

function pickMaterial(rows: MaterialPickRow[] | StylePickRow[]) {
  const row = (rows as MaterialPickRow[])[0]
  if (!row) return
  form.mainMaterialId = row.materialId
  names.material = `${row.materialCode} ${row.materialName}`
  if ((rows as MaterialPickRow[]).length > 1) ElMessage.info(`主力面料只要一个，用了第一个：${row.materialCode}`)
}

function clearMaterial() {
  form.mainMaterialId = null
  names.material = null
}

// ---------- 颜色 → SKU 预览 ----------
const skuPreview = computed(() => {
  const sizes = sizeGroup.value?.sizes.length ?? 0
  return { colors: form.colorIds.length, sizes, total: form.colorIds.length * sizes }
})

// ---------- 校验、保存 ----------
const formRef = ref<FormInstance>()
const MANUAL_CODE = /^[A-Z0-9-]{3,20}$/
const rules: FormRules = {
  code: [{
    validator: (_rule, _value, callback) => {
      if (editing || form.codeMode !== 'MANUAL') return callback()
      const code = (form.code ?? '').trim().toUpperCase()
      if (!code) return callback(new Error('请填款号，或者改成自动编号'))
      if (!MANUAL_CODE.test(code)) return callback(new Error('款号只能是 3-20 位的字母、数字和横杠'))
      callback()
    },
    trigger: 'blur',
  }],
  name: [{ required: true, whitespace: true, message: '请填款式名称', trigger: 'blur' }],
  styleClass: [{ required: true, message: '请选择大类', trigger: 'change' }],
  brand: [{ required: true, message: '请选择品牌', trigger: 'change' }],
  categoryId: [{ required: true, message: '请选择品类', trigger: 'change' }],
  year: [{ required: true, message: '请选择年份', trigger: 'change' }],
  season: [{ required: true, message: '请选择季节', trigger: 'change' }],
  sizeGroupId: [{ required: true, message: '请选择尺码组', trigger: 'change' }],
}

const saving = ref(false)

function body() {
  return {
    ...form,
    code: form.codeMode === 'MANUAL' ? (form.code ?? '').trim().toUpperCase() : null,
    name: form.name.trim(),
    aliasName: orNull(form.aliasName),
    productName: orNull(form.productName),
    composition: orNull(form.composition),
    remark: orNull(form.remark),
  }
}

async function save() {
  if (!(await guard.waitForUploads())) return
  try {
    await formRef.value?.validate()
  } catch {
    ElMessage.warning('有几项没填对，看标红的地方')
    return
  }
  saving.value = true
  try {
    if (editing) {
      const { codeMode: _m, code: _c, colorIds: _ids, images: _i, files: _f, ...data } = body()
      await styleApi.update(id!, data)
      guard.markSaved()
      ElMessage.success(`${names.code} 已保存`)
      await leave(`/styles/${id}`)
    } else {
      const result = await styleApi.create(body())
      guard.markSaved()
      ElMessage.success(result.skuCount ? `${result.code} 已建好，生成了 ${result.skuCount} 个 SKU` : `${result.code} 已建好`)
      await leave(`/styles/${result.id}`)
    }
  } finally {
    saving.value = false
  }
}

/** 去别的页，关掉这个表单页签（取消是明确不要了，不再问） */
async function leave(to: string) {
  guard.discardUploads()
  guard.markSaved()
  await router.push(to)
  tabs.close(path, to)
}

const backTo = editing ? `/styles/${id}` : '/styles'
const title = computed(() => (editing ? `编辑款式 ${names.code}` : '新建款式'))
</script>

<template>
  <div>
    <PageHeader :title="title" :back="{ label: editing ? names.code || '款式' : '款式', to: backTo }">
      <template #actions>
        <el-button @click="leave(backTo)">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!loaded" @click="save">{{ editing ? '保存' : '建款式' }}</el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这个款式不存在，可能已经删了">
      <el-button @click="leave('/styles')">回款式列表</el-button>
    </BlankState>

    <div v-else class="page">
      <el-form ref="formRef" v-loading="!loaded" :model="form" :rules="rules" label-position="top" class="style-form">
        <h3 class="sec">基本</h3>
        <div class="grid">
          <el-form-item label="款号" prop="code" class="code-item">
            <template v-if="editing">
              <el-input :model-value="names.code" disabled class="code" title="款号建好不改" />
            </template>
            <template v-else>
              <div class="code-row">
                <el-segmented v-model="form.codeMode" :options="[{ label: '自动编号', value: 'AUTO' }, { label: '手填', value: 'MANUAL' }]" />
                <el-input
                  v-if="form.codeMode === 'MANUAL'"
                  v-model="form.code"
                  class="code"
                  maxlength="20"
                  placeholder="如 27CF009，转大写"
                  @blur="form.code = (form.code ?? '').trim().toUpperCase()"
                />
              </div>
              <p v-if="form.codeMode === 'AUTO'" class="hint">
                <template v-if="preview.code">
                  保存时编号，大概是 <span class="code">{{ preview.code }}</span>（同时有人建就往后排）
                </template>
                <span v-else-if="preview.error" class="warn">{{ preview.error }}</span>
                <template v-else>正在算款号……</template>
              </p>
            </template>
          </el-form-item>
          <el-form-item label="款式名称" prop="name">
            <el-input v-model="form.name" maxlength="100" placeholder="如：双排扣短风衣" />
          </el-form-item>
          <el-form-item label="花名">
            <el-input v-model="form.aliasName" maxlength="50" placeholder="选填，款式的俗称" />
          </el-form-item>
          <el-form-item label="大类" prop="styleClass">
            <DictSelect v-model="form.styleClass" type="style_class" :disabled="editing" :title="editing ? '大类进了款号，建好不改' : ''" />
          </el-form-item>
          <el-form-item label="设计师">
            <UserSelect v-model="form.designerId" :label="names.designer" clearable />
          </el-form-item>
          <el-form-item label="版师">
            <UserSelect v-model="form.patternMakerId" :label="names.patternMaker" clearable />
          </el-form-item>
          <el-form-item label="品牌" prop="brand">
            <DictSelect v-model="form.brand" type="brand" no-data-text="品牌字典还没有选项：找管理员在字典管理里加，或者导入款式时带进来" />
          </el-form-item>
          <el-form-item label="品类" prop="categoryId">
            <el-tree-select v-model="form.categoryId" :data="categoryOptions" filterable default-expand-all placeholder="选到最末一级" />
          </el-form-item>
          <el-form-item label="来源">
            <el-select v-model="form.source" filterable>
              <el-option v-for="o in sources.enabled.value" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="年份" prop="year">
            <el-select v-model="form.year" filterable>
              <el-option v-for="y in years" :key="y" :label="String(y)" :value="y" />
            </el-select>
          </el-form-item>
          <el-form-item label="季节" prop="season">
            <DictSelect v-model="form.season" type="season" />
          </el-form-item>
          <el-form-item label="波段">
            <DictSelect v-model="form.wave" type="wave" clearable />
          </el-form-item>
          <el-form-item label="风格">
            <DictSelect v-model="form.styleTag" type="style_tag" clearable />
          </el-form-item>
          <el-form-item label="系列">
            <DictSelect v-model="form.series" type="series" clearable no-data-text="系列字典还没有选项，不用填" />
          </el-form-item>
          <el-form-item label="性别">
            <DictSelect v-model="form.gender" type="gender" clearable />
          </el-form-item>
        </div>

        <h3 class="sec">尺码、价格、生产</h3>
        <div class="grid">
          <el-form-item label="尺码组" prop="sizeGroupId">
            <el-select v-model="form.sizeGroupId" filterable :disabled="editing && skuCount > 0">
              <el-option v-for="g in sizeGroupOptions" :key="g.id" :label="`${g.name}\u3000${g.sizes.join(' ')}`" :value="g.id" />
            </el-select>
            <p v-if="editing && skuCount > 0" class="hint">已经生成 {{ skuCount }} 个 SKU，不能换尺码组</p>
          </el-form-item>
          <el-form-item label="吊牌价（元）">
            <el-input-number v-model="form.tagPrice" :min="0" :precision="2" :controls="false" :value-on-clear="null" />
          </el-form-item>
          <el-form-item v-if="canSeeCost" label="参考成本价（元）">
            <el-input-number v-model="form.refCost" :min="0" :precision="4" :controls="false" :value-on-clear="null" placeholder="一期手填" />
          </el-form-item>
          <el-form-item label="主力面料">
            <div class="pick-row">
              <span v-if="names.material" class="picked">{{ names.material }}</span>
              <el-button size="small" @click="materialPicking = true">{{ names.material ? '换一个' : '从物料库选' }}</el-button>
              <el-button v-if="names.material" link size="small" @click="clearMaterial">不填</el-button>
            </div>
          </el-form-item>
          <el-form-item label="默认加工厂">
            <SupplierSelect v-model="form.defaultFactoryId" :category="FACTORY_CATEGORIES" :label="names.factory" clearable />
          </el-form-item>
          <el-form-item label="上市日期">
            <el-date-picker v-model="form.launchDate" type="date" value-format="YYYY-MM-DD" placeholder="选填" class="date" />
          </el-form-item>
        </div>

        <h3 class="sec">吊牌</h3>
        <div class="grid">
          <el-form-item label="品名">
            <el-input v-model="form.productName" maxlength="50" placeholder="吊牌上的品名，如 风衣" />
          </el-form-item>
          <el-form-item label="执行标准">
            <DictSelect v-model="form.execStandard" type="exec_standard" clearable />
          </el-form-item>
          <el-form-item label="安全类别">
            <DictSelect v-model="form.safetyCategory" type="safety_category" clearable />
          </el-form-item>
          <el-form-item label="成分" class="wide">
            <el-input v-model="form.composition" maxlength="255" placeholder="如 面料：100% 棉；里料：100% 聚酯纤维" />
          </el-form-item>
          <el-form-item label="备注" class="wide">
            <el-input v-model="form.remark" maxlength="255" />
          </el-form-item>
        </div>

        <template v-if="!editing">
          <h3 class="sec">颜色、图片、附件</h3>
          <el-form-item label="颜色">
            <ColorSelect v-model="form.colorIds" />
            <p class="hint">
              <template v-if="skuPreview.total">{{ skuPreview.colors }} 个颜色 × {{ skuPreview.sizes }} 个尺码 = 建好就生成 {{ skuPreview.total }} 个 SKU</template>
              <template v-else>不选也能建，之后在详情的「颜色尺码」里加颜色、生成 SKU</template>
            </p>
          </el-form-item>
          <el-form-item label="款式图片（第一张是主图）">
            <ImageUpload v-model="form.images" :max="20" />
          </el-form-item>
          <el-form-item label="设计附件（图稿、设计说明）">
            <DesignFiles v-model="form.files" />
          </el-form-item>
        </template>
      </el-form>
    </div>

    <DataPick v-model="materialPicking" kind="sku" @pick="pickMaterial" />
  </div>
</template>

<style scoped>
.style-form {
  max-width: 1080px;
}

.sec {
  margin: 8px 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--lm-ink);
}

.sec:not(:first-child) {
  padding-top: 16px;
  border-top: 1px solid var(--lm-line-soft);
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 20px;
}

.grid .wide {
  grid-column: span 3;
}

.code-item {
  grid-column: span 3;
}

.code-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.code-row .el-input,
.code-item > :deep(.el-form-item__content) > .el-input {
  width: 260px;
}

.hint {
  width: 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--lm-muted);
}

.warn {
  color: var(--lm-pending-text);
}

.pick-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}

.picked {
  font-size: 13px;
}

.style-form :deep(.el-select),
.style-form :deep(.el-input-number),
.style-form :deep(.date) {
  width: 100%;
}

.style-form :deep(.el-input-number .el-input__inner) {
  text-align: left;
}

@media (max-width: 1024px) {
  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .grid .wide,
  .code-item {
    grid-column: span 2;
  }
}

@media (max-width: 640px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .grid .wide,
  .code-item {
    grid-column: auto;
  }
}
</style>
