<script setup lang="ts">
import { routeId } from '@/utils/entityId'
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi, orderChangeApi } from '@/api/order'
import { kittingApi, mrpApi } from '@/api/purchase'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import StatusTag from '@/components/StatusTag.vue'
import ApprovalPanel from '@/components/doc/ApprovalPanel.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import DocRefs from '@/components/doc/DocRefs.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import { isItem, runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import type { DocAction } from '@/types/biz'
import type { FileVO } from '@/types/system'
import { ORDER_LOG_ACTIONS, ORDER_SOURCE, ORDER_STATUS, type OrderAction, type OrderChangeRow, type OrderDetail, type OrderStyle } from '@/types/order'
import { KITTING_STATUS, type OrderKitting } from '@/types/purchase'
import { formatDate, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { businessToday } from '@/utils/businessDate'
import { ApiError } from '@/utils/request'
import { confirmRemove } from '@/views/basic/common'
import ChangeTable from '../change/ChangeTable.vue'
import OrderAuditDialog from './OrderAuditDialog.vue'
import OrderChangeDialog from './OrderChangeDialog.vue'
import OrderScheduleDialog from './OrderScheduleDialog.vue'

/**
 * 需求订单详情（/orders/:id?tab=lines|down|changes|logs，docs/api/02 §1.3、§4.3）。
 * - 顶上按钮照 actions：编辑、提交审核、驳回 + 审核（不是我审时写"现在等××审"）、提变更、算料、排单；
 *   撤回、反审核、结案、取消结案、取消订单（走变更单）、作废、删除收进"更多"
 * - 状态动作都走 runDocAction：1002 时重新取详情（拿新 version），作废不要原因
 * - 齐料、变更单列表是另取的：取不到只是那一块空着，详情照常
 * - ?tab= 跟着地址变（只认自己这一页）；排单成功切到"下游单据"
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()

const id = routeId(route.params.id)
const path = route.path

const orderTypes = useDict('order_type')
const brands = useDict('brand')

type TabName = 'lines' | 'down' | 'changes' | 'logs'
const TABS: TabName[] = ['lines', 'down', 'changes', 'logs']
const pickTab = (value: unknown): TabName | undefined => (TABS.includes(value as TabName) ? (value as TabName) : undefined)

const detail = ref<OrderDetail | null>(null)
const kitting = ref<OrderKitting | null>(null)
const changes = ref<OrderChangeRow[]>([])
const canViewChanges = computed(() => user.hasPerm('order:change:list'))
const notFound = ref(false)
const tab = ref<TabName>(pickTab(route.query.tab) ?? 'lines')

watch(
  () => route.query.tab,
  (value) => {
    const name = pickTab(value)
    if (name && route.path === path) tab.value = name
  },
)

const logRef = ref<InstanceType<typeof DocLogList>>()
const refsRef = ref<InstanceType<typeof DocRefs>>()

const auditVisible = ref(false)
const scheduleVisible = ref(false)
const changeVisible = ref(false)
const changeCancel = ref(false)
const creatingMrp = ref(false)

const can = (a: OrderAction) => detail.value?.actions.includes(a) ?? false

async function load() {
  try {
    const d = await orderApi.get(id)
    detail.value = d
    tabs.rename(path, `订单 ${d.no}`)
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
    return
  }
  void loadKitting()
  void loadChanges()
}

async function loadKitting() {
  const d = detail.value
  if (!d || !['APPROVED', 'RUNNING', 'DONE'].includes(d.status)) {
    kitting.value = null
    return
  }
  try {
    kitting.value = (await kittingApi.byOrders([id]))[0] ?? null
  } catch {
    kitting.value = null
  }
}

async function loadChanges() {
  if (!canViewChanges.value) {
    changes.value = []
    return
  }
  try {
    changes.value = (await orderChangeApi.page({ orderId: id, page: 1, size: 100 })).list
  } catch {
    changes.value = []
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
  refsRef.value?.reload()
}

// ---------- 标题下一行 ----------
const approved = computed(() => !!detail.value && ['APPROVED', 'RUNNING', 'DONE', 'CLOSED'].includes(detail.value.status))
/** 待审核时的 commitDate 是上一级草填的，不算承诺交期（02 §3.3） */
const dueDate = computed(() => (detail.value ? (approved.value && detail.value.commitDate) || detail.value.requiredDate : ''))
/** 离交期几天：只在已审核、生产中显示 */
const daysLeft = computed(() => {
  const d = detail.value
  if (!d || (d.status !== 'APPROVED' && d.status !== 'RUNNING')) return null
  const due = new Date(`${dueDate.value}T00:00:00Z`)
  const today = new Date(`${businessToday()}T00:00:00Z`)
  return Math.round((due.getTime() - today.getTime()) / 86400000)
})

// ---------- 明细：一个款一块，只读矩阵 ----------
function itemsOf(styleId: string) {
  return detail.value?.items.filter((i) => i.styleId === styleId) ?? []
}

function colorsOf(styleId: string) {
  const seen = new Map<string, { id: string; code: string; name: string; hex: string | null }>()
  for (const it of itemsOf(styleId)) if (!seen.has(it.colorId)) seen.set(it.colorId, { id: it.colorId, code: it.colorCode, name: it.colorName, hex: it.colorHex })
  return [...seen.values()]
}

const cellsOf = (styleId: string) => itemsOf(styleId).map((i) => ({ id: i.styleSkuId, colorId: i.colorId, sizeCode: i.sizeCode }))
const qtysOf = (styleId: string) => Object.fromEntries(itemsOf(styleId).map((i) => [i.styleSkuId, i.qty]))
const itemBySku = computed(() => new Map((detail.value?.items ?? []).map((i) => [i.styleSkuId, i])))
/** 已审核以后每格小字"排 80 · 回 40" */
const subOf = (skuId: string) => {
  const it = itemBySku.value.get(skuId)
  return approved.value && it ? `排 ${it.scheduledQty} · 回 ${it.returnedQty}` : null
}

/** 款的单价：都一样写一个数，不一样写"按尺码" */
function priceText(st: OrderStyle): string {
  const prices = new Set(itemsOf(st.styleId).map((i) => i.price))
  if (prices.size > 1) return '多个单价（逐 SKU）'
  const p = [...prices][0]
  return p == null ? '没填单价' : `单价 ${formatPrice(p)}`
}

/** 混合值不以第一行冒充整款；完整值可展开逐 SKU 查看。 */
function lineNote(st: OrderStyle): string {
  const rows = itemsOf(st.styleId)
  const dates = new Set(rows.map((row) => row.lineDueDate))
  const remarks = new Set(rows.map((row) => row.remark))
  return [dates.size > 1 ? '多个行交期' : rows[0]?.lineDueDate ? `行交期 ${formatDate(rows[0].lineDueDate)}` : '',
    remarks.size > 1 ? '多个行备注' : rows[0]?.remark ?? ''].filter(Boolean).join(' · ')
}

// ---------- 按钮 ----------
async function act(action: DocAction) {
  const d = detail.value
  if (!d) return
  const ok = await runDocAction(action, `订单 ${d.no}`, d.version, (a, body, o) => orderApi.act(id, a, body, o), { reload: refresh })
  if (ok) await refresh()
}

async function remove() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, '草稿删除后找不回来；想留个底就用「作废」。')
  await orderApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push('/orders')
  tabs.close(path, '/orders')
}

function openChange(cancel: boolean) {
  changeCancel.value = cancel
  changeVisible.value = true
}

async function createMrp() {
  creatingMrp.value = true
  try {
    const res = await mrpApi.create([id])
    ElMessage.success(`${res.no} 算好了，勾选要买的行生成采购单`)
    await router.push(`/mrp/${res.id}`)
  } finally {
    creatingMrp.value = false
  }
}

function onScheduled() {
  tab.value = 'down'
  void refresh()
}

type MoreCommand = 'withdraw' | 'unaudit' | 'close' | 'reopen' | 'cancel' | 'void' | 'remove'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('unaudit') && { command: 'unaudit', label: '反审核' },
    can('close') && { command: 'close', label: '结案' },
    can('reopen') && { command: 'reopen', label: '取消结案' },
    can('change') && { command: 'cancel', label: '取消订单', danger: true },
    can('void') && { command: 'void', label: '作废', danger: true },
    can('remove') && { command: 'remove', label: '删除', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'remove') void remove()
  else if (command === 'cancel') openChange(true)
  else void act(command)
}

/** 不是我审、在等别人审：顶上写一句 */
const waitingText = computed(() => {
  const a = detail.value?.approval
  return detail.value?.status === 'PENDING' && a?.waitingFor && !a.canPass ? `现在等${a.waitingFor}审` : ''
})

// ---------- 页签里的东西（在 script 里定义，模板里不写引用 import 的箭头函数） ----------
const loadRefs = () => orderApi.references(id)
const loadLogs = () => orderApi.logs(id)
const loadFiles = () => orderApi.attachments(id)
const saveFiles = (files: FileVO[]) => orderApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(ORDER_STATUS).map(([k, v]) => [k, v.label]))

async function quickChangeAct(row: OrderChangeRow, action: 'audit' | 'reject') {
  const ch = await orderChangeApi.get(row.id)
  const ok = await runDocAction(action, `变更单 ${row.no}`, ch.version, (a, body, o) => orderChangeApi.act(row.id, a, body, o), { reload: loadChanges })
  if (ok) await refresh()
}

function openKitting() {
  const calcId = kitting.value?.calcIds[0]
  if (calcId) void router.push(`/mrp/${calcId}`)
}
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? '需求订单'" :back="{ label: '需求订单', to: '/orders' }">
      <template v-if="detail" #title>
        <StatusTag :tone="ORDER_STATUS[detail.status].tone">{{ ORDER_STATUS[detail.status].label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <span v-if="waitingText" class="dim waiting">{{ waitingText }}</span>
        <el-button v-if="can('edit')" @click="router.push(`/orders/${id}/edit`)">编辑</el-button>
        <el-button v-if="can('submit')" type="primary" @click="act('submit')">提交审核</el-button>
        <el-button v-if="can('reject')" @click="act('reject')">驳回</el-button>
        <el-button v-if="can('audit')" type="primary" @click="auditVisible = true">审核</el-button>
        <el-button v-if="can('change')" @click="openChange(false)">提变更</el-button>
        <el-button v-if="can('mrp')" :loading="creatingMrp" @click="createMrp">算料</el-button>
        <el-button v-if="can('schedule')" type="primary" @click="scheduleVisible = true">排单</el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张订单不存在，可能已经删了">
      <el-button @click="router.push('/orders')">回订单列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <div v-if="detail.pendingChange" class="chg-banner">
          <span>变更单 <span class="code">{{ detail.pendingChange.no }}</span> 待审核：{{ detail.pendingChange.summary }}</span>
          <el-button v-if="canViewChanges" size="small" @click="router.push(`/order-changes/${detail.pendingChange.id}`)">去看</el-button>
        </div>

        <p class="sub num">
          {{ formatQty(detail.totalQty) }} 件 · {{ detail.currency }} {{ formatMoney(detail.totalAmount) }} ·
          {{ approved && detail.commitDate ? '承诺交期' : '要求交期' }} {{ formatDate(dueDate) }}
          <DaysLeftTag :days-left="daysLeft" />
          <button v-if="kitting" type="button" class="tag-btn" :disabled="!kitting.calcIds.length" @click="openKitting">
            <StatusTag :tone="KITTING_STATUS[kitting.status].tone">{{ kitting.label }}</StatusTag>
          </button>
        </p>

        <el-tabs v-model="tab">
          <el-tab-pane :label="`明细（${detail.styles.length} 款）`" name="lines">
            <section v-for="st in detail.styles" :key="st.styleId" class="panel style-card">
              <div class="panel-head st-head">
                <span class="st-info">
                  <img v-if="st.image" :src="st.image.thumbUrl ?? st.image.url" alt="" class="thumb" />
                  <router-link :to="`/styles/${st.styleId}`" class="code">{{ st.styleCode }}</router-link>
                  <strong>{{ st.styleName }}</strong>
                  <StatusTag v-if="st.techPackVersion != null" tone="progress">V{{ st.techPackVersion }} 已锁定</StatusTag>
                  <span v-else-if="st.currentPackVersion != null" class="dim">资料包 V{{ st.currentPackVersion }}</span>
                  <span v-else class="danger">资料包未生效</span>
                </span>
                <span class="st-sum num">{{ priceText(st) }} · {{ formatQty(st.qty) }} 件<template v-if="st.amount != null"> · {{ formatMoney(st.amount) }}</template></span>
              </div>
              <div class="panel-body">
                <p v-if="st.techPackVersion != null && st.currentPackVersion != null && st.techPackVersion !== st.currentPackVersion" class="warn small">
                  现在生效的是 V{{ st.currentPackVersion }}，要换走变更单
                </p>
                <p v-if="lineNote(st)" class="dim small">{{ lineNote(st) }}</p>
                <SkuMatrix :model-value="qtysOf(st.styleId)" :sizes="st.sizes" :colors="colorsOf(st.styleId)" :skus="cellsOf(st.styleId)" readonly :sub="subOf" />
                <el-collapse><el-collapse-item title="逐 SKU 单价 / 行交期 / 备注">
                  <el-table :data="itemsOf(st.styleId)" size="small">
                    <el-table-column prop="skuCode" label="SKU" min-width="160" />
                    <el-table-column label="单价" width="130"><template #default="{ row }">{{ row.price == null ? '未填' : formatPrice(row.price) }}</template></el-table-column>
                    <el-table-column label="行交期" width="140"><template #default="{ row }">{{ row.lineDueDate || '同要求交期' }}</template></el-table-column>
                    <el-table-column prop="remark" label="备注" min-width="160" />
                  </el-table>
                </el-collapse-item></el-collapse>
              </div>
            </section>
          </el-tab-pane>

          <el-tab-pane label="下游单据" name="down" lazy>
            <DocRefs ref="refsRef" :load="loadRefs" empty-text="还没有下游单据：审核后点「排单」「算料」" />
          </el-tab-pane>

          <el-tab-pane :label="changes.length ? `变更 ${changes.length}` : '变更'" name="changes">
            <ChangeTable
              v-if="canViewChanges"
              :rows="changes"
              :show-order="false"
              empty-text="没有变更。订单审核后要改数量、颜色、交期、单价，点「提变更」"
              @act="quickChangeAct"
            />
            <BlankState v-else title="当前账号没有查看变更单的权限" />
          </el-tab-pane>

          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logRef" :load="loadLogs" :actions="ORDER_LOG_ACTIONS" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">订单信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>主体</dt>
              <dd>{{ detail.companyName }}</dd>
              <dt>类型</dt>
              <dd>{{ orderTypes.labelOf(detail.orderType) }}<template v-if="detail.brand"> · {{ brands.labelOf(detail.brand) }}</template></dd>
              <dt>合同号</dt>
              <dd class="code">{{ detail.contractNo || '—' }}</dd>
              <dt>下单日期</dt>
              <dd class="num">{{ formatDate(detail.orderDate) }}</dd>
              <dt>要求交期</dt>
              <dd class="num">{{ formatDate(detail.requiredDate) }}</dd>
              <template v-if="approved && detail.commitDate">
                <dt>承诺交期</dt>
                <dd class="num">{{ formatDate(detail.commitDate) }}</dd>
              </template>
              <dt>业务员</dt>
              <dd>{{ detail.salesName ?? '—' }}</dd>
              <dt>跟单员</dt>
              <dd>{{ detail.merchName ?? '—' }}</dd>
              <dt>送货要求</dt>
              <dd>{{ detail.deliveryReq || '—' }}</dd>
              <dt>备注</dt>
              <dd>{{ detail.remark || '—' }}</dd>
              <dt>来源</dt>
              <dd>{{ ORDER_SOURCE[detail.source] }}</dd>
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
            </dl>
          </div>
        </section>

        <ApprovalPanel :approval="detail.approval" :status="detail.status" />

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="detail.status === 'VOIDED' || detail.status === 'CLOSED'" @changed="logRef?.reload()" />
          </div>
        </section>
      </aside>
    </div>

    <OrderAuditDialog v-if="detail" v-model="auditVisible" :order="detail" @changed="refresh" />
    <OrderScheduleDialog v-if="detail" v-model="scheduleVisible" :order-id="id" @scheduled="onScheduled" />
    <OrderChangeDialog v-if="detail" v-model="changeVisible" :order="detail" :default-cancel="changeCancel" @saved="refresh" />
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1200px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.waiting {
  margin-right: 4px;
  font-size: 12.5px;
}

.chg-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--lm-ink-2);
}

.tag-btn {
  padding: 0;
  font: inherit;
  cursor: pointer;
  background: none;
  border: none;
}

.tag-btn:disabled {
  cursor: default;
}

.style-card {
  margin-bottom: 14px;
}

.st-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.st-info {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.st-sum {
  font-size: 12.5px;
  font-weight: 400;
}

.thumb {
  width: 28px;
  height: 28px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.small {
  margin: 0 0 8px;
  font-size: 12px;
}

.warn {
  color: var(--lm-pending-text);
}

.danger {
  color: var(--lm-danger-text);
}
</style>
