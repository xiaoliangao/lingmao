<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { warehouseApi } from '@/api/basic'
import { reportApi } from '@/api/report'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useColumns } from '@/composables/useColumns'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import type { WarehouseOption } from '@/types/basic'
import type { StaleMaterialRow } from '@/types/report'
import { formatDate, formatMoney, formatPrice, formatQty } from '@/utils/format'

/**
 * 呆滞料（/stale-materials，RFC 019 §3.3，甲方 11-27 答 Q67）：物料仓里有库存、超过 N 天没出没入的物料。
 * 盘点调整不算"动过"。N 默认 90 天，甲方还没定，有权限的人能改默认（存在系统设置里）；这一页上也能临时换个天数看
 */
const units = useDict('unit')
const cols = useColumns('staleMaterials', [
  { key: 'spec', label: '规格', hidden: true },
  { key: 'price', label: '单价' },
  { key: 'amount', label: '金额' },
  { key: 'last', label: '最后出入库' },
])
const defaultDays = ref<number | null>(null)
const warehouses = ref<WarehouseOption[]>([])

const { query, page, size, pageSizes, list, total, loading, search, load, exporting, exportFile } = useTable({
  fetch: (params) => reportApi.staleMaterials(params),
  query: { keyword: '', days: undefined as number | undefined, warehouseId: undefined as string | undefined },
  exportUrl: '/api/reports/stale-materials/export',
  exportName: '呆滞料.xlsx',
  immediate: false,
})

// 默认天数取到了才算开始过：第一次取失败，切回来再取；切回来按原来的页码刷新（agy T-77e556 P1-7）
let started = false
onActivated(async () => {
  if (started) return void load()
  const [days, options] = await Promise.all([reportApi.staleDays(), warehouseApi.options('MATERIAL')])
  defaultDays.value = days
  query.days ??= days
  warehouses.value = options
  started = true
  search()
})

const warehouseName = computed(() => warehouses.value.find((w) => w.id === query.warehouseId)?.name ?? '')
const changed = computed(() => defaultDays.value != null && query.days != null && query.days !== defaultDays.value)
const saving = ref(false)
async function saveDefault() {
  if (query.days == null) return
  saving.value = true
  try {
    await reportApi.setStaleDays(query.days)
    defaultDays.value = query.days
    ElMessage.success(`以后超过 ${query.days} 天没动的算呆滞`)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader sub="物料仓里有库存、很久没出没入的物料；盘点调整不算动过">
      <template #actions>
        <el-button v-perm="'report:stale:export'" icon="Download" :loading="exporting" @click="exportFile">导出</el-button>
      </template>
    </PageHeader>

    <FilterBar v-model:keyword="query.keyword" placeholder="SKU 码、物料编码、名称" :columns="cols" @search="search">
      <template #left>
        <span class="days">
          超过
          <el-input-number v-model="query.days" :min="1" :max="3650" :step="30" :controls="false" class="days-input" @change="search" />
          天没动
          <el-button v-if="changed" v-perm="'report:stale:edit'" link type="primary" :loading="saving" @click="saveDefault">设为默认</el-button>
        </span>
      </template>
      <FilterItem label="仓库" :text="warehouseName" @clear="query.warehouseId = undefined">
        <el-select v-model="query.warehouseId" filterable clearable placeholder="全部物料仓">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </FilterItem>
    </FilterBar>

    <el-table v-loading="loading" :data="list" :row-key="(r: StaleMaterialRow) => `${r.warehouseId}-${r.materialSkuId}`" scrollbar-always-on
      :empty-text="query.keyword || query.warehouseId ? '没有符合条件的：清空筛选再看' : `物料仓里没有超过 ${query.days ?? defaultDays ?? ''} 天没动的料`">
      <el-table-column label="仓库" prop="warehouseName" width="156" fixed="left" />
      <el-table-column label="物料" min-width="220">
        <template #default="{ row }">
          <span class="code sku">{{ (row as StaleMaterialRow).skuCode }}</span>
          <span class="dim">{{ (row as StaleMaterialRow).materialName }}<template v-if="(row as StaleMaterialRow).colorName"> · {{ (row as StaleMaterialRow).colorName }}</template></span>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.spec" label="规格" prop="spec" width="100" />
      <el-table-column label="库存" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty((row as StaleMaterialRow).qty) }} {{ units.labelOf((row as StaleMaterialRow).stockUnit) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.price" label="单价" width="100" class-name="num" align="right">
        <template #default="{ row }">{{ (row as StaleMaterialRow).unitPrice == null ? '—' : formatPrice((row as StaleMaterialRow).unitPrice) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.amount" label="金额" width="120" class-name="num" align="right">
        <template #default="{ row }">{{ (row as StaleMaterialRow).amount == null ? '—' : formatMoney((row as StaleMaterialRow).amount) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.last" label="最后出入库" width="110">
        <template #default="{ row }">{{ (row as StaleMaterialRow).lastDate ? formatDate((row as StaleMaterialRow).lastDate) : '没有记录' }}</template>
      </el-table-column>
      <el-table-column label="没动" width="100" class-name="num" align="right" fixed="right">
        <template #default="{ row }">{{ (row as StaleMaterialRow).idleDays == null ? '—' : `${(row as StaleMaterialRow).idleDays} 天` }}</template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" v-model:page-size="size" class="pagination"
      layout="total, sizes, prev, pager, next" :page-sizes="pageSizes" :total="total" />
  </div>
</template>

<style scoped>
/* 模板里 SKU 码后面的空格会被去掉，用间距隔开名称 */
.sku {
  margin-right: 6px;
}

.days {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  white-space: nowrap;
}

.days-input {
  width: 72px;
}
</style>
