<script setup lang="ts">
import { isFutureBusinessDay } from '@/utils/businessDate'
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { companyApi, warehouseApi } from '@/api/basic'
import { invDocApi as invDocApiBase, stockApi } from '@/api/inventory'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { todayStr } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { CompanyOption, MaterialPickRow, WarehouseOption } from '@/types/basic'
import { INV_DOC_TYPE, type InvDocDetail, type InvDocItemForm, type InvDocType } from '@/types/inventory'
import type { IssueOption } from '@/types/production'
import type { StylePickRow } from '@/types/style'
import { formatMoney, formatQty } from '@/utils/format'
import { orNull } from '@/views/basic/common'
import PickProductionDialog from './PickProductionDialog.vue'
import { bindCompanyApi } from '@/utils/companyScope'
const invDocApi = bindCompanyApi(invDocApiBase, () => head.companyId)

/**
 * 手工新建、改草稿的入库单 / 出库单对话框（docs/api/04 §1.5、§3.2.3-3.2.4、§4）。
 *
 *   <InvDocFormDialog v-model="creating" doc-type="OTHER_IN" @saved="(id) => router.push(`/stock-in/${id}`)" />
 *   <InvDocFormDialog v-model="editing" :doc-type="d.docType" :editing-doc="d" @saved="refresh" />
 *   退料入库选了"源生产单"：发 from-production（生产单 id），调用方接着开 SourceInvDocDialog（走生成接口）
 *
 * - 出仓、入仓、往来、原因出不出、怎么填都看 INV_DOC_TYPE，不写 if (docType === …)
 * - 有出仓：选了出仓、加了行都取出仓库存，数量大于库存的标红"库存不够，审核会失败"，不拦保存
 * - 手建的单单价选填、显示金额；源单生成的草稿单价按源单算，不显示单价；领料草稿能改超领原因
 */
const visible = defineModel<boolean>({ required: true })
const user = useUserStore()

const props = defineProps<{
  docType: InvDocType
  editingDoc?: InvDocDetail | null
}>()

const emit = defineEmits<{
  saved: [id: string]
  /** 退料入库选了源生产单：走生成接口 */
  fromProduction: [productionOrderId: string]
}>()

const units = useDict('unit')

interface LineRow {
  /** 改草稿时原来的行：保存时带回，后端按它认行 */
  id: string | null
  /** 过过账的行（反审核回来的草稿）不能删 */
  posted: boolean
  sourceItemId: string | null
  materialSkuId: string
  skuCode: string
  materialName: string
  stockUnit: string
  purchaseUnit: string | null
  qty: number | null
  purchaseQty: number | null
  price: number | null
  dyeLot: string
  rolls: number | null
  overReason: string
  remark: string
  fromStockQty: number | null
}

const companies = ref<CompanyOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const saving = ref(false)
const skuPicking = ref(false)
const prodPicking = ref(false)

const head = reactive({
  companyId: '1',
  docDate: todayStr(),
  fromWarehouseId: null as string | null,
  toWarehouseId: null as string | null,
  partnerId: null as string | null,
  partnerName: null as string | null,
  reason: '',
  supplierDeliveryNo: '',
  logisticsNo: '',
  remark: '',
})

const lines = ref<LineRow[]>([])
const guard = useUnsavedChanges(() => ({ head, lines: lines.value }), visible)

const activeType = computed<InvDocType>(() => props.editingDoc?.docType ?? props.docType)
const meta = computed(() => INV_DOC_TYPE[activeType.value])
const isSourceDoc = computed(() => Boolean(props.editingDoc?.source))
const isPurchaseDoc = computed(() => activeType.value === 'PURCHASE_IN' || activeType.value === 'PURCHASE_RETURN')
/** 退料入库新建时能挂源生产单（选了就走生成接口） */
const canPickSource = computed(() => !props.editingDoc && meta.value.source === 'PRODUCTION_ORDER' && !meta.value.sourceRequired)
const showOverReason = computed(() => activeType.value === 'ISSUE_OUT')
const lineAmount = (l: LineRow) => (l.qty !== null && l.price !== null ? l.qty * l.price : null)
const short = (l: LineRow) => l.fromStockQty !== null && (l.qty ?? 0) > l.fromStockQty

const fromOptions = computed(() => {
  const allowed = meta.value.from
  if (!allowed) return []
  return warehouses.value.filter((w) => allowed.includes(w.type))
})

const toOptions = computed(() => {
  const allowed = meta.value.to
  if (!allowed) return []
  if (activeType.value === 'TRANSFER' && head.fromWarehouseId) {
    const fromWh = warehouses.value.find((w) => w.id === head.fromWarehouseId)
    if (fromWh) {
      return warehouses.value.filter((w) => w.type === fromWh.type && w.id !== fromWh.id)
    }
  }
  return warehouses.value.filter((w) => allowed.includes(w.type))
})

async function refreshStocks() {
  if (!head.fromWarehouseId || !lines.value.length) return
  const whId = head.fromWarehouseId
  try {
    const skuIds = [...new Set(lines.value.map((l) => l.materialSkuId))]
    const list = await stockApi.qty(whId, skuIds, { silent: true })
    // 等返回的时候又换了仓：只认最后选的
    if (whId !== head.fromWarehouseId) return
    const map = new Map(list.map((x) => [x.materialSkuId, x.qty]))
    lines.value.forEach((l) => {
      l.fromStockQty = map.get(l.materialSkuId) ?? 0
    })
  } catch {
    // 只是参考：取不到写"—"，不影响填单
    lines.value.forEach((l) => {
      l.fromStockQty = null
    })
  }
}

function onPickedProduction(order: IssueOption) {
  guard.markSaved()
  visible.value = false
  emit('fromProduction', order.id)
}

async function init() {
  if (!visible.value) return
  const [comps, whs] = await Promise.all([companyApi.options(), warehouseApi.options()])
  companies.value = comps
  warehouses.value = whs

  const d = props.editingDoc
  if (d) {
    Object.assign(head, {
      companyId: d.companyId,
      docDate: d.docDate,
      fromWarehouseId: d.fromWarehouseId,
      toWarehouseId: d.toWarehouseId,
      partnerId: d.partnerId,
      partnerName: d.partnerName,
      reason: d.reason ?? '',
      supplierDeliveryNo: d.supplierDeliveryNo ?? '',
      logisticsNo: d.logisticsNo ?? '',
      remark: d.remark ?? '',
    })
    lines.value = d.items.map((it) => ({
      id: it.id,
      posted: it.posted,
      sourceItemId: it.sourceItemId,
      materialSkuId: it.materialSkuId,
      skuCode: it.skuCode,
      materialName: [it.materialName, it.colorName, it.spec].filter(Boolean).join(' '),
      stockUnit: it.stockUnit,
      purchaseUnit: it.purchaseUnit,
      qty: it.qty,
      purchaseQty: it.purchaseQty,
      price: it.price,
      dyeLot: it.dyeLot ?? '',
      rolls: it.rolls,
      overReason: it.overReason ?? '',
      remark: it.remark ?? '',
      fromStockQty: null,
    }))
  } else {
    Object.assign(head, {
      // 默认我的默认主体；没有就先放第一个（下拉里能换）
      companyId: user.defaultCompanyId ?? comps[0]?.id ?? 1,
      docDate: todayStr(),
      fromWarehouseId: fromOptions.value[0]?.id ?? null,
      toWarehouseId: null,
      partnerId: null,
      partnerName: null,
      reason: '',
      supplierDeliveryNo: '',
      logisticsNo: '',
      remark: '',
    })
    head.toWarehouseId = toOptions.value[0]?.id ?? null
    lines.value = []
  }
  await refreshStocks()
  guard.markSaved()
}

watch(visible, (on) => {
  if (on) void init()
})

watch(
  () => head.fromWarehouseId,
  (whId) => {
    if (!visible.value) return
    if (activeType.value === 'RETURN_IN' && whId) {
      const hit = warehouses.value.find((w) => w.id === whId)
      if (hit?.factoryId) head.partnerId = hit.factoryId
    }
    if (activeType.value === 'TRANSFER' && head.toWarehouseId === whId) {
      head.toWarehouseId = null
    }
    void refreshStocks()
  },
)

async function onPickedSkus(rows: MaterialPickRow[] | StylePickRow[]) {
  for (const r of rows as MaterialPickRow[]) {
    lines.value.push({
      id: null,
      posted: false,
      sourceItemId: null,
      materialSkuId: r.skuId,
      skuCode: r.skuCode,
      materialName: [r.materialName, r.colorName, r.spec].filter(Boolean).join(' '),
      stockUnit: r.stockUnit,
      purchaseUnit: r.purchaseUnit,
      // 数量不替人填
      qty: null,
      purchaseQty: null,
      price: r.price,
      dyeLot: '',
      rolls: null,
      overReason: '',
      remark: '',
      fromStockQty: null,
    })
  }
  await refreshStocks()
}

function disableFuture(d: Date) {
  return isFutureBusinessDay(d)
}

async function save() {
  if (meta.value.from && !head.fromWarehouseId) return void ElMessage.warning('先选出仓')
  if (meta.value.to && !head.toWarehouseId) return void ElMessage.warning('先选入仓')
  if (activeType.value === 'TRANSFER' && head.fromWarehouseId === head.toWarehouseId) return void ElMessage.warning('调拨的出仓、入仓不能是同一个')
  if (meta.value.reasonDict && !head.reason) return void ElMessage.warning('先选原因')
  if (!lines.value.length) return void ElMessage.warning('至少加一行物料')
  const noQty = lines.value.findIndex((l) => !(isPurchaseDoc.value ? l.purchaseQty : l.qty))
  if (noQty >= 0) return void ElMessage.warning(`第 ${noQty + 1} 行没填数量`)

  const items: InvDocItemForm[] = lines.value.map((l) => ({
    id: l.id,
    sourceItemId: l.sourceItemId,
    materialSkuId: l.materialSkuId,
    qty: isPurchaseDoc.value ? null : l.qty,
    purchaseQty: isPurchaseDoc.value ? l.purchaseQty : null,
    price: isSourceDoc.value ? null : l.price,
    dyeLot: orNull(l.dyeLot),
    rolls: l.rolls,
    overReason: orNull(l.overReason),
    remark: orNull(l.remark),
  }))

  const baseBody = {
    companyId: head.companyId,
    docDate: head.docDate,
    fromWarehouseId: head.fromWarehouseId,
    toWarehouseId: head.toWarehouseId,
    partnerId: head.partnerId,
    reason: orNull(head.reason),
    supplierDeliveryNo: orNull(head.supplierDeliveryNo),
    logisticsNo: orNull(head.logisticsNo),
    remark: orNull(head.remark),
    items,
  }

  saving.value = true
  try {
    if (props.editingDoc) {
      await invDocApi.update(props.editingDoc.id, { ...baseBody, version: props.editingDoc.version })
      ElMessage.success(`${props.editingDoc.no} 已保存`)
      guard.markSaved()
      visible.value = false
      emit('saved', props.editingDoc.id)
    } else {
      const created = await invDocApi.create({ ...baseBody, docType: activeType.value })
      ElMessage.success(`${created.no} 草稿存好了，核对后审核过账`)
      guard.markSaved()
      visible.value = false
      emit('saved', created.id)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="editingDoc ? `编辑 ${editingDoc.no}（${meta.label}）` : `新建${meta.label}`"
    width="900px"
    :close-on-click-modal="false"
    :before-close="guard.beforeClose"
  >
    <el-form label-position="top" class="head-grid">
      <el-form-item label="主体">
        <el-select v-model="head.companyId" filterable>
          <el-option v-for="c in companies" :key="c.id" :label="c.shortName" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="日期">
        <el-date-picker v-model="head.docDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disableFuture" :clearable="false" />
      </el-form-item>
      <el-form-item v-if="meta.from" label="出仓">
        <el-select v-model="head.fromWarehouseId" filterable>
          <el-option v-for="w in fromOptions" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="meta.to" label="入仓">
        <el-select v-model="head.toWarehouseId" filterable>
          <el-option v-for="w in toOptions" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="canPickSource" label="源生产单">
        <el-button @click="prodPicking = true">选生产单</el-button>
      </el-form-item>
      <el-form-item v-if="meta.partner === 'FACTORY' && !isSourceDoc" label="往来加工厂">
        <SupplierSelect v-model="head.partnerId" :category="['GARMENT', 'PROCESSOR']" :label="head.partnerName" clearable />
      </el-form-item>

      <el-form-item v-if="meta.reasonDict" label="原因" required>
        <DictSelect v-model="head.reason" :type="meta.reasonDict" />
      </el-form-item>
      <el-form-item v-else label="原因">
        <el-input v-model="head.reason" maxlength="100" />
      </el-form-item>

      <el-form-item label="物流单号">
        <el-input v-model="head.logisticsNo" maxlength="60" />
      </el-form-item>
      <el-form-item label="备注" class="span-2">
        <el-input v-model="head.remark" maxlength="160" />
      </el-form-item>
    </el-form>

    <div class="line-bar">
      <span class="dim">同一种料不同缸号分几行填</span>
      <el-button v-if="!isSourceDoc" size="small" icon="Plus" @click="skuPicking = true">从物料库选</el-button>
    </div>

    <el-table :data="lines" empty-text="还没有物料：点「从物料库选」">
      <el-table-column label="物料" min-width="200">
        <template #default="{ row }">
          <div class="sku-cell">
            <span class="code">{{ row.skuCode }}</span>
            <span class="dim">{{ row.materialName }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column v-if="meta.from" label="出仓库存" width="110" class-name="num" align="right">
        <template #default="{ row }">
          <span :class="{ danger: short(row as LineRow) }">{{ row.fromStockQty === null ? '—' : formatQty(row.fromStockQty) }}</span>
          <div v-if="short(row as LineRow)" class="danger small">库存不够，审核会失败</div>
        </template>
      </el-table-column>

      <el-table-column label="数量" width="150" class-name="num" align="right">
        <template #default="{ row }">
          <div class="qty-box">
            <el-input-number
              v-if="isPurchaseDoc"
              v-model="row.purchaseQty"
              size="small"
              :min="0.001"
              :precision="3"
              :controls="false"
            />
            <el-input-number v-else v-model="row.qty" size="small" :min="0.001" :precision="3" :controls="false" />
            <span class="dim unit">{{ units.labelOf(isPurchaseDoc ? (row.purchaseUnit ?? row.stockUnit) : row.stockUnit) }}</span>
          </div>
        </template>
      </el-table-column>

      <el-table-column v-if="!isSourceDoc" label="单价" width="104" class-name="num" align="right">
        <template #default="{ row }">
          <el-input-number v-model="row.price" size="small" :min="0" :precision="4" :controls="false" :value-on-clear="null" placeholder="选填" />
        </template>
      </el-table-column>
      <el-table-column v-if="!isSourceDoc" label="金额" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ lineAmount(row as LineRow) === null ? '—' : formatMoney(lineAmount(row as LineRow) ?? 0) }}</template>
      </el-table-column>

      <el-table-column label="缸号 / 卷数" width="160">
        <template #default="{ row }">
          <div class="lot-row">
            <el-input v-model="row.dyeLot" size="small" placeholder="缸号" />
            <el-input-number v-model="row.rolls" size="small" :min="0" :precision="0" :controls="false" placeholder="卷" class="rolls" />
          </div>
        </template>
      </el-table-column>

      <el-table-column v-if="showOverReason" label="超领原因" min-width="130">
        <template #default="{ row }">
          <el-input v-model="row.overReason" size="small" maxlength="100" placeholder="领多了才填" />
        </template>
      </el-table-column>

      <el-table-column label="备注" min-width="130">
        <template #default="{ row }">
          <el-input v-model="row.remark" size="small" maxlength="100" />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="60" align="right">
        <template #default="{ row, $index }">
          <el-tooltip v-if="(row as LineRow).posted" content="这一行过过账，不能删，数量能改" placement="top">
            <span class="dim small">不能删</span>
          </el-tooltip>
          <el-button v-else link type="danger" size="small" @click="lines.splice($index, 1)">删</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ editingDoc ? '保存' : '存草稿' }}</el-button>
    </template>
  </el-dialog>

  <DataPick v-model="skuPicking" kind="sku" @pick="onPickedSkus" />
  <PickProductionDialog v-if="canPickSource" v-model="prodPicking" returnable @pick="onPickedProduction" />
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 14px;
}

.head-grid .span-2 {
  grid-column: span 2;
}

.head-grid :deep(.el-select),
.head-grid :deep(.el-date-editor) {
  width: 100%;
}

.line-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12.5px;
}

.sku-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qty-box {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.qty-box :deep(.el-input-number--small) {
  width: 88px;
}

.unit {
  font-size: 12px;
}

.lot-row {
  display: flex;
  gap: 6px;
}

.lot-row .rolls {
  width: 56px;
  flex: none;
}

.danger {
  color: var(--lm-danger-text);
}

.small {
  font-size: 11.5px;
}

@media (max-width: 820px) {
  .head-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
