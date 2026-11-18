<script setup lang="ts">
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'
import StatusTag from '@/components/StatusTag.vue'
import { useDict } from '@/composables/useDict'
import { useTable } from '@/composables/useTable'
import { ORDER_STATUS, type OrderStatus, type StyleOrderRow } from '@/types/order'
import { formatDate, formatQty } from '@/utils/format'

const props = defineProps<{
  styleId: string
}>()

const router = useRouter()
const orderTypes = useDict('order_type')
const brands = useDict('brand')

const { page, size, pageSizes, list, total, loading } = useTable({
  fetch: (params) => orderApi.byStyle(props.styleId, params),
  query: {},
})

function openOrder(row: StyleOrderRow) {
  router.push(`/orders/${row.orderId}`)
}
</script>

<template>
  <div>
    <el-table
      v-loading="loading"
      :data="list"
      row-key="orderId"
      row-class-name="clickable"
      empty-text="这款还没有下过需求订单"
      @row-click="openOrder"
    >
      <el-table-column label="订单号" prop="no" min-width="150" class-name="code" />
      <el-table-column label="类型 · 品牌" min-width="140">
        <template #default="{ row }">
          {{ orderTypes.labelOf(row.orderType) }}
          <span v-if="row.brand" class="dim"> · {{ brands.labelOf(row.brand) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="本款件数" width="110" class-name="num" align="right">
        <template #default="{ row }">{{ formatQty(row.qty) }}</template>
      </el-table-column>
      <el-table-column label="锁定资料包" width="110">
        <template #default="{ row }">
          <span v-if="row.techPackVersion != null" class="code">生产 V{{ row.techPackVersion }}</span>
          <span v-else class="dim">未锁版</span>
        </template>
      </el-table-column>
      <el-table-column label="交期" width="130" class-name="num">
        <template #default="{ row }">{{ formatDate(row.commitDate ?? row.requiredDate) }}</template>
      </el-table-column>
      <el-table-column label="跟单" width="100">
        <template #default="{ row }">{{ row.merchName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <StatusTag :tone="ORDER_STATUS[row.status as OrderStatus].tone">{{ ORDER_STATUS[row.status as OrderStatus].label }}</StatusTag>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
      v-model:current-page="page"
      v-model:page-size="size"
      class="pagination"
      layout="total, sizes, prev, pager, next"
      :page-sizes="pageSizes"
      :total="total"
    />
  </div>
</template>

<style scoped>
:deep(.el-table__row.clickable) {
  cursor: pointer;
}
</style>
