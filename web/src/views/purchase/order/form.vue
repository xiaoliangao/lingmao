<script setup lang="ts">
import { routeId } from '@/utils/entityId'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { companyApi, warehouseApi } from '@/api/basic'
import { purchaseApi } from '@/api/purchase'
import BlankState from '@/components/BlankState.vue'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import PageHeader from '@/components/PageHeader.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import { showSilentError, todayStr } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import type { CompanyOption, MaterialPickRow, WarehouseOption } from '@/types/basic'
import { PURCHASE_TYPE_LABEL, type PurchaseForm, type PurchaseItemForm, type PurchaseType } from '@/types/purchase'
import type { StylePickRow } from '@/types/style'
import { formatMoney, formatPrice } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'

/**
 * 采购单新建、改草稿（/purchases/new、/purchases/:id/edit，docs/api/03 §3.3.3-3.3.4、§4）。
 * - 默认值都从 GET /defaults 来：选了 / 换了供应商、加了物料、改了下单日期各取一次。
 *   单价：没手改过的行用返回的单价；手改过的只更新"档案 x"。交货日期没手改过的用返回的。
 *   换供应商（已经有明细时）先确认"单价按新供应商的现价重新带出，手改过的不动"
 * - 单价和档案现价不一样：琥珀色，改价原因必填；提交前结算方式、税率、改价原因空的标红
 * - 新建后提交失败：草稿已经建好了，转到它的编辑页接着改，不会再点一次又建一张
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()
const units = useDict('unit')

const path = route.path
const id = route.params.id ? routeId(route.params.id) : null
const editing = id !== null

interface LineDraft {
  calcItemId: string | null
  materialSkuId: string
  skuCode: string
  materialName: string
  colorSpec: string
  purchaseUnit: string
  qty: number | null
  /** null = 用默认单价（后端按档案现价 → SKU 单价 → 物料参考单价取） */
  price: number | null
  archivePrice: number | null
  priceChangeReason: string
  lineDueDate: string | null
  expectDate: string | null
  /** 单价手改过：换供应商、改下单日期时不覆盖 */
  priceTouched: boolean
  /** 从算料单带来的订单号、款号（只看） */
  sourceText: string
}

const companies = ref<CompanyOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const loaded = ref(!editing)
const notFound = ref(false)
const saving = ref(false)
const version = ref(0)
const poNo = ref('')
/** 点过提交：空着的必填项标红 */
const submitTried = ref(false)
/** 交货日期手改过：不再用默认值覆盖 */
const deliveryTouched = ref(false)

const head = reactive({
  companyId: null as string | null,
  supplierId: null as string | null,
  supplierName: null as string | null,
  purchaseType: 'ORDER' as PurchaseType,
  buyerId: user.info?.id ?? null,
  buyerName: user.info?.nickname ?? null,
  orderDate: todayStr(),
  deliveryDate: null as string | null,
  warehouseId: null as string | null,
  settleMethod: null as string | null,
  taxPct: null as number | null,
  taxIncluded: true,
  freightIncluded: true,
  remark: '',
  planStyles: [] as { id: string; code: string; name: string }[],
})

const lines = ref<LineDraft[]>([])
const skuPicking = ref(false)
const stylePicking = ref(false)
const guard = useUnsavedChanges(() => ({ head, lines: lines.value }))

const TYPE_OPTIONS = Object.entries(PURCHASE_TYPE_LABEL).map(([value, label]) => ({ value: value as PurchaseType, label }))
/** 交货地点：启用的物料仓和工厂仓（options 只回启用的），工厂仓标"直发工厂" */
const warehouseOptions = computed(() => warehouses.value.filter((w) => w.type === 'MATERIAL' || w.type === 'FACTORY'))
const haveSkuIds = computed(() => lines.value.map((l) => l.materialSkuId))
const haveStyleIds = computed(() => head.planStyles.map((s) => s.id))
const totalAmount = computed(() => lines.value.reduce((sum, l) => sum + (l.qty ?? 0) * (l.price ?? 0), 0))

function priceChanged(l: LineDraft): boolean {
  if (l.archivePrice === null || l.price === null) return false
  return Math.abs(l.price - l.archivePrice) > 0.00005
}

// ---------- 默认值 ----------
let defaultsSeq = 0

/** 取默认值，按 §3.3.3 填：fillHead = 换了供应商（结算方式、税率、采购员跟着换） */
async function applyDefaults(fillHead: boolean) {
  if (!head.supplierId) return
  const seq = ++defaultsSeq
  const def = await purchaseApi.defaults({
    supplierId: head.supplierId,
    orderDate: head.orderDate,
    skuIds: lines.value.length ? lines.value.map((l) => l.materialSkuId) : undefined,
  })
  // 连着换了两次供应商：只认最后一次的
  if (seq !== defaultsSeq) return
  if (fillHead) {
    head.settleMethod = def.settleMethod
    head.taxPct = def.taxPct
    if (def.buyerId) {
      head.buyerId = def.buyerId
      head.buyerName = def.buyerName
    }
  }
  if (def.deliveryDate && !deliveryTouched.value) head.deliveryDate = def.deliveryDate
  const bySku = new Map(def.lines.map((d) => [d.materialSkuId, d]))
  for (const line of lines.value) {
    const d = bySku.get(line.materialSkuId)
    if (!d) continue
    line.archivePrice = d.archivePrice
    line.purchaseUnit = d.purchaseUnit
    if (!line.priceTouched) line.price = d.price
  }
}

async function onSupplierChange(supplierId: string | null | undefined) {
  const next = supplierId ?? null
  const prev = head.supplierId
  if (next === prev) return
  if (prev !== null && next !== null && lines.value.length) {
    try {
      await ElMessageBox.confirm('单价按新供应商的现价重新带出，手改过的不动。', '换供应商', {
        confirmButtonText: '换',
        cancelButtonText: '不换',
      })
    } catch {
      // 不换：下拉回到原来的供应商（v-model 没绑，值本来就没动）
      return
    }
  }
  head.supplierId = next
  if (next !== null) await applyDefaults(true)
}

function onOrderDateChange() {
  // 现价按下单日期取（PRD §6.10 规则 3）
  void applyDefaults(false)
}

function onTypeChange(type: PurchaseType) {
  // 计划用于款号只有备货采购收
  if (type !== 'STOCK') head.planStyles = []
}

async function onPickedSkus(rows: MaterialPickRow[] | StylePickRow[]) {
  for (const r of rows as MaterialPickRow[]) {
    if (lines.value.some((l) => l.materialSkuId === r.skuId)) continue
    lines.value.push({
      calcItemId: null,
      materialSkuId: r.skuId,
      skuCode: r.skuCode,
      materialName: r.materialName,
      colorSpec: [r.colorName, r.spec].filter(Boolean).join(' '),
      purchaseUnit: r.purchaseUnit,
      // 数量不替人填：采购员自己按需要填
      qty: null,
      // 单价等默认值回来再填；没选供应商时先用物料上的单价
      price: head.supplierId ? null : r.price,
      archivePrice: null,
      priceChangeReason: '',
      lineDueDate: null,
      expectDate: null,
      priceTouched: false,
      sourceText: '',
    })
  }
  await applyDefaults(false)
}

function onPickedPlanStyles(rows: MaterialPickRow[] | StylePickRow[]) {
  for (const s of rows as StylePickRow[]) {
    if (!head.planStyles.some((x) => x.id === s.id)) head.planStyles.push({ id: s.id, code: s.code, name: s.name })
  }
}

// ---------- 加载 ----------
async function init() {
  const [comps, whs] = await Promise.all([companyApi.options(), warehouseApi.options()])
  companies.value = comps
  warehouses.value = whs
  if (id === null) {
    // 主体必填：默认我的默认主体，没有（不止一个启用的主体、又没设）就空着让人选
    head.companyId = user.defaultCompanyId
    head.warehouseId = warehouseOptions.value.find((w) => w.type === 'MATERIAL')?.id ?? null
    guard.markSaved()
    return
  }
  try {
    const d = await purchaseApi.get(id)
    version.value = d.version
    poNo.value = d.no
    Object.assign(head, {
      companyId: d.companyId,
      supplierId: d.supplierId,
      supplierName: d.supplierShortName,
      purchaseType: d.purchaseType,
      buyerId: d.buyerId,
      buyerName: d.buyerName,
      orderDate: d.orderDate,
      deliveryDate: d.deliveryDate,
      warehouseId: d.warehouseId,
      settleMethod: d.settleMethod,
      taxPct: d.taxPct,
      taxIncluded: d.taxIncluded,
      freightIncluded: d.freightIncluded,
      remark: d.remark ?? '',
      planStyles: d.planStyles.map((ps) => ({ id: ps.styleId, code: ps.styleCode, name: ps.styleName })),
    })
    // 存过的草稿：交货日期是定下来的，不再被默认值改
    deliveryTouched.value = true
    lines.value = d.items.map((it) => ({
      calcItemId: it.calcItemId,
      materialSkuId: it.materialSkuId,
      skuCode: it.skuCode,
      materialName: it.materialName,
      colorSpec: [it.colorName, it.spec].filter(Boolean).join(' '),
      purchaseUnit: it.purchaseUnit,
      qty: it.qty,
      price: it.price,
      archivePrice: it.archivePrice,
      priceChangeReason: it.priceChangeReason ?? '',
      lineDueDate: it.lineDueDate,
      expectDate: it.expectDate,
      priceTouched: it.archivePrice !== null && Math.abs(it.price - it.archivePrice) > 0.00005,
      sourceText: [...it.orderNos, ...it.styleCodes].join(' · '),
    }))
    tabs.rename(path, `编辑 ${d.no}`)
    loaded.value = true
    guard.markSaved()
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

onMounted(init)

// ---------- 保存 ----------
const missingSettle = computed(() => submitTried.value && !head.settleMethod)
const missingTax = computed(() => submitTried.value && head.taxPct === null)
const needReason = (l: LineDraft) => priceChanged(l) && !l.priceChangeReason.trim()

function buildForm(andSubmit: boolean): PurchaseForm | null {
  submitTried.value = andSubmit
  if (!head.supplierId) return warn('先选供应商')
  if (!head.companyId) return warn('先选主体')
  if (!head.buyerId) return warn('先选采购员')
  if (!head.warehouseId) return warn('先选交货地点')
  if (!lines.value.length) return warn('至少加一行物料：点「从物料库选」')
  const noQty = lines.value.findIndex((l) => !l.qty || l.qty <= 0)
  if (noQty >= 0) return warn(`第 ${noQty + 1} 行没填数量`)
  const noReason = lines.value.findIndex(needReason)
  if (noReason >= 0) return warn(`第 ${noReason + 1} 行改了单价，要写原因`)
  if (andSubmit && (!head.settleMethod || head.taxPct === null)) return warn('提交前要填结算方式和税率')
  const items: PurchaseItemForm[] = lines.value.map((l) => ({
    calcItemId: l.calcItemId,
    materialSkuId: l.materialSkuId,
    qty: l.qty,
    price: l.price,
    priceChangeReason: priceChanged(l) ? l.priceChangeReason.trim() : null,
    lineDueDate: l.lineDueDate,
    expectDate: l.expectDate,
  }))
  return {
    companyId: head.companyId,
    supplierId: head.supplierId,
    purchaseType: head.purchaseType,
    buyerId: head.buyerId,
    orderDate: head.orderDate,
    deliveryDate: head.deliveryDate,
    warehouseId: head.warehouseId,
    settleMethod: head.settleMethod,
    taxPct: head.taxPct,
    taxIncluded: head.taxIncluded,
    freightIncluded: head.freightIncluded,
    remark: orNull(head.remark),
    planStyleIds: head.purchaseType === 'STOCK' ? head.planStyles.map((s) => s.id) : [],
    items,
  }
}

function warn(msg: string): null {
  ElMessage.warning(msg)
  return null
}

/** 提交失败（缺单价、供应商停用……）：原因弹出来，返回 false，不往下走 */
async function submit(poId: string, ver: number): Promise<boolean> {
  try {
    await purchaseApi.act(poId, 'submit', { version: ver }, { silent: true })
    return true
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    return false
  }
}

async function save(andSubmit: boolean) {
  if (!(await guard.waitForUploads())) return
  const form = buildForm(andSubmit)
  if (!form) return
  saving.value = true
  try {
    if (id !== null) {
      version.value = await purchaseApi.update(id, { ...form, version: version.value })
      guard.markSaved()
      if (andSubmit && !(await submit(id, version.value))) return
      ElMessage.success(andSubmit ? `${poNo.value} 提交审核了` : `${poNo.value} 已保存`)
      await leave(`/purchases/${id}`)
      return
    }
    const saved = await purchaseApi.create(form)
    guard.markSaved()
    if (andSubmit && !(await submit(saved.id, saved.version))) {
      // 草稿已经建好了：去它的编辑页接着改，新建页签关掉，免得再点一次又建一张
      await leave(`/purchases/${saved.id}/edit`)
      return
    }
    ElMessage.success(andSubmit ? `${saved.no} 建好了，已提交审核` : `${saved.no} 草稿存好了`)
    await leave(`/purchases/${saved.id}`)
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

function removeLine(index: number) {
  lines.value.splice(index, 1)
}

const backTo = editing ? `/purchases/${id}` : '/purchases'
</script>

<template>
  <div>
    <PageHeader :title="editing ? `编辑 ${poNo}` : '新建采购单'" :back="{ label: editing ? poNo || '采购单' : '采购单', to: backTo }">
      <template #actions>
        <el-button @click="leave(backTo)">取消</el-button>
        <el-button :loading="saving" :disabled="!loaded" @click="save(false)">{{ editing ? '保存' : '存草稿' }}</el-button>
        <el-button v-perm="'purchase:order:submit'" type="primary" :loading="saving" :disabled="!loaded" @click="save(true)">
          {{ editing ? '保存并提交' : '提交审核' }}
        </el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张采购单不存在，可能已经删了">
      <el-button @click="leave('/purchases')">回采购单列表</el-button>
    </BlankState>

    <div v-else v-loading="!loaded" class="page">
      <el-form label-position="top" class="head-grid">
        <el-form-item label="供应商" required>
          <SupplierSelect :model-value="head.supplierId" :label="head.supplierName" usage="BULK" @update:model-value="onSupplierChange" />
        </el-form-item>
        <el-form-item label="采购类型" required>
          <el-select v-model="head.purchaseType" filterable @change="onTypeChange">
            <el-option v-for="o in TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体" required>
          <el-select v-model="head.companyId" filterable placeholder="选主体">
            <el-option v-for="c in companies" :key="c.id" :label="c.shortName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="采购员" required>
          <UserSelect v-model="head.buyerId" v-model:label="head.buyerName" />
        </el-form-item>
        <el-form-item label="下单日期" required>
          <el-date-picker v-model="head.orderDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="onOrderDateChange" />
        </el-form-item>
        <el-form-item label="交货日期">
          <el-date-picker
            v-model="head.deliveryDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="按物料采购周期推"
            @change="deliveryTouched = !!head.deliveryDate"
          />
        </el-form-item>
        <el-form-item label="交货地点" required>
          <el-select v-model="head.warehouseId" filterable placeholder="选仓库">
            <el-option v-for="w in warehouseOptions" :key="w.id" :label="w.name" :value="w.id">
              {{ w.name }}<span v-if="w.type === 'FACTORY'" class="direct">直发工厂</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="结算方式" :error="missingSettle ? '提交前要填' : ''">
          <DictSelect v-model="head.settleMethod" type="settle_method" clearable />
        </el-form-item>
        <el-form-item label="税率 %" :error="missingTax ? '提交前要填' : ''">
          <el-input-number v-model="head.taxPct" :min="0" :max="100" :precision="2" :controls="false" :value-on-clear="null" />
        </el-form-item>
        <el-form-item label="含税、含运费">
          <div class="checks">
            <el-checkbox v-model="head.taxIncluded">含税</el-checkbox>
            <el-checkbox v-model="head.freightIncluded">含运费</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item label="备注" class="span-2">
          <el-input v-model="head.remark" maxlength="200" />
        </el-form-item>
        <el-form-item v-if="head.purchaseType === 'STOCK'" label="计划用于款号（只是备注，不预留）" class="full">
          <div class="plan-styles">
            <el-tag v-for="(ps, i) in head.planStyles" :key="ps.id" closable @close="head.planStyles.splice(i, 1)">
              <span class="code">{{ ps.code }}</span> {{ ps.name }}
            </el-tag>
            <el-button size="small" @click="stylePicking = true">选款式</el-button>
          </div>
        </el-form-item>
      </el-form>

      <div class="sec-bar">
        <h3>明细</h3>
        <div class="right">
          <span class="num">合计 <strong>{{ formatMoney(totalAmount) }}</strong></span>
          <el-button icon="Plus" @click="skuPicking = true">从物料库选</el-button>
        </div>
      </div>

      <el-table :data="lines" row-key="materialSkuId" empty-text="还没有物料：点「从物料库选」">
        <el-table-column label="#" width="44" class-name="num">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="物料" min-width="200">
          <template #default="{ row }">
            <span class="code">{{ row.skuCode }}</span>
            <div class="dim small">{{ row.materialName }} {{ row.colorSpec }}</div>
            <div v-if="row.sourceText" class="dim small code">{{ row.sourceText }}</div>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="140" class-name="num" align="right">
          <template #default="{ row }">
            <el-input-number v-model="row.qty" size="small" :min="0" :precision="3" :controls="false" :value-on-clear="null" class="qty" />
            <span class="dim small"> {{ units.labelOf(row.purchaseUnit) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="单价" width="130" class-name="num" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.price"
              size="small"
              :min="0"
              :precision="4"
              :controls="false"
              :value-on-clear="null"
              placeholder="默认"
              :class="{ amber: priceChanged(row as LineDraft) }"
              @change="row.priceTouched = true"
            />
            <div v-if="row.archivePrice !== null" class="dim small">档案 {{ formatPrice(row.archivePrice) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="改价原因" min-width="150">
          <template #default="{ row }">
            <el-input
              v-if="priceChanged(row as LineDraft)"
              v-model="row.priceChangeReason"
              size="small"
              maxlength="100"
              placeholder="必填"
              :class="{ 'is-missing': submitTried && needReason(row as LineDraft) }"
            />
            <span v-else class="dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="100" class-name="num" align="right">
          <template #default="{ row }">{{ row.qty && row.price !== null ? formatMoney(row.qty * row.price) : '—' }}</template>
        </el-table-column>
        <el-table-column label="行交期" width="140">
          <template #default="{ row }">
            <el-date-picker v-model="row.lineDueDate" size="small" type="date" value-format="YYYY-MM-DD" placeholder="同单头" />
          </template>
        </el-table-column>
        <el-table-column label="预计到货" width="140">
          <template #default="{ row }">
            <el-date-picker v-model="row.expectDate" size="small" type="date" value-format="YYYY-MM-DD" placeholder="同交期" />
          </template>
        </el-table-column>
        <el-table-column label="" width="56" align="right">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="removeLine($index)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <DataPick v-model="skuPicking" kind="sku" :have="haveSkuIds" @pick="onPickedSkus" />
    <DataPick v-model="stylePicking" kind="style" :have="haveStyleIds" @pick="onPickedPlanStyles" />
  </div>
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 16px;
  max-width: 1180px;
}

.head-grid .span-2 {
  grid-column: span 2;
}

.head-grid .full {
  grid-column: 1 / -1;
}

.head-grid :deep(.el-select),
.head-grid :deep(.el-input-number),
.head-grid :deep(.el-date-editor) {
  width: 100%;
}

@media (max-width: 960px) {
  .head-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .head-grid .span-2 {
    grid-column: 1 / -1;
  }
}

.direct {
  margin-left: 8px;
  font-size: 12px;
  color: var(--lm-pending-text);
}

.checks {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 32px;
}

.plan-styles {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.sec-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 14px;
  margin: 8px 0 10px;
  border-top: 1px solid var(--lm-line-soft);
}

.sec-bar h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.small {
  font-size: 11.5px;
}

.qty {
  width: 86px;
}

.amber :deep(.el-input__wrapper) {
  background: var(--lm-pending-bg);
  box-shadow: 0 0 0 1px var(--lm-pending-line) inset;
}

.is-missing :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--lm-danger-text) inset;
}
</style>
