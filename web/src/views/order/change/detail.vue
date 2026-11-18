<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, onActivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi, orderChangeApi as orderChangeApiBase } from '@/api/order'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import { isItem, runDocAction } from '@/components/doc/docUtils'
import { useTabsStore } from '@/stores/tabs'
import type { DocAction } from '@/types/biz'
import type { FileVO } from '@/types/system'
import {
  CHANGE_REJECTED,
  CHANGE_STATUS,
  CHANGE_TYPE,
  ORDER_STATUS,
  RESPONSIBLE_PARTY,
  type ChangeAction,
  type ChangeItemView,
  type OrderChangeDetail,
  type OrderDetail,
} from '@/types/order'
import { formatDateTime } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import OrderChangeDialog from '../order/OrderChangeDialog.vue'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeOrderPrice } = useFieldAccess()
const orderChangeApi = bindCompanyApi(orderChangeApiBase, () => detail.value?.companyId)

/**
 * 订单变更单详情（/order-changes/:id，docs/api/02 §1.4）：单头、改动清单（一处一行"原 → 新"）、附件、操作记录。
 * 按钮照 actions：提交、撤回、驳回、审核、改了再提交（edit，打开提变更对话框改这张草稿）、作废、删除。
 * 状态动作 1002 时重新取详情
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

const id = routeId(route.params.id)
const path = route.path

const detail = ref<OrderChangeDetail | null>(null)
const notFound = ref(false)
const logRef = ref<InstanceType<typeof DocLogList>>()

const can = (a: ChangeAction) => detail.value?.actions.includes(a) ?? false
const status = computed(() => {
  const d = detail.value
  if (!d) return CHANGE_STATUS.DRAFT
  return d.status === 'DRAFT' && d.rejectReason ? CHANGE_REJECTED : CHANGE_STATUS[d.status]
})
const typesText = computed(() => detail.value?.changeTypes.map((t) => CHANGE_TYPE[t]).join('、') ?? '')

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  try {
    const d = await read.run(() => orderChangeApi.get(id))
    if (!read.current()) return
    detail.value = d
    tabs.rename(path, `变更 ${d.no}`)
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

onMounted(load)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (!notFound.value) void refresh()
})

async function refresh() {
  await load()
  logRef.value?.reload()
}

async function act(action: DocAction) {
  const d = detail.value
  if (!d) return
  const note = action === 'audit' ? '审核后马上改写订单，下游的生产单、采购单、算料单会收到提醒。' : undefined
  const ok = await runDocAction(action, `变更单 ${d.no}`, d.version, (a, body, o) => orderChangeApi.act(id, a, body, o), { note, reload: refresh })
  if (ok) await refresh()
}

async function remove() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, '变更单草稿删除后找不回来，订单不受影响。')
  await orderChangeApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push('/order-changes')
  tabs.close(path, '/order-changes')
}

// ---------- 改了再提交：拿订单现在的样子打开提变更对话框 ----------
const editVisible = ref(false)
const order = ref<OrderDetail | null>(null)
const opening = ref(false)
async function openEdit() {
  const d = detail.value
  if (!d) return
  opening.value = true
  try {
    order.value = await orderApi.get(d.orderId)
    editVisible.value = true
  } finally {
    opening.value = false
  }
}

type MoreCommand = 'withdraw' | 'void' | 'remove'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('void') && { command: 'void', label: '作废', danger: true },
    can('remove') && { command: 'remove', label: '删除', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'remove') void remove()
  else void act(command)
}

/** 改动的哪一项：SKU 码、款号，交期是整单 */
const targetOf = (row: ChangeItemView) => row.skuCode ?? row.styleCode ?? '整单'

const loadLogs = () => orderChangeApi.logs(id)
const loadFiles = () => orderChangeApi.attachments(id)
const saveFiles = (files: FileVO[]) => orderChangeApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(CHANGE_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? '订单变更'" :back="{ label: '订单变更', to: '/order-changes' }">
      <template v-if="detail" #title>
        <StatusTag :tone="status.tone">{{ status.label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('edit')" :loading="opening" @click="openEdit">改了再提交</el-button>
        <el-button v-if="can('submit')" type="primary" @click="act('submit')">提交</el-button>
        <el-button v-if="can('reject')" @click="act('reject')">驳回</el-button>
        <el-button v-if="can('audit')" type="primary" @click="act('audit')">审核</el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张变更单不存在，可能已经删了">
      <el-button @click="router.push('/order-changes')">回变更单列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <div v-if="detail.rejectReason && detail.status === 'DRAFT'" class="reject-bar">驳回原因：{{ detail.rejectReason }}</div>

        <section class="panel">
          <div class="panel-head">改了什么<span class="dim head-sub">{{ typesText }}</span></div>
          <div v-if="detail.cancel" class="panel-body danger">取消整张订单</div>
          <el-table v-else :data="detail.items.filter(item => canSeeOrderPrice || item.changeType !== 'PRICE')" row-key="id" empty-text="没有改动">
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ CHANGE_TYPE[(row as ChangeItemView).changeType] }}</template>
            </el-table-column>
            <el-table-column label="改的是" width="190" class-name="code">
              <template #default="{ row }">{{ targetOf(row as ChangeItemView) }}</template>
            </el-table-column>
            <el-table-column label="原 → 新" prop="text" min-width="260" />
          </el-table>
        </section>

        <section class="panel">
          <div class="panel-head">操作记录</div>
          <div class="panel-body">
            <DocLogList ref="logRef" :load="loadLogs" :statuses="LOG_STATUSES" />
          </div>
        </section>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">变更信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>订单</dt>
              <dd>
                <router-link :to="`/orders/${detail.orderId}`" class="code">{{ detail.orderNo }}</router-link>
                <StatusTag class="ml-6" :tone="ORDER_STATUS[detail.orderStatus].tone">{{ ORDER_STATUS[detail.orderStatus].label }}</StatusTag>
              </dd>
              <dt>类型</dt>
              <dd>{{ typesText || '—' }}</dd>
              <dt>原因</dt>
              <dd>{{ detail.reason }}</dd>
              <dt>责任方</dt>
              <dd>{{ RESPONSIBLE_PARTY[detail.responsibleParty] }}</dd>
              <template v-if="canSeeOrderPrice">
                <dt>费用说明</dt>
                <dd>{{ detail.feeNote || '—' }}</dd>
              </template>
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
              <dt>审核</dt>
              <dd>{{ detail.auditTime ? `${detail.auditByName ?? '—'} · ${formatDateTime(detail.auditTime)}` : '—' }}</dd>
            </dl>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">变更依据</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="detail.status !== 'DRAFT'" @changed="logRef?.reload()" />
          </div>
        </section>
      </aside>
    </div>

    <OrderChangeDialog v-if="order && detail" v-model="editVisible" :order="order" :editing="detail" @saved="refresh" />
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

.body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

@media (max-width: 1180px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.reject-bar {
  padding: 8px 12px;
  font-size: 13px;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
  border-radius: var(--lm-radius);
}

.head-sub {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
}

.danger {
  color: var(--lm-danger-text);
}

.ml-6 {
  margin-left: 6px;
}
</style>
