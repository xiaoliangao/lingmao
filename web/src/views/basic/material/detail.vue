<script setup lang="ts">
import { routeId } from '@/utils/entityId'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { materialApi } from '@/api/basic'
import { MATERIAL_SOURCE_LABEL, type MaterialDetail, type MaterialSku } from '@/types/basic'
import type { FileVO } from '@/types/system'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import PriceHistory from '@/components/price/PriceHistory.vue'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import { formatDateTime, formatPrice, formatQty } from '@/utils/format'
import { confirmRemove, orNull, toggleStatus } from '../common'
import MaterialForm from './MaterialForm.vue'
import SkuPicker from './SkuPicker.vue'
import { invalidSpecs, specsOf, type SpecCodeOverrides } from './sku'

/**
 * 物料详情（/materials/:id，RFC 003 §7、§12）：左边 SKU、价格记录、操作记录三个页签，右边图片和资料。
 * 价格记录（RFC 006 §9）要 price:price:list，没有就不显示这个页签。
 * 一条物料一个页签（详情路由，见 router/index.ts）：id 在这里取一次，不跟着路由变
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()
const id = routeId(route.params.id)
const path = route.path

const types = useDict('material_type')
const units = useDict('unit')
const seasons = useDict('season')

const detail = ref<MaterialDetail | null>(null)
const notFound = ref(false)
const tab = ref<'skus' | 'prices' | 'logs'>('skus')
const logList = ref<InstanceType<typeof DocLogList>>()
const loadLogs = () => materialApi.logs(id)
const canEdit = computed(() => user.hasPerm('basic:material:edit'))

async function load() {
  try {
    detail.value = await materialApi.get(id)
    tabs.rename(path, `物料 ${detail.value.material.code}`)
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

onMounted(load)

/** 改了东西：重新取一遍，操作记录也刷新 */
async function refresh() {
  await load()
  logList.value?.reload()
}

const m = computed(() => detail.value?.material)
const stockUnit = computed(() => units.labelOf(m.value?.stockUnit))
const purchaseUnit = computed(() => units.labelOf(m.value?.purchaseUnit))

/** 右边的资料，空的显示"—" */
const infoRows = computed(() => {
  const d = detail.value
  if (!d) return []
  const x = d.material
  const pct = (v: number | null) => (v == null ? '' : `${formatQty(v)}%`)
  return [
    ['分类', [d.parentCategoryName, d.categoryName].filter(Boolean).join(' / ')],
    ['类型', types.labelOf(x.materialType)],
    ['库存单位', stockUnit.value],
    ['采购单位', x.purchaseUnit === x.stockUnit ? purchaseUnit.value : `${purchaseUnit.value}（1 ${purchaseUnit.value} = ${formatQty(x.conversionFactor)} ${stockUnit.value}）`],
    ['门幅', x.widthCm == null ? '' : `${formatQty(x.widthCm)} 厘米`],
    ['克重', x.weightGsm == null ? '' : `${formatQty(x.weightGsm)} g/㎡`],
    ['成分', x.composition],
    ['组织 / 纹理', x.texture],
    ['默认供应商', d.defaultSupplierName],
    ['供应商货号', x.supplierItemNo],
    ['参考单价', x.refPrice == null ? '' : `${formatPrice(x.refPrice)} 元 / ${purchaseUnit.value}`],
    ['起订量', x.moq == null ? '' : `${formatQty(x.moq)} ${purchaseUnit.value}`],
    ['最小包装量', `${formatQty(x.minPackQty)} ${purchaseUnit.value}`],
    ['采购周期', x.leadDays == null ? '' : `${x.leadDays} 天`],
    ['默认来源', MATERIAL_SOURCE_LABEL[x.defaultSource] ?? x.defaultSource],
    ['默认损耗率', pct(x.defaultLossPct)],
    ['备料比', pct(x.reservePct)],
    ['年份 · 季节', [x.year, seasons.labelOf(x.season)].filter(Boolean).join(' · ')],
    ['创建', formatDateTime(x.createTime)],
    ['备注', x.remark],
  ].map(([label, value]) => ({ label: label as string, value: (value as string | null | undefined) || '—' }))
})

const legacy = computed(() => Object.entries(m.value?.legacyAttrs ?? {}))
const subLine = computed(() => {
  const d = detail.value
  if (!d) return ''
  return [d.categoryName, types.labelOf(d.material.materialType), d.material.composition, d.material.texture].filter(Boolean).join(' · ')
})

// ---------- 物料本身：编辑、启用停用、删除、图片 ----------
const formDialog = ref<InstanceType<typeof MaterialForm>>()

async function toggleMaterial() {
  const x = m.value!
  const consequence = '停用后它的 SKU 不能再选进新单据，已有库存照常出库，随时可以启用。'
  if (await toggleStatus(x, `物料 ${x.code}`, materialApi, id, consequence)) refresh()
}

async function removeMaterial() {
  const x = m.value!
  await confirmRemove(`删除物料 ${x.code}`, '删除以后连同 SKU、图片一起找不回来。已经被价格档案、单据用过的删不掉，请改成停用。')
  await materialApi.remove(id)
  ElMessage.success(`已删除 ${x.code}`)
  await router.push('/materials')
  tabs.close(path, '/materials')
}

async function saveImages(files: FileVO[]) {
  detail.value!.images = await materialApi.replaceImages(id, files)
  logList.value?.reload()
}

// ---------- 批量加 SKU ----------
const addVisible = ref(false)
const addForm = reactive({ colorIds: [] as string[], specs: '', specCodes: {} as SpecCodeOverrides })
const addGuard = useUnsavedChanges(addForm, addVisible)
const existingCodes = computed(() => detail.value?.skus.map((s) => s.code) ?? [])

function openAdd() {
  Object.assign(addForm, { colorIds: [], specs: '', specCodes: {} })
  addVisible.value = true
}

async function addSkus() {
  const bad = invalidSpecs(addForm.specs, addForm.specCodes)
  if (bad.length) {
    ElMessage.warning(`规格 ${bad.join('、')} 的规格码不对：只能是字母、数字、小数点，最长 16 位，可以在"规格码"里改`)
    return
  }
  const created = await materialApi.addSkus(id, { colorIds: addForm.colorIds, specs: specsOf(addForm.specs, addForm.specCodes) })
  if (created.length) ElMessage.success(`加了 ${created.length} 个 SKU`)
  else ElMessage.info('选的组合都已经有了，没有加')
  addVisible.value = false
  refresh()
}

// ---------- 改一个 SKU ----------
const skuVisible = ref(false)
const skuEditing = ref<MaterialSku | null>(null)
const skuForm = reactive({
  supplierColorNo: '', supplierItemNo: '', price: undefined as number | null | undefined,
  safetyStock: undefined as number | null | undefined, remark: '', images: [] as FileVO[],
})
const skuGuard = useUnsavedChanges(skuForm, skuVisible)

function openSku(sku: MaterialSku) {
  skuEditing.value = sku
  Object.assign(skuForm, {
    supplierColorNo: sku.supplierColorNo ?? '', supplierItemNo: sku.supplierItemNo ?? '', price: sku.price ?? undefined,
    safetyStock: sku.safetyStock ?? undefined, remark: sku.remark ?? '', images: sku.image ? [sku.image] : [],
  })
  skuVisible.value = true
}

async function saveSku() {
  if (!(await skuGuard.waitForUploads())) return
  const sku = skuEditing.value!
  await materialApi.updateSku(id, sku.id, {
    supplierColorNo: orNull(skuForm.supplierColorNo),
    supplierItemNo: orNull(skuForm.supplierItemNo),
    price: skuForm.price ?? null,
    safetyStock: skuForm.safetyStock ?? null,
    remark: orNull(skuForm.remark),
  })
  if ((skuForm.images[0]?.url ?? null) !== (sku.image?.url ?? null)) {
    await materialApi.replaceSkuImages(id, sku.id, skuForm.images)
  }
  ElMessage.success('已保存')
  skuVisible.value = false
  refresh()
}

async function toggleSku(sku: MaterialSku) {
  const api = { enable: (skuId: string) => materialApi.enableSku(id, skuId), disable: (skuId: string) => materialApi.disableSku(id, skuId) }
  if (await toggleStatus(sku, `SKU ${sku.code}`, api, sku.id, '停用后这个 SKU 不能再选进新单据，已有库存照常出库。')) refresh()
}

async function removeSku(sku: MaterialSku) {
  await confirmRemove(`删除 SKU ${sku.code}`, '删除以后找不回来。已经被单据、价格档案用过的删不掉，请改成停用；物料至少要留一个 SKU。')
  await materialApi.removeSku(id, sku.id)
  ElMessage.success('已删除')
  refresh()
}

/** 操作记录里 SKU 相关动作的叫法（通用的新建、修改、启用、停用、换图片由 DocLogList 给） */
const LOG_ACTIONS = {
  addSkus: '加 SKU',
  updateSku: '改 SKU',
  deleteSku: '删 SKU',
  enableSku: '启用 SKU',
  disableSku: '停用 SKU',
  skuImages: '换 SKU 图片',
}
</script>

<template>
  <div>
    <PageHeader :title="m ? `${m.code} ${m.name}` : '物料'" :back="{ label: '物料', to: '/materials' }">
      <template v-if="m" #title>
        <StatusTag :tone="m.status === 1 ? 'active' : 'off'">{{ m.status === 1 ? '启用' : '停用' }}</StatusTag>
      </template>
      <template v-if="m" #actions>
        <el-button v-perm="'basic:material:remove'" type="danger" plain @click="removeMaterial">删除</el-button>
        <el-button v-perm="'basic:material:edit'" @click="toggleMaterial">{{ m.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button v-perm="'basic:material:edit'" icon="Plus" @click="openAdd">批量加 SKU</el-button>
        <el-button v-perm="'basic:material:edit'" type="primary" @click="formDialog?.openEdit(detail!)">编辑</el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这个物料不存在，可能已经删了">
      <el-button @click="router.push('/materials')">回物料列表</el-button>
    </BlankState>

    <div v-else-if="detail && m" class="page detail">
      <div class="body">
        <p class="sub dim">{{ subLine }}</p>
        <el-tabs v-model="tab">
          <el-tab-pane :label="`SKU（${detail.skus.length}）`" name="skus">
            <el-table :data="detail.skus" row-key="id" empty-text="还没有 SKU，点「批量加 SKU」">
              <el-table-column label="SKU 码" min-width="170">
                <template #default="{ row }">
                  <span class="sku-code">
                    <img v-if="row.image" :src="row.image.thumbUrl ?? row.image.url" alt="" class="thumb" loading="lazy" />
                    <span class="code">{{ row.code }}</span>
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="颜色" min-width="84">
                <template #default="{ row }">
                  <span v-if="row.colorId" class="color">
                    <span class="swatch" :style="{ background: row.colorHex ?? undefined }" />
                    {{ row.colorName }}
                  </span>
                  <span v-else class="dim">—</span>
                </template>
              </el-table-column>
              <el-table-column label="规格" min-width="64">
                <template #default="{ row }">{{ row.spec ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="供应商色号" min-width="84" class-name="code">
                <template #default="{ row }">{{ row.supplierColorNo ?? '—' }}</template>
              </el-table-column>
              <el-table-column :label="`单价 / ${purchaseUnit}`" min-width="96" class-name="num" align="right">
                <template #default="{ row }">
                  <span v-if="row.price != null">{{ formatPrice(row.price) }}</span>
                  <span v-else class="dim">同参考价</span>
                </template>
              </el-table-column>
              <el-table-column :label="`最小库存（${stockUnit}）`" min-width="104" class-name="num" align="right">
                <template #default="{ row }">{{ row.safetyStock != null ? formatQty(row.safetyStock) : '—' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="84">
                <template #default="{ row }">
                  <StatusTag :tone="row.status === 1 ? 'active' : 'off'">{{ row.status === 1 ? '启用' : '停用' }}</StatusTag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="170" align="right">
                <template #default="{ row }">
                  <el-button v-perm="'basic:material:edit'" link @click="openSku(row as MaterialSku)">编辑</el-button>
                  <el-button v-perm="'basic:material:edit'" link @click="toggleSku(row as MaterialSku)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
                  <el-button v-perm="'basic:material:edit'" link type="danger" @click="removeSku(row as MaterialSku)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane v-if="user.hasPerm('price:price:list')" label="价格记录" name="prices" lazy>
            <PriceHistory :subject="{ kind: 'material', id, code: m.code, name: m.name }" />
          </el-tab-pane>
          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logList" :load="loadLogs" :actions="LOG_ACTIONS" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="aside">
        <section class="panel">
          <div class="panel-head">图片<span class="acts dim">第一张是主图</span></div>
          <div class="panel-body">
            <ImageUpload :model-value="detail.images" :disabled="!canEdit" :save="saveImages" />
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">资料</div>
          <dl class="props panel-body">
            <template v-for="p in infoRows" :key="p.label">
              <dt>{{ p.label }}</dt>
              <dd>{{ p.value }}</dd>
            </template>
          </dl>
        </section>
        <section v-if="legacy.length" class="panel">
          <div class="panel-head">领猫附加属性<span class="acts dim">导入时带过来的，只看</span></div>
          <dl class="props panel-body">
            <template v-for="[k, v] in legacy" :key="k">
              <dt>{{ k }}</dt>
              <dd>{{ v || '—' }}</dd>
            </template>
          </dl>
        </section>
      </aside>
    </div>

    <MaterialForm ref="formDialog" @saved="refresh" />

    <el-dialog v-model="addVisible" :title="`给 ${m?.code ?? ''} 批量加 SKU`" width="min(640px, 96vw)" :before-close="addGuard.beforeClose">
      <SkuPicker v-if="m" v-model:color-ids="addForm.colorIds" v-model:specs="addForm.specs" v-model:spec-codes="addForm.specCodes" :material-code="m.code" :existing="existingCodes" />
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="addSkus">加上</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="skuVisible" :title="`编辑 SKU ${skuEditing?.code ?? ''}`" width="min(520px, 94vw)" :before-close="skuGuard.beforeClose">
      <el-form class="form-grid" label-position="top" :model="skuForm">
        <el-form-item label="供应商色号">
          <el-input v-model="skuForm.supplierColorNo" maxlength="64" placeholder="如 C-1032" />
        </el-form-item>
        <el-form-item label="供应商货号">
          <el-input v-model="skuForm.supplierItemNo" maxlength="64" placeholder="不填 = 用物料的" />
        </el-form-item>
        <el-form-item :label="`单价（元 / ${purchaseUnit}）`">
          <el-input-number v-model="skuForm.price" :min="0" :controls="false" :value-on-clear="null" placeholder="不填 = 参考单价" />
        </el-form-item>
        <el-form-item :label="`最小库存（${stockUnit}）`">
          <el-input-number v-model="skuForm.safetyStock" :min="0" :controls="false" :value-on-clear="null" />
        </el-form-item>
        <el-form-item label="备注" class="full">
          <el-input v-model="skuForm.remark" maxlength="255" />
        </el-form-item>
        <el-form-item label="图片" class="full">
          <ImageUpload v-model="skuForm.images" :max="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="skuVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSku">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 详情页：左边主体（页签），右边一栏资料。窄屏上下排 */
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 272px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1280px) {
  .detail {
    grid-template-columns: minmax(0, 1fr);
  }
}

.sub {
  margin: 0 0 6px;
  font-size: 13px;
}

.aside {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.props {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  gap: 7px 12px;
  margin: 0;
  font-size: 13px;
}

.props dt {
  color: var(--lm-muted);
}

.props dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.sku-code,
.color {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.thumb {
  flex: none;
  width: 28px;
  height: 28px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.swatch {
  width: 14px;
  height: 14px;
  border: 1px solid var(--lm-line);
  border-radius: 3px;
}

.form-grid :deep(.el-input-number) {
  width: 100%;
}

.form-grid :deep(.el-input-number .el-input__inner) {
  text-align: left;
}
</style>
