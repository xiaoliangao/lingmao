<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type TableInstance } from 'element-plus'
import { purchaseApi, reorderApi } from '@/api/purchase'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import { useDict } from '@/composables/useDict'
import { PURCHASE_STATUS, type ReorderPickItem, type ReorderRow } from '@/types/purchase'
import { formatPrice, formatQty } from '@/utils/format'

/**
 * 补货提醒（/reorder，docs/api/03 §1.4、§3.3.8、§3.6）：低于安全库存的物料，勾选后按供应商拆成备货采购单草稿。
 * 行上能改供应商（换了重取单价）、补货量。切走再切回来不重新取：手改的量和供应商留着；要最新的点「重新取」
 */
interface EditableReorderRow extends ReorderRow {
  supplierIdInput: number | null
  qtyInput: number
}

const router = useRouter()
const units = useDict('unit')

const rows = ref<EditableReorderRow[]>([])
const selected = ref<EditableReorderRow[]>([])
const loading = ref(false)
const submitting = ref(false)
const tableRef = ref<TableInstance>()

async function load() {
  loading.value = true
  try {
    const data = await reorderApi.list()
    rows.value = data.map((r) => ({
      ...r,
      supplierIdInput: r.defaultSupplierId,
      qtyInput: r.suggestQty,
    }))
    await nextTick()
    tableRef.value?.clearSelection()
    for (const row of rows.value) {
      if (row.openDrafts.length === 0 && row.supplierIdInput) {
        tableRef.value?.toggleRowSelection(row, true)
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function onSupplierChange(row: EditableReorderRow) {
  const supplierId = row.supplierIdInput
  if (!supplierId) {
    row.price = null
    return
  }
  const def = await purchaseApi.defaults({ supplierId, skuIds: [row.materialSkuId] })
  // 等返回的时候又换了一家：只认最后选的
  if (row.supplierIdInput === supplierId) row.price = def.lines[0]?.price ?? null
}

async function generateStockPos() {
  if (!selected.value.length) {
    ElMessage.warning('先勾要补货的物料')
    return
  }
  const items: ReorderPickItem[] = []
  for (const r of selected.value) {
    if (!r.supplierIdInput) {
      ElMessage.warning(`${r.skuCode} 还没选供应商`)
      return
    }
    if (!r.qtyInput || r.qtyInput <= 0) {
      ElMessage.warning(`${r.skuCode} 补货量要大于 0`)
      return
    }
    items.push({ materialSkuId: r.materialSkuId, supplierId: r.supplierIdInput, qty: r.qtyInput })
  }

  submitting.value = true
  try {
    const created = await purchaseApi.fromReorder(items)
    ElMessage.success(`按供应商拆成 ${created.length} 张备货采购单草稿：${created.map((c) => c.no).join('、')}`)
    if (created.length === 1 && created[0]) {
      await router.push(`/purchases/${created[0].id}`)
    }
    // 生成过的行带上了"已开的备货单"：重新取，免得再生成一次
    await load()
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="补货提醒" :back="{ label: '库存', to: '/stock' }">
      <template #actions>
        <el-button :loading="loading" @click="load">重新取</el-button>
        <el-button
          v-perm="'purchase:order:add'"
          type="primary"
          :loading="submitting"
          :disabled="!selected.length"
          @click="generateStockPos"
        >
          生成备货采购单{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
      </template>
    </PageHeader>

    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      row-key="materialSkuId"
      empty-text="没有低于安全库存的物料"
      @selection-change="selected = $event"
    >
      <el-table-column type="selection" width="40" />
      <el-table-column label="物料 SKU" min-width="210">
        <template #default="{ row }">
          <div class="sku-cell">
            <router-link :to="`/materials/${row.materialId}`" class="code link">{{ row.skuCode }}</router-link>
            <span class="dim">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="供应商" width="175">
        <template #default="{ row }">
          <SupplierSelect
            v-model="row.supplierIdInput"
            usage="BULK"
            :label="row.defaultSupplierName"
            :placeholder="row.defaultSupplierId ? '选供应商' : '物料没设默认供应商'"
            @update:model-value="onSupplierChange(row as EditableReorderRow)"
          />
        </template>
      </el-table-column>
      <el-table-column label="物料仓库存" width="104" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.stockQty) }} {{ units.labelOf(row.stockUnit) }}</template>
      </el-table-column>
      <el-table-column label="在途" width="90" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.transitQty) }}</template>
      </el-table-column>
      <el-table-column label="已被占用" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.heldQty) }}</template>
      </el-table-column>
      <el-table-column label="安全库存" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.safetyStock) }}</template>
      </el-table-column>
      <el-table-column label="差多少" width="104" class-name="num" align="right">
        <template #default="{ row }"><strong class="warn">{{ formatQty(row.gapQty) }}</strong></template>
      </el-table-column>
      <el-table-column label="补货量" width="150" class-name="num" align="right">
        <template #default="{ row }">
          <div class="qty-box">
            <el-input-number v-model="row.qtyInput" size="small" :min="0" :precision="3" :controls="false" />
            <span class="dim unit">{{ units.labelOf(row.purchaseUnit) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="参考单价" width="96" class-name="num" align="right">
        <template #default="{ row }">{{ row.price == null ? '—' : formatPrice(row.price) }}</template>
      </el-table-column>
      <el-table-column label="已开的备货单" min-width="150">
        <template #default="{ row }">
          <template v-if="(row as EditableReorderRow).openDrafts.length">
            <router-link
              v-for="d in (row as EditableReorderRow).openDrafts"
              :key="d.id"
              :to="`/purchases/${d.id}`"
              class="draft-chip code"
            >
              {{ d.no }}
              <StatusTag :tone="PURCHASE_STATUS[d.status].tone">{{ PURCHASE_STATUS[d.status].label }}</StatusTag>
            </router-link>
          </template>
          <span v-else class="dim">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.sku-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}

.warn {
  color: var(--lm-pending-text);
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

.draft-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 6px;
  color: var(--lm-accent);
  text-decoration: none;
}
</style>
