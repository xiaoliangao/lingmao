<script setup lang="ts">
import { useRouter } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import { CHANGE_REJECTED, CHANGE_STATUS, CHANGE_TYPE, RESPONSIBLE_PARTY, type OrderChangeRow } from '@/types/order'
import { formatDate } from '@/utils/format'

/**
 * 变更单表格（docs/api/02 §1.4 的列）：变更单列表、订单详情的"变更"页签共用。点一行去变更单详情。
 * 行上 actions 有 audit 时行尾出"驳回""审核"，点了发 act 事件，页面自己调接口、刷新。
 *
 *   <ChangeTable :rows="list" :loading="loading" empty-text="…" @act="quickAct" />
 *   <ChangeTable :rows="changes" :show-order="false" empty-text="没有变更。…" @act="quickAct" />
 */
withDefaults(defineProps<{ rows: OrderChangeRow[]; loading?: boolean; emptyText: string; showOrder?: boolean }>(), {
  loading: false,
  showOrder: true,
})
const emit = defineEmits<{ act: [row: OrderChangeRow, action: 'audit' | 'reject'] }>()

const router = useRouter()

const typesText = (row: OrderChangeRow) => row.changeTypes.map((t) => CHANGE_TYPE[t]).join('、')
const statusOf = (row: OrderChangeRow) => (row.status === 'DRAFT' && row.rejectReason ? CHANGE_REJECTED : CHANGE_STATUS[row.status])

function openRow(row: OrderChangeRow) {
  void router.push(`/order-changes/${row.id}`)
}
</script>

<template>
  <el-table v-loading="loading" :data="rows" row-key="id" row-class-name="clickable" :empty-text="emptyText" @row-click="openRow">
    <el-table-column label="变更单号" prop="no" width="150" class-name="code" />
    <el-table-column v-if="showOrder" label="订单号" width="150">
      <template #default="{ row }">
        <router-link :to="`/orders/${row.orderId}`" class="code" @click.stop>{{ row.orderNo }}</router-link>
      </template>
    </el-table-column>
    <el-table-column label="类型" width="120">
      <template #default="{ row }">{{ typesText(row as OrderChangeRow) }}</template>
    </el-table-column>
    <el-table-column label="改了什么" min-width="240">
      <template #default="{ row }"><span class="wrap">{{ row.summary }}</span></template>
    </el-table-column>
    <el-table-column label="原因" prop="reason" min-width="140" show-overflow-tooltip />
    <el-table-column label="责任方" width="72">
      <template #default="{ row }">{{ RESPONSIBLE_PARTY[(row as OrderChangeRow).responsibleParty] }}</template>
    </el-table-column>
    <el-table-column label="制单" width="120">
      <template #default="{ row }">
        {{ row.createByName ?? '—' }} <span class="dim num">{{ formatDate(row.createTime) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="审核" width="120">
      <template #default="{ row }">
        <template v-if="row.auditTime">{{ row.auditByName ?? '—' }} <span class="dim num">{{ formatDate(row.auditTime) }}</span></template>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="84">
      <template #default="{ row }">
        <StatusTag :tone="statusOf(row as OrderChangeRow).tone">{{ statusOf(row as OrderChangeRow).label }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="" width="104" align="right">
      <template #default="{ row }">
        <template v-if="(row as OrderChangeRow).actions.includes('audit')">
          <el-button link type="danger" size="small" @click.stop="emit('act', row as OrderChangeRow, 'reject')">驳回</el-button>
          <el-button link type="primary" size="small" @click.stop="emit('act', row as OrderChangeRow, 'audit')">审核</el-button>
        </template>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.wrap {
  white-space: pre-line;
}
</style>
