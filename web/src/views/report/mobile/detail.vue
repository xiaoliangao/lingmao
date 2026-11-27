<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { orderApi, orderChangeApi } from '@/api/order'
import { productionApi } from '@/api/production'
import { purchaseApi } from '@/api/purchase'
import ApprovalPanel from '@/components/doc/ApprovalPanel.vue'
import { nextApprover, runDocAction, showSilentError } from '@/components/doc/docUtils'
import StatusTag from '@/components/StatusTag.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import type { ApprovalView, Attachment, BizType, DocAction, DocActionReq, StatusTone } from '@/types/biz'
import { CHANGE_STATUS, CHANGE_TYPE, ORDER_STATUS } from '@/types/order'
import { PRODUCTION_KIND, PRODUCTION_STATUS } from '@/types/production'
import { PURCHASE_STATUS, PURCHASE_TYPE_LABEL } from '@/types/purchase'
import { docRoute } from '@/utils/docRoute'
import { ApiError, type RequestOptions } from '@/utils/request'
import { formatDate, formatMoney, formatQty } from '@/utils/format'

/**
 * 手机待办的单据页（/mobile-todo/:id?type=ORDER，RFC 019 §4）：看要点、直接审核或驳回。
 * 能在手机上审的四种：需求订单（要填承诺交期）、采购单、生产单（外发加工、成品采购）、订单变更单；
 * 能不能审照详情接口的 actions（后端按状态、权限、轮没轮到我算好），不自己判断。
 * 别的种类（资料包、出入库、盘点、供应商准入……）只说一句"到电脑上处理"，给电脑版的链接。
 * 评审信息（缺料、产能）只在电脑上看；手机上审需求订单，后端照样校验
 */
type PhoneType = 'ORDER' | 'ORDER_CHANGE' | 'PURCHASE_ORDER' | 'PRODUCTION_ORDER'
const PHONE_TYPES: PhoneType[] = ['ORDER', 'ORDER_CHANGE', 'PURCHASE_ORDER', 'PRODUCTION_ORDER']

/** 页面上要显示的，四种单据各自换成这个样子 */
interface View {
  typeLabel: string
  no: string
  statusLabel: string
  tone: StatusTone
  status: string
  version: number
  actions: string[]
  approval: ApprovalView | null
  image: Attachment | null
  fields: [string, string][]
  linesTitle: string
  lines: { main: string; sub?: string; right?: string }[]
  /** 需求订单审核要填的承诺交期的默认值 */
  commitDate?: string
}

const route = useRoute()
const router = useRouter()
const id = String(route.params.id)
const type = String(route.query.type ?? '') as BizType
const phone = PHONE_TYPES.includes(type as PhoneType)

const view = ref<View | null>(null)
const loading = ref(false)
const missing = ref<string | null>(null)
const busy = ref(false)
const commitDate = ref('')

const desktopUrl = computed(() =>
  docRoute({ type, id, kind: (route.query.kind as string | undefined) ?? null, parentId: (route.query.parentId as string | undefined) ?? null }),
)

const reader = useLatestRequest()
async function load() {
  if (!phone) return
  const read = reader.begin()
  loading.value = true
  try {
    const v = await read.run(() => fetchView())
    if (!read.current()) return
    view.value = v
    commitDate.value = v.commitDate ?? ''
    missing.value = null
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && (e.code === 404 || e.code === 403)) missing.value = e.code === 404 ? '这张单已经删了' : '你没有看这张单的权限'
    else throw e
  } finally {
    if (read.current()) loading.value = false
  }
}
onMounted(load)

async function fetchView(): Promise<View> {
  switch (type as PhoneType) {
    case 'ORDER': {
      const d = await orderApi.get(id)
      return {
        typeLabel: '需求订单', no: d.no, status: d.status, ...tone(ORDER_STATUS[d.status]), version: d.version, actions: d.actions,
        approval: d.approval, image: d.styles[0]?.image ?? null,
        fields: [
          ['业务员', d.salesName ?? '—'], ['跟单', d.merchName ?? '—'], ['要求交期', formatDate(d.requiredDate)],
          ['件数', `${formatQty(d.totalQty)} 件`], ...money('金额', d.totalAmount, d.currency), ['制单', d.createByName ?? '—'],
          ...(d.remark ? [['备注', d.remark] as [string, string]] : []),
        ],
        linesTitle: '款式',
        lines: d.styles.map((s) => ({ main: `${s.styleCode} ${s.styleName}`, right: `${formatQty(s.qty)} 件` })),
        commitDate: d.commitDate ?? d.requiredDate,
      }
    }
    case 'ORDER_CHANGE': {
      const d = await orderChangeApi.get(id)
      return {
        typeLabel: '订单变更单', no: d.no, status: d.status, ...tone(CHANGE_STATUS[d.status]), version: d.version, actions: d.actions,
        approval: d.approval, image: null,
        fields: [
          ['订单', d.orderNo], ['变更', d.changeTypes.map((t) => CHANGE_TYPE[t]).join('、')], ['原因', d.reason],
          ['提交', d.createByName ?? '—'],
        ],
        linesTitle: '改了什么',
        lines: d.cancel ? [{ main: '整单取消' }] : d.items.map((i) => ({ main: i.text })),
      }
    }
    case 'PURCHASE_ORDER': {
      const d = await purchaseApi.get(id)
      return {
        typeLabel: '采购单', no: d.no, status: d.status, ...tone(PURCHASE_STATUS[d.status]), version: d.version, actions: d.actions,
        approval: d.approval, image: d.items[0]?.image ?? null,
        fields: [
          ['供应商', d.supplierName], ['类型', PURCHASE_TYPE_LABEL[d.purchaseType]], ['采购员', d.buyerName], ['交期', formatDate(d.deliveryDate)],
          ...money('金额', d.totalAmount, 'CNY'), ['收货仓', d.warehouseName],
          ...(d.remark ? [['备注', d.remark] as [string, string]] : []),
        ],
        linesTitle: '物料',
        lines: d.items.map((i) => ({
          main: [i.materialName, i.colorName].filter(Boolean).join(' · '),
          sub: i.spec ?? undefined,
          right: `${formatQty(i.qty)} ${i.purchaseUnit}${i.amount == null ? '' : ` · ¥${formatMoney(i.amount)}`}`,
        })),
      }
    }
    case 'PRODUCTION_ORDER': {
      const d = await productionApi.get(id)
      const byColor = new Map<string, number>()
      for (const i of d.items) byColor.set(i.colorName, (byColor.get(i.colorName) ?? 0) + Number(i.qty))
      return {
        typeLabel: PRODUCTION_KIND[d.kind].label, no: d.no, status: d.status, ...tone(PRODUCTION_STATUS[d.status]), version: d.version,
        actions: d.actions, approval: d.approval, image: d.image,
        fields: [
          ['款式', `${d.styleCode} ${d.styleName}`], ['工厂', d.factoryName], ['订单', d.orderNo], ['交期', formatDate(d.dueDate)],
          ['件数', `${formatQty(d.totalQty)} 件`],
          ...(d.unitPrice == null ? [] : [[PRODUCTION_KIND[d.kind].priceLabel, `¥${formatMoney(d.unitPrice)}`] as [string, string]]),
          ...money('金额', d.totalAmount, 'CNY'),
          ...(d.priceChangeReason ? [['改价原因', d.priceChangeReason] as [string, string]] : []),
          ['跟单', d.merchName ?? '—'],
        ],
        linesTitle: '颜色',
        lines: [...byColor].map(([color, qty]) => ({ main: color, right: `${formatQty(qty)} 件` })),
      }
    }
  }
}

function tone(s: { label: string; tone: StatusTone } | undefined) {
  return { statusLabel: s?.label ?? '', tone: s?.tone ?? ('off' as StatusTone) }
}

/** 看不到金额的人后端不给（字段没有），就不显示这一行 */
function money(label: string, amount: number | null | undefined, currency: string): [string, string][] {
  if (amount == null) return []
  return [[label, `${currency === 'CNY' ? '¥' : `${currency} `}${formatMoney(amount)}`]]
}

const canAudit = computed(() => view.value?.actions.includes('audit') ?? false)
const canReject = computed(() => view.value?.actions.includes('reject') ?? false)

function exec(action: DocAction, body: DocActionReq, options: RequestOptions) {
  switch (type as PhoneType) {
    case 'ORDER': return orderApi.act(id, action, body, options)
    case 'ORDER_CHANGE': return orderChangeApi.act(id, action, body, options)
    case 'PURCHASE_ORDER': return purchaseApi.act(id, action, body, options)
    case 'PRODUCTION_ORDER': return productionApi.act(id, action, body, options)
  }
}

async function act(action: 'audit' | 'reject') {
  const v = view.value
  if (!v || busy.value) return
  busy.value = true
  try {
    let ok: boolean
    if (action === 'audit' && type === 'ORDER') ok = await auditOrder(v)
    else ok = await runDocAction(action, `${v.typeLabel} ${v.no}`, v.version, exec, {
      reload: load, approval: v.approval, note: action === 'audit' ? '确定审核通过？' : undefined,
    })
    if (ok) await load()
  } finally {
    busy.value = false
  }
}

/** 需求订单审核要带承诺交期（orderApi.audit），不走通用的 act */
async function auditOrder(v: View): Promise<boolean> {
  if (!commitDate.value) {
    ElMessage.warning('填承诺交期')
    return false
  }
  const next = nextApprover(v.approval)
  try {
    // 手机上单手容易点错：和其他三种单据一样先确认
    await ElMessageBox.confirm(`承诺交期 ${formatDate(commitDate.value)}，确定审核通过？`, `审核 ${v.typeLabel} ${v.no}`, {
      confirmButtonText: '审核', cancelButtonText: '取消',
    })
  } catch {
    return false
  }
  try {
    await orderApi.audit(id, { version: v.version, commitDate: commitDate.value }, { silent: true })
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    void load()
    return false
  }
  ElMessage.success(next ? `这一级审过了，等${next}审` : `${v.no} 已审核`)
  return true
}

function back() {
  if (window.history.state?.back) router.back()
  else void router.push('/mobile-todo')
}
</script>

<template>
  <div v-loading="loading && !view" class="m-shell">
    <header class="m-top">
      <button type="button" class="back-btn" @click="back">‹ 待办</button>
    </header>

    <div v-if="!phone" class="m-empty">
      <p>这种单据要在电脑上处理</p>
      <a v-if="desktopUrl" class="full-btn" :href="desktopUrl">打开电脑版</a>
    </div>

    <div v-else-if="missing" class="m-empty">
      <p>{{ missing }}</p>
      <button type="button" class="full-btn" @click="router.push('/mobile-todo')">回待办</button>
    </div>

    <template v-else-if="view">
      <section class="m-head">
        <ThumbImage v-if="view.image" :src="view.image.thumbUrl" alt="" class="pic" />
        <div class="head-info">
          <span class="dim">{{ view.typeLabel }}</span>
          <b class="code">{{ view.no }}</b>
          <StatusTag :tone="view.tone">{{ view.statusLabel }}</StatusTag>
        </div>
      </section>

      <dl class="m-fields">
        <template v-for="[k, val] in view.fields" :key="k">
          <dt>{{ k }}</dt>
          <dd>{{ val }}</dd>
        </template>
      </dl>

      <section v-if="view.lines.length" class="m-lines">
        <h2>{{ view.linesTitle }}</h2>
        <div v-for="(l, i) in view.lines" :key="i" class="line">
          <span class="main">
            {{ l.main }}
            <small v-if="l.sub" class="dim">{{ l.sub }}</small>
          </span>
          <span v-if="l.right" class="num">{{ l.right }}</span>
        </div>
      </section>

      <ApprovalPanel :approval="view.approval" :status="view.status" class="m-approval" />

      <a v-if="desktopUrl" class="desk-link" :href="desktopUrl">在电脑版看完整的单据</a>

      <div v-if="canAudit || canReject" class="m-actions">
        <label v-if="canAudit && type === 'ORDER'" class="commit">
          承诺交期
          <input v-model="commitDate" type="date" />
        </label>
        <div class="btns">
          <button v-if="canReject" type="button" class="btn reject" :disabled="busy" @click="act('reject')">驳回</button>
          <button v-if="canAudit" type="button" class="btn pass" :disabled="busy" @click="act('audit')">审核通过</button>
        </div>
      </div>
      <p v-else class="m-done dim">{{ view.status === 'PENDING' ? `等${view.approval?.waitingFor ?? '别人'}审` : '这张单不用你审了' }}</p>
    </template>
  </div>
</template>

<style scoped>
.m-shell {
  max-width: 560px;
  min-height: 100%;
  padding: 10px 14px 120px;
  margin: 0 auto;
  background: var(--lm-greige-2);
}
.m-top {
  margin-bottom: 8px;
}
.back-btn {
  min-height: 44px;
  padding: 0 4px;
  font: inherit;
  font-size: 14px;
  color: var(--lm-ink);
  cursor: pointer;
  background: transparent;
  border: 0;
}
.m-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 64px 16px;
  font-size: 14px;
  color: var(--lm-muted);
  text-align: center;
}
.full-btn {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 0 18px;
  font: inherit;
  color: var(--lm-ink);
  text-decoration: none;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}
.m-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  margin-bottom: 10px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}
.pic {
  flex: none;
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: var(--lm-radius);
}
.head-info {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}
.head-info .dim {
  width: 100%;
  font-size: 12px;
}
.head-info b {
  font-size: 16px;
}
.m-fields {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px 10px;
  padding: 12px;
  margin: 0 0 10px;
  font-size: 13px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}
.m-fields dt {
  color: var(--lm-muted);
}
.m-fields dd {
  margin: 0;
  word-break: break-all;
}
.m-lines {
  padding: 12px;
  margin-bottom: 10px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}
.m-lines h2 {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}
.line {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  border-top: 1px solid var(--lm-line-soft);
}
.line:first-of-type {
  border-top: 0;
}
.line .main {
  min-width: 0;
}
.line small {
  display: block;
  font-size: 12px;
}
.line .num {
  flex: none;
}
.m-approval {
  margin-bottom: 10px;
}
.desk-link {
  display: block;
  padding: 8px 0;
  font-size: 13px;
  color: var(--lm-ink-2);
  text-align: center;
}
.m-actions {
  position: fixed;
  right: 0;
  bottom: 0;
  left: 0;
  max-width: 560px;
  padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  margin: 0 auto;
  background: var(--lm-paper);
  border-top: 1px solid var(--lm-line);
}
.commit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}
.commit input {
  min-height: 36px;
  padding: 0 8px;
  font: inherit;
  color: var(--lm-ink);
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}
.btns {
  display: flex;
  gap: 10px;
}
.btn {
  flex: 1;
  min-height: 46px;
  font: inherit;
  font-size: 15px;
  cursor: pointer;
  border-radius: var(--lm-radius);
}
.btn.reject {
  color: var(--lm-danger-text);
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
}
.btn.pass {
  font-weight: 600;
  color: var(--lm-on-accent);
  background: var(--lm-accent);
  border: 1px solid var(--lm-accent);
}
.btn:disabled {
  opacity: 0.6;
}
.m-done {
  padding: 12px 0;
  font-size: 13px;
  text-align: center;
}
</style>
