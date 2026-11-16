<script lang="ts">
import type { ColumnDef } from '@/composables/useColumns'

export type ChangeColumn = 'order' | 'types' | 'summary' | 'reason' | 'party' | 'created' | 'audited' | 'status' | 'fee' | 'rejectReason'

/**
 * 能挑的列（变更单号、行尾的审核按钮总在）。变更单列表：useColumns('order-changes', CHANGE_COLUMNS)。
 * 审核人默认不显示：1440 宽的屏上一屏放下、不用横着滚（状态里已经看得出审没审）
 */
export const CHANGE_COLUMNS: readonly ColumnDef<ChangeColumn>[] = [
  { key: 'order', label: '订单号' },
  { key: 'types', label: '类型' },
  { key: 'summary', label: '改了什么' },
  { key: 'reason', label: '原因' },
  { key: 'party', label: '责任方' },
  { key: 'created', label: '制单' },
  { key: 'audited', label: '审核', hidden: true },
  { key: 'fee', label: '费用说明', hidden: true },
  { key: 'rejectReason', label: '驳回原因', hidden: true },
  { key: 'status', label: '状态' },
]
</script>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import StatusTag from '@/components/StatusTag.vue'
import type { ColumnsState } from '@/composables/useColumns'
import { CHANGE_REJECTED, CHANGE_STATUS, CHANGE_TYPE, RESPONSIBLE_PARTY, type OrderChangeRow } from '@/types/order'
import { formatDate } from '@/utils/format'

/**
 * 变更单表格（docs/api/02 §1.4 的列）：变更单列表、订单详情的"变更"页签共用。点一行去变更单详情。
 * 行上 actions 有 audit 时行尾出"驳回""审核"（固定在右边），点了发 act 事件，页面自己调接口、刷新。
 *
 *   <ChangeTable :rows="list" :loading="loading" :columns="cols" empty-text="…" @act="quickAct" />     cols = useColumns('order-changes', CHANGE_COLUMNS)
 *   <ChangeTable :rows="changes" :show-order="false" empty-text="没有变更。…" @act="quickAct" />          不传 columns 按默认的列
 *
 * 改了什么、原因放不下的末尾省略号，鼠标移上去看全文；制单、审核是人名，日期小字放第二行
 */
const props = withDefaults(
  defineProps<{ rows: OrderChangeRow[]; loading?: boolean; emptyText: string; showOrder?: boolean; columns?: ColumnsState<ChangeColumn> }>(),
  { loading: false, showOrder: true, columns: undefined },
)
const emit = defineEmits<{ act: [row: OrderChangeRow, action: 'audit' | 'reject'] }>()

const router = useRouter()

/** 这一列显不显示：传了 columns 听各人挑的，没传按默认 */
const show = (key: ChangeColumn) => (props.columns ? props.columns.show[key] : !CHANGE_COLUMNS.find((c) => c.key === key)?.hidden)
const typesText = (row: OrderChangeRow) => row.changeTypes.map((t) => CHANGE_TYPE[t]).join('、')
const statusOf = (row: OrderChangeRow) => (row.status === 'DRAFT' && row.rejectReason ? CHANGE_REJECTED : CHANGE_STATUS[row.status])

function openRow(row: OrderChangeRow) {
  void router.push(`/order-changes/${row.id}`)
}
</script>

<template>
  <el-table v-loading="loading" :data="rows" row-key="id" row-class-name="clickable" scrollbar-always-on :empty-text="emptyText" @row-click="openRow">
    <el-table-column label="变更单号" prop="no" width="140" fixed="left" class-name="code" />
    <el-table-column v-if="showOrder && show('order')" label="订单号" width="140">
      <template #default="{ row }">
        <router-link :to="`/orders/${row.orderId}`" class="code" @click.stop>{{ row.orderNo }}</router-link>
      </template>
    </el-table-column>
    <el-table-column v-if="show('types')" label="类型" width="120">
      <template #default="{ row }">{{ typesText(row as OrderChangeRow) }}</template>
    </el-table-column>
    <el-table-column v-if="show('summary')" label="改了什么" min-width="240">
      <template #default="{ row }">{{ row.summary }}</template>
    </el-table-column>
    <el-table-column v-if="show('reason')" label="原因" prop="reason" min-width="160" />
    <el-table-column v-if="show('party')" label="责任方" width="80">
      <template #default="{ row }">{{ RESPONSIBLE_PARTY[(row as OrderChangeRow).responsibleParty] }}</template>
    </el-table-column>
    <el-table-column v-if="show('created')" label="制单" width="104">
      <template #default="{ row }">
        {{ row.createByName ?? '—' }}
        <div class="dim small num">{{ formatDate(row.createTime) }}</div>
      </template>
    </el-table-column>
    <el-table-column v-if="show('audited')" label="审核" width="104">
      <template #default="{ row }">
        <template v-if="row.auditTime">
          {{ row.auditByName ?? '—' }}
          <div class="dim small num">{{ formatDate(row.auditTime) }}</div>
        </template>
        <span v-else class="dim">—</span>
      </template>
    </el-table-column>
    <el-table-column v-if="show('fee')" label="费用说明" min-width="160">
      <template #default="{ row }">{{ row.feeNote || '—' }}</template>
    </el-table-column>
    <el-table-column v-if="show('rejectReason')" label="驳回原因" min-width="160">
      <template #default="{ row }">{{ row.rejectReason || '—' }}</template>
    </el-table-column>
    <el-table-column v-if="show('status')" label="状态" width="104">
      <template #default="{ row }">
        <StatusTag :tone="statusOf(row as OrderChangeRow).tone">{{ statusOf(row as OrderChangeRow).label }}</StatusTag>
      </template>
    </el-table-column>
    <el-table-column label="" width="120" fixed="right" align="right" :show-overflow-tooltip="false">
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
.small {
  font-size: 11.5px;
}
</style>
