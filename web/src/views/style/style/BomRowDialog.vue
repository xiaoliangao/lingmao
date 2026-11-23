<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { materialApi } from '@/api/basic'
import { techPackApi } from '@/api/techpack'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useDict } from '@/composables/useDict'
import { useEntityContext, useEntityRead } from '@/composables/useEntityContext'
import { ApiError } from '@/utils/request'
import { sessionEpoch } from '@/utils/token'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { MATERIAL_SOURCE_LABEL, type ColorOption, type MaterialPickRow, type MaterialSku, type MaterialSource } from '@/types/basic'
import type { StylePickRow } from '@/types/style'
import {
  BOM_SKU_MODE,
  BOM_USAGE_MODE,
  type BomDefault,
  type BomItem,
  type BomItemForm,
  type BomSkuForm,
  type BomSkuMode,
  type BomUsageMode,
} from '@/types/techpack'
import { formatPrice } from '@/utils/format'
import { fromScaledInteger, rescaleInteger, toScaledInteger } from '@/utils/fixedPoint'
import { orNull } from '@/views/basic/common'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()

/**
 * 改物料清单的一行（docs/api/01 §4.4，原型 BomRowForm）：物料、配料方式和配料、用量方式和用量、损耗、来源、供应商、单价、含税含运、备注。
 * 加行走资料包页签的"从物料库选"（一次勾多条），这里只改一行；一行都没有时也能从这里加。
 *
 *   <BomRowDialog v-model="editing" :pack-id :sizes :base-size :colors :editing-row="row" @save="replaceRow" />
 *
 * 默认值（01 §3.9）：换物料时整套覆盖（单位、供应商、损耗、来源、单价、含税含运、各 SKU 的单价）；
 * 换供应商、换配料的 SKU 只覆盖单价那几项（单价、价格来源、含税含运、各 SKU 的单价），用户改过的用量、损耗、来源、备注不动。
 * 配料的 SKU 下拉是这个物料的 SKU（materialApi.get），只列启用的；已经配了的停用 SKU 照样显示、标红
 */
export interface BomDraftRow extends BomItemForm {
  materialCode: string
  materialName: string
  materialType: string
  unit: string
  supplierName: string | null
  /** 配料 SKU id → 显示用的码、颜色、规格、状态（页签上的表格要显示，不用再查） */
  skuInfo: Record<string, { code: string; colorName: string | null; spec: string | null; status: number }>
}

const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  packId: string
  companyId?: string
  sizes: string[]
  baseSize: string
  colors: ColorOption[]
  /** 改哪一行；null = 加一行 */
  editingRow: BomItem | null
}>()

const emit = defineEmits<{
  save: [row: BomDraftRow]
}>()

const units = useDict('unit')

const form = reactive({
  part: '',
  materialId: '',
  materialCode: '',
  materialName: '',
  materialType: '',
  unit: '',
  skuMode: 'FIXED' as BomSkuMode,
  usageMode: 'UNIFORM' as BomUsageMode,
  usageQty: null as number | null,
  sizeUsages: {} as Record<string, number>,
  lossPct: 0,
  source: 'PURCHASE' as MaterialSource,
  supplierId: null as string | null,
  supplierName: null as string | null,
  refPrice: null as number | null | undefined,
  priceSourceId: null as string | null,
  priceTaxIncluded: true as boolean | undefined,
  priceFreightIncluded: true as boolean | undefined,
  remark: '',
})

/** 配料：格子（'FIXED' | 'C:款色' | 'S:尺码' | 'CS:款色:尺码'）→ 选的物料 SKU 和它单独的单价（null = 用行上的） */
const skuMap = reactive<Record<string, { materialSkuId: string; refPrice?: number | null }>>({})
const guard = useUnsavedChanges(() => ({ form, skuMap }), visible, { scope: () => `${props.companyId ?? 'shared'}:${props.packId}:${props.editingRow?.id ?? 'new'}` })
const materialRead = useEntityRead(), defaultRead = useEntityRead()
const materialLoading = ref(false), defaultsLoading = ref(false), materialError = ref<string | null>(null), defaultsError = ref<string | null>(null)
let retryDefaultsFull = false
const context = useEntityContext(() => { materialRead.cancel(); defaultRead.cancel(); materialSkus.value = []; lastDefault.value = null; picking.value = false; visible.value = false })
const options = () => ({ companyId: props.companyId, authEpoch: sessionEpoch(), silent: true })
function readError(cause: unknown) { return cause instanceof ApiError && [403, 404].includes(cause.code) ? '权限已变化或资料不可见，请重新读取' : cause instanceof ApiError ? cause.message : '读取失败，请重试' }

/** 这个物料的 SKU（配料下拉） */
const materialSkus = ref<MaterialSku[]>([])
/** 上次取到的默认值：单价旁边提示"合 xx 元/采购单位"用 */
const lastDefault = ref<BomDefault | null>(null)
const picking = ref(false)

/** 客供只在 TRADE 开时有，一期按关（01 §4.4） */
const SOURCE_OPTIONS = (Object.keys(MATERIAL_SOURCE_LABEL) as MaterialSource[]).filter((s) => s !== 'CUSTOMER_SUPPLIED')

function keyOf(colorId: string | null, sizeCode: string | null): string {
  if (colorId != null && sizeCode != null) return `CS:${colorId}:${sizeCode}`
  if (colorId != null) return `C:${colorId}`
  if (sizeCode != null) return `S:${sizeCode}`
  return 'FIXED'
}

/** 配料方式要填的格子 */
const slots = computed(() => {
  if (form.skuMode === 'FIXED') return [{ key: 'FIXED', label: '全部颜色、尺码', colorId: null, sizeCode: null }]
  if (form.skuMode === 'BY_COLOR') {
    return props.colors.map((c) => ({ key: keyOf(c.id, null), label: c.name, colorId: c.id as string | null, sizeCode: null as string | null }))
  }
  if (form.skuMode === 'BY_SIZE') {
    return props.sizes.map((sz) => ({ key: keyOf(null, sz), label: sz, colorId: null as string | null, sizeCode: sz as string | null }))
  }
  return props.colors.flatMap((c) =>
    props.sizes.map((sz) => ({ key: keyOf(c.id, sz), label: `${c.name} · ${sz}`, colorId: c.id as string | null, sizeCode: sz as string | null })),
  )
})

const skuLabel = (s: MaterialSku) => [s.code, s.colorName, s.spec].filter(Boolean).join(' ')
/** 下拉的选项：启用的 SKU，加上已经配了的停用 SKU（照样显示、标红） */
const skuOptions = computed(() => {
  const used = new Set(Object.values(skuMap).map((v) => v.materialSkuId))
  return materialSkus.value.filter((s) => s.status === 1 || used.has(s.id))
})
const skuOf = (id: string | undefined) => materialSkus.value.find((s) => s.id === id)

async function loadMaterialSkus(materialId: string): Promise<boolean> {
  const read = materialRead.begin()
  materialError.value = null; materialLoading.value = false; materialSkus.value = []
  if (!materialId) return false
  materialLoading.value = true
  try {
    const result = await read.run(() => materialApi.get(materialId))
    if (!read.current() || !visible.value || form.materialId !== materialId) return false
    materialSkus.value = result.skus
    return true
  } catch (cause) { if (read.current()) materialError.value = readError(cause); return false }
  finally { if (read.current()) materialLoading.value = false }
}

function resetFromProps() {
  Object.keys(skuMap).forEach((k) => delete skuMap[k])
  lastDefault.value = null
  const r = props.editingRow
  if (r) {
    Object.assign(form, {
      part: r.part,
      materialId: r.materialId,
      materialCode: r.materialCode,
      materialName: r.materialName,
      materialType: r.materialType,
      unit: r.unit,
      skuMode: r.skuMode,
      usageMode: r.usageMode,
      usageQty: r.usageQty,
      sizeUsages: { ...r.sizeUsages },
      lossPct: r.lossPct,
      source: r.source,
      supplierId: r.supplierId,
      supplierName: r.supplierName,
      refPrice: r.refPrice,
      priceSourceId: r.priceSourceId,
      priceTaxIncluded: r.priceTaxIncluded,
      priceFreightIncluded: r.priceFreightIncluded,
      remark: r.remark ?? '',
    })
    for (const sk of r.skus) skuMap[keyOf(sk.colorId, sk.sizeCode)] = { materialSkuId: sk.materialSkuId, refPrice: sk.refPrice }
    void loadMaterialSkus(r.materialId)
  } else {
    Object.assign(form, {
      part: '',
      materialId: '',
      materialCode: '',
      materialName: '',
      materialType: '',
      unit: '',
      skuMode: 'FIXED',
      usageMode: 'UNIFORM',
      usageQty: null,
      sizeUsages: {},
      lossPct: 0,
      source: 'PURCHASE',
      supplierId: null,
      supplierName: null,
      refPrice: null,
      priceSourceId: null,
      priceTaxIncluded: true,
      priceFreightIncluded: true,
      remark: '',
    })
    materialSkus.value = []
  }
  guard.markSaved()
}

watch(() => [visible.value, props.packId, props.companyId, props.editingRow?.id], () => {
  materialRead.cancel(); defaultRead.cancel(); materialError.value = null; defaultsError.value = null; materialLoading.value = false; defaultsLoading.value = false
  if (visible.value) resetFromProps()
}, { immediate: true })

/** 问后端这一行的默认值。full：换物料，整套覆盖；否则（换供应商、换 SKU）只覆盖单价那几项 */
async function applyDefaults(full: boolean) {
  const read = defaultRead.begin(), materialId = form.materialId
  defaultsError.value = null; defaultsLoading.value = false; retryDefaultsFull = full
  if (!materialId) return
  const supplierId = form.supplierId
  const skuIds = [...new Set(Object.values(skuMap).map((v) => v.materialSkuId))]
  defaultsLoading.value = true
  try {
    const [def] = await techPackApi.bomDefaults(props.packId, [{ materialId, supplierId, skuIds }], { ...options(), signal: read.signal })
    if (!read.current() || !visible.value || form.materialId !== materialId || form.supplierId !== supplierId) return
    if (!def) throw new ApiError(500, '未取得这一行的默认值')
    lastDefault.value = def
    if (full) {
      form.unit = def.unit
      form.lossPct = def.lossPct
      form.source = def.source
      if (!form.supplierId) form.supplierId = def.supplierId
    }
    if (form.supplierId === def.supplierId) form.supplierName = def.supplierName
    form.refPrice = def.refPrice
    form.priceSourceId = def.priceSourceId
    form.priceTaxIncluded = def.priceTaxIncluded
    form.priceFreightIncluded = def.priceFreightIncluded
    const prices = new Map((def.skuPrices ?? []).map((p) => [p.materialSkuId, p.refPrice]))
    for (const entry of Object.values(skuMap)) entry.refPrice = prices.get(entry.materialSkuId) ?? null
  } catch (cause) { if (read.current()) defaultsError.value = readError(cause) }
  finally { if (read.current()) defaultsLoading.value = false }
}

/** 换了配色方式：随款色的按颜色自动配上（物料 SKU 的颜色和款色是同一张颜色表），配不上的空着 */
function autoMatch() {
  if (form.skuMode !== 'BY_COLOR' && form.skuMode !== 'BY_COLOR_SIZE') return
  for (const slot of slots.value) {
    if (skuMap[slot.key] || slot.colorId == null) continue
    const hit = materialSkus.value.find((s) => s.status === 1 && s.colorId === slot.colorId)
    if (hit) skuMap[slot.key] = { materialSkuId: hit.id, refPrice: null }
  }
}

watch(
  () => form.skuMode,
  () => {
    if (!visible.value) return
    // 换了方式，不属于新方式的格子去掉
    const keep = new Set(slots.value.map((s) => s.key))
    Object.keys(skuMap).forEach((k) => {
      if (!keep.has(k)) delete skuMap[k]
    })
    autoMatch()
  },
)

/** 从物料库换一种物料：配料清空，按选的 SKU 先配上，再整套取默认值 */
async function onPickMaterial(rows: MaterialPickRow[] | StylePickRow[]) {
  const picked = (rows as MaterialPickRow[])[0], current = context.capture()
  if (!picked || !visible.value) return
  defaultRead.cancel()
  Object.keys(skuMap).forEach((k) => delete skuMap[k])
  Object.assign(form, {
    materialId: picked.materialId,
    materialCode: picked.materialCode,
    materialName: picked.materialName,
    materialType: picked.materialType,
    unit: picked.stockUnit,
    supplierId: null,
    supplierName: null,
  })
  if (!await loadMaterialSkus(picked.materialId) || !current() || form.materialId !== picked.materialId) return
  if (form.skuMode === 'FIXED') skuMap.FIXED = { materialSkuId: picked.skuId, refPrice: null }
  else autoMatch()
  await applyDefaults(true)
}

async function onSkuChange(key: string, skuId: string | null) {
  if (skuId == null) delete skuMap[key]
  else skuMap[key] = { materialSkuId: skuId, refPrice: null }
  await applyDefaults(false)
}

async function onSupplierChange(id: string | null | undefined) {
  form.supplierId = id ?? null
  if (!form.supplierId) form.supplierName = null
  await applyDefaults(false)
}

/** 单价按库存单位，旁边提示换回采购单位的价（01 §4.4） */
const purchaseHint = computed(() => {
  const def = lastDefault.value
  if (!def || form.refPrice == null || def.purchaseUnit === def.unit) return ''
  const product = toScaledInteger(form.refPrice, 4) * toScaledInteger(def.conversionFactor, 6)
  return `合 ${formatPrice(fromScaledInteger(rescaleInteger(product, 10, 4), 4))} 元/${units.labelOf(def.purchaseUnit)}`
})

function submit() {
  if (materialLoading.value || defaultsLoading.value || materialError.value || defaultsError.value) return
  if (!form.materialId) {
    ElMessage.warning('先选物料')
    return
  }
  if (!form.part) {
    ElMessage.warning('选一个部位')
    return
  }
  const skus: BomSkuForm[] = []
  const skuInfo: BomDraftRow['skuInfo'] = {}
  for (const slot of slots.value) {
    const hit = skuMap[slot.key]
    if (!hit) continue
    skus.push({ colorId: slot.colorId, sizeCode: slot.sizeCode, materialSkuId: hit.materialSkuId, refPrice: hit.refPrice })
    const sku = skuOf(hit.materialSkuId)
    if (sku) skuInfo[sku.id] = { code: sku.code, colorName: sku.colorName, spec: sku.spec, status: sku.status }
  }
  if (!skus.length) {
    ElMessage.warning('至少配一个 SKU')
    return
  }

  emit('save', {
    part: form.part,
    materialId: form.materialId,
    materialCode: form.materialCode,
    materialName: form.materialName,
    materialType: form.materialType,
    unit: form.unit,
    skuMode: form.skuMode,
    usageMode: form.usageMode,
    usageQty: form.usageMode === 'UNIFORM' ? form.usageQty : null,
    sizeUsages: form.usageMode === 'BY_SIZE' ? { ...form.sizeUsages } : {},
    lossPct: form.lossPct,
    source: form.source,
    supplierId: form.supplierId,
    supplierName: form.supplierName,
    refPrice: form.refPrice,
    priceSourceId: form.priceSourceId,
    priceTaxIncluded: form.priceTaxIncluded,
    priceFreightIncluded: form.priceFreightIncluded,
    remark: orNull(form.remark),
    skus,
    skuInfo,
  })
  guard.markSaved()
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editingRow ? `改物料行 · ${editingRow.materialName}` : '加一行物料'"
    width="720px"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
  >
    <p v-if="materialLoading || defaultsLoading" role="status" class="dim">正在读取配料资料…</p>
    <el-alert v-if="materialError" :title="materialError" type="error" :closable="false"><el-button @click="loadMaterialSkus(form.materialId)">重试 SKU</el-button></el-alert>
    <el-alert v-if="defaultsError" :title="defaultsError" type="error" :closable="false"><el-button @click="applyDefaults(retryDefaultsFull)">重试默认值</el-button></el-alert>
    <el-form label-position="top" class="form-grid">
      <el-form-item label="部位">
        <DictSelect v-model="form.part" type="bom_part" />
      </el-form-item>
      <el-form-item label="物料">
        <div class="mat-box">
          <span v-if="form.materialId"><span class="code">{{ form.materialCode }}</span> {{ form.materialName }}</span>
          <span v-else class="dim">还没选</span>
          <el-button size="small" @click="picking = true">{{ form.materialId ? '换物料' : '从物料库选' }}</el-button>
        </div>
      </el-form-item>

      <el-form-item label="配料方式">
        <el-select v-model="form.skuMode" filterable>
          <el-option v-for="(lbl, k) in BOM_SKU_MODE" :key="k" :label="lbl" :value="k" />
        </el-select>
      </el-form-item>
      <el-form-item label="用量方式">
        <el-segmented v-model="form.usageMode" :options="Object.entries(BOM_USAGE_MODE).map(([value, label]) => ({ label, value }))" />
      </el-form-item>

      <el-form-item label="配料" class="full">
        <div v-if="!form.materialId" class="dim">先选物料</div>
        <div v-else class="slot-list">
          <div v-for="slot in slots" :key="slot.key" class="slot-row">
            <span class="slot-lbl">{{ slot.label }}</span>
            <el-select
              :model-value="skuMap[slot.key]?.materialSkuId"
              size="small"
              filterable
              clearable
              placeholder="选 SKU"
              class="slot-sel"
              :class="{ stopped: skuOf(skuMap[slot.key]?.materialSkuId)?.status === 0 }"
              @update:model-value="(v: string | null) => onSkuChange(slot.key, v ?? null)"
            >
              <el-option v-for="s in skuOptions" :key="s.id" :label="skuLabel(s)" :value="s.id" :disabled="s.status !== 1">
                <span :class="{ danger: s.status !== 1 }">{{ skuLabel(s) }}<template v-if="s.status !== 1">（已停用）</template></span>
              </el-option>
            </el-select>
            <span v-if="skuOf(skuMap[slot.key]?.materialSkuId)?.status === 0" class="danger small">已停用，生效时会被拦</span>
            <span v-if="canSeeCost && skuMap[slot.key]?.refPrice != null" class="dim num small">单价 {{ formatPrice(skuMap[slot.key]?.refPrice) }}</span>
          </div>
        </div>
      </el-form-item>

      <el-form-item v-if="form.usageMode === 'UNIFORM'" :label="`用量（${units.labelOf(form.unit) || '库存单位'} / 件）`">
        <el-input-number v-model="form.usageQty" :min="0" :precision="4" :controls="false" />
      </el-form-item>
      <el-form-item v-else label="各码用量" class="full">
        <div class="size-grid">
          <div v-for="sz in sizes" :key="sz" class="size-cell" :class="{ base: sz === baseSize }">
            <span class="sz">{{ sz }}<small v-if="sz === baseSize">基码</small></span>
            <el-input-number v-model="form.sizeUsages[sz]" size="small" :min="0" :precision="4" :controls="false" />
          </div>
        </div>
      </el-form-item>
      <el-form-item label="损耗 %">
        <el-input-number v-model="form.lossPct" :min="0" :max="100" :precision="2" :controls="false" />
      </el-form-item>

      <el-form-item label="物料来源">
        <el-select v-model="form.source" filterable>
          <el-option v-for="s in SOURCE_OPTIONS" :key="s" :label="MATERIAL_SOURCE_LABEL[s]" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="供应商">
        <SupplierSelect :model-value="form.supplierId" :label="form.supplierName" clearable @update:model-value="onSupplierChange" />
      </el-form-item>

      <el-form-item v-if="canSeeCost" :label="`参考单价（元 / ${units.labelOf(form.unit) || '库存单位'}）`">
        <el-input-number v-model="form.refPrice" :min="0" :precision="4" :controls="false" />
        <span v-if="purchaseHint" class="dim small">{{ purchaseHint }}</span>
      </el-form-item>
      <el-form-item v-if="canSeeCost" label="单价口径">
        <div class="checks">
          <el-checkbox v-model="form.priceTaxIncluded">含税</el-checkbox>
          <el-checkbox v-model="form.priceFreightIncluded">含运费</el-checkbox>
        </div>
      </el-form-item>

      <el-form-item label="备注" class="full">
        <el-input v-model="form.remark" maxlength="120" placeholder="选填" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="materialLoading || defaultsLoading || !!materialError || !!defaultsError" @click="submit">确定</el-button>
    </template>
  </el-dialog>

  <DataPick v-model="picking" kind="sku" single @pick="onPickMaterial" />
</template>

<style scoped>
.form-grid :deep(.el-select),
.form-grid :deep(.el-input-number) {
  width: 100%;
}

.mat-box {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  font-size: 13px;
}

.size-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
  width: 100%;
}

.size-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px 8px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.size-cell.base {
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.sz {
  font-size: 12px;
  font-weight: 600;
}

.sz small {
  margin-left: 3px;
  color: var(--lm-accent-2);
}

.slot-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-height: 240px;
  padding: 8px;
  overflow-y: auto;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.slot-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}

.slot-lbl {
  flex: none;
  width: 110px;
  color: var(--lm-ink-2);
}

.slot-row .slot-sel {
  width: 260px;
}

.slot-sel.stopped :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-danger-text) inset;
}

.danger {
  color: var(--lm-danger-text);
}

.small {
  font-size: 12px;
}

.checks {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 32px;
}
</style>
