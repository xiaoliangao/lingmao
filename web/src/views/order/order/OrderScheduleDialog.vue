<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '@/api/order'
import DictSelect from '@/components/DictSelect.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useDict } from '@/composables/useDict'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { PROCESS_MODE_FULL, type ScheduleBlock, type SchedulePrefill, type ScheduleQuote, type ScheduleStyle } from '@/types/order'
import { formatMoney, formatPrice } from '@/utils/format'
import { orNull } from '@/views/basic/common'

/**
 * 排单（docs/api/02 §3.12、§4.5，原型 OrderSchedule）：一个款一块 = 一张生产单草稿；"再加一个工厂"同款再加一块（件数默认 0）。
 * - 单价跟着"加工厂 + 加工方式"走：换工厂重新取现价（quote），换加工方式用 quote 里那种方式的现价；
 *   档案里没有就空着要手填，不沿用上一种方式的价。单价和带出的现价不一样时"改价原因"必填
 * - 每格上限 maxQty；同款几块加起来超了，几块里那一格都标红（SkuMatrix 的 error），不截断，后端还会拦 1004
 * - 成功：提示生成了哪几张，发 scheduled，详情切到"下游单据"页签
 *
 *   <OrderScheduleDialog v-model="scheduleVisible" :order-id="id" @scheduled="onScheduled" />
 */
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ orderId: number }>()
const emit = defineEmits<{ scheduled: [] }>()

const modes = useDict('process_mode')
const termsDict = useDict('prod_terms')

interface BlockDraft {
  uid: number
  style: ScheduleStyle
  factoryId: number | null
  factoryName: string | null
  processMode: string
  unitPrice: number | null
  priceChangeReason: string
  dueDate: string
  merchId: number | null
  merchName: string | null
  settleMethod: string | null
  terms: string
  remark: string
  quote: ScheduleQuote | null
  quoting: boolean
  /** 订单行 id → 件数 */
  qtys: Record<number, number>
}

let uid = 0
const loading = ref(false)
const submitting = ref(false)
const prefill = ref<SchedulePrefill | null>(null)
const blocks = ref<BlockDraft[]>([])
/** 只比较用户填的部分（quote、quoting 是带出来的） */
const guard = useUnsavedChanges(
  () => blocks.value.map(({ quote: _q, quoting: _x, style, ...rest }) => ({ styleId: style.styleId, ...rest })),
  visible,
)

function makeBlock(st: ScheduleStyle, pf: SchedulePrefill, fill: boolean): BlockDraft {
  return {
    uid: ++uid,
    style: st,
    factoryId: st.defaultFactoryId,
    factoryName: st.defaultFactoryName,
    processMode: '',
    unitPrice: null,
    priceChangeReason: '',
    dueDate: pf.defaultDueDate,
    merchId: pf.merchId,
    merchName: pf.merchName,
    settleMethod: null,
    terms: '',
    remark: '',
    quote: null,
    quoting: false,
    qtys: Object.fromEntries(st.skus.map((s) => [s.orderItemId, fill ? s.remainQty : 0])),
  }
}

/** 这块现在这种加工方式在档案里的现价；没有是 null */
const archiveOf = (b: BlockDraft) => b.quote?.prices.find((p) => p.processMode === b.processMode) ?? null
const priceChanged = (b: BlockDraft) => {
  const archive = archiveOf(b)?.price
  return archive != null && b.unitPrice != null && Math.abs(b.unitPrice - archive) > 0.00005
}

/** 换了加工方式（或刚取到现价）：单价换成这种方式的现价，没有就空着；改价原因清掉 */
function applyModePrice(b: BlockDraft) {
  b.unitPrice = archiveOf(b)?.price ?? null
  b.priceChangeReason = ''
}

/** 每块的取价序号：连着换两次工厂，先发的后回来不能盖掉后换的 */
const quoteSeq = new Map<number, number>()
async function requote(b: BlockDraft, keepMode: boolean) {
  const seq = (quoteSeq.get(b.uid) ?? 0) + 1
  quoteSeq.set(b.uid, seq)
  if (!b.factoryId) {
    b.quote = null
    applyModePrice(b)
    return
  }
  b.quoting = true
  try {
    const q = await scheduleApi.quote(props.orderId, b.style.styleId, b.factoryId)
    if (quoteSeq.get(b.uid) !== seq) return
    b.quote = q
    b.factoryName = q.factoryName
    b.settleMethod = q.settleMethod
    if (!keepMode || !b.processMode) b.processMode = q.defaultMode
    applyModePrice(b)
  } catch {
    if (quoteSeq.get(b.uid) === seq) {
      b.quote = null
      applyModePrice(b)
    }
  } finally {
    if (quoteSeq.get(b.uid) === seq) b.quoting = false
  }
}

function onFactoryChange(b: BlockDraft, factoryId: number | null | undefined) {
  b.factoryId = factoryId ?? null
  void requote(b, true)
}

watch(visible, async (on) => {
  if (!on) return
  blocks.value = []
  prefill.value = null
  loading.value = true
  try {
    const pf = await scheduleApi.prefill(props.orderId)
    prefill.value = pf
    blocks.value = pf.styles.map((st) => makeBlock(st, pf, true))
    await Promise.all(blocks.value.map((b) => requote(b, false)))
    guard.markSaved()
  } finally {
    loading.value = false
  }
})

const sameStyleCount = (b: BlockDraft) => blocks.value.filter((x) => x.style.styleId === b.style.styleId).length

function addFactory(b: BlockDraft) {
  if (!prefill.value) return
  const idx = blocks.value.indexOf(b)
  const extra = makeBlock(b.style, prefill.value, false)
  extra.factoryId = null
  extra.factoryName = null
  blocks.value.splice(idx + 1, 0, extra)
}

// ---------- 矩阵：格子 id 是订单行 id ----------
const cellsOf = (st: ScheduleStyle) => st.skus.map((s) => ({ id: s.orderItemId, colorId: s.colorId, sizeCode: s.sizeCode }))
const maxMap = computed(() => new Map(blocks.value.flatMap((b) => b.style.skus.map((s) => [s.orderItemId, s.maxQty] as const))))
const maxOf = (itemId: number) => maxMap.value.get(itemId)

/** 同一个订单行在几块里加起来 */
const sumOf = (itemId: number) => blocks.value.reduce((sum, b) => sum + (b.qtys[itemId] ?? 0), 0)
function crossBlockError(itemId: number): string | null {
  const max = maxOf(itemId)
  const sum = sumOf(itemId)
  return max != null && sum > max && blocks.value.filter((b) => (b.qtys[itemId] ?? 0) > 0).length > 1
    ? `几块加起来 ${sum} 件，最多还能排 ${max}`
    : null
}
const remainSub = (itemId: number) => {
  const sku = blocks.value.flatMap((b) => b.style.skus).find((s) => s.orderItemId === itemId)
  return sku ? `余 ${sku.remainQty}` : null
}

const blockQty = (b: BlockDraft) => Object.values(b.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0)
const activeBlocks = computed(() => blocks.value.filter((b) => blockQty(b) > 0))
const footText = computed(() => {
  const n = activeBlocks.value.length
  if (!n) return '还没填件数'
  const buy = activeBlocks.value.filter((b) => b.processMode === PROCESS_MODE_FULL).length
  return `生成 ${n} 张生产单：外发加工单 ${n - buy} 张、成品采购单 ${buy} 张`
})

function addTerm(b: BlockDraft, value: string) {
  const text = termsDict.options.value.find((o) => o.value === value)?.label
  if (text) b.terms = b.terms ? `${b.terms}\n${text}` : text
}

function buildBlocks(): ScheduleBlock[] | null {
  const out: ScheduleBlock[] = []
  for (const b of activeBlocks.value) {
    const name = b.style.styleCode
    if (!b.factoryId) return warn(`${name}：选加工厂`)
    if (b.quote && !b.quote.usable) return warn(`${b.quote.factoryName}：${b.quote.reason ?? '不能下大货单'}`)
    if (!b.processMode) return warn(`${name}：选加工方式`)
    if (b.unitPrice == null) return warn(`${name}：填单价`)
    if (priceChanged(b) && !b.priceChangeReason.trim()) return warn(`${name}：单价和档案现价不一样，写改价原因`)
    if (!b.dueDate || !b.merchId) return warn(`${name}：填交期、生产跟单`)
    out.push({
      styleId: b.style.styleId,
      factoryId: b.factoryId,
      processMode: b.processMode,
      unitPrice: b.unitPrice,
      priceSourceId: priceChanged(b) ? null : (archiveOf(b)?.priceSourceId ?? null),
      priceChangeReason: priceChanged(b) ? b.priceChangeReason.trim() : null,
      dueDate: b.dueDate,
      merchId: b.merchId,
      settleMethod: b.settleMethod,
      terms: orNull(b.terms),
      remark: orNull(b.remark),
      items: Object.entries(b.qtys)
        .map(([itemId, qty]) => ({ orderItemId: Number(itemId), qty }))
        .filter((x) => x.qty > 0),
    })
  }
  return out
}

function warn(message: string): null {
  ElMessage.warning(message)
  return null
}

async function submit() {
  if (!activeBlocks.value.length) {
    ElMessage.warning('至少填一格件数')
    return
  }
  const payload = buildBlocks()
  if (!payload) return
  submitting.value = true
  try {
    const created = await scheduleApi.schedule(props.orderId, payload)
    ElMessage.success(`生成了 ${created.length} 张生产单草稿：${created.map((c) => c.no).join('、')}，去生产单里核对后提交`)
    guard.markSaved()
    visible.value = false
    emit('scheduled')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`排单 ${prefill?.orderNo ?? ''}`" width="840px" :before-close="guard.beforeClose">
    <div v-loading="loading" class="blocks">
      <p v-if="!loading && prefill && !blocks.length" class="dim">每个款都排完了</p>
      <section v-for="(b, idx) in blocks" :key="b.uid" class="block">
        <div class="block-head">
          <span class="code">{{ b.style.styleCode }}</span>
          <strong>{{ b.style.styleName }}</strong>
          <span class="dim">资料包 V{{ b.style.techPackVersion }}</span>
          <span class="dim">→ {{ b.processMode === PROCESS_MODE_FULL ? '成品采购单' : '外发加工单' }}</span>
          <span class="gap" />
          <el-button size="small" icon="Plus" @click="addFactory(b)">再加一个工厂</el-button>
          <el-button v-if="sameStyleCount(b) > 1" link type="danger" size="small" @click="blocks.splice(idx, 1)">
            去掉这块
          </el-button>
        </div>

        <el-form label-position="top" class="form-grid">
          <el-form-item label="加工厂" required :error="b.quote && !b.quote.usable ? (b.quote.reason ?? '不能下大货单') : ''">
            <SupplierSelect
              :model-value="b.factoryId"
              :label="b.factoryName"
              :category="['GARMENT', 'PROCESSOR']"
              usage="BULK"
              @update:model-value="onFactoryChange(b, $event)"
            />
          </el-form-item>
          <el-form-item label="加工方式" required>
            <el-select v-model="b.processMode" filterable @change="applyModePrice(b)">
              <el-option v-for="m in modes.enabled.value" :key="m.value" :label="m.label" :value="m.value" />
            </el-select>
          </el-form-item>
          <el-form-item :label="b.processMode === PROCESS_MODE_FULL ? '成品单价（元/件）' : '加工费（元/件）'" required>
            <el-input-number v-model="b.unitPrice" :min="0" :precision="2" :controls="false" :disabled="b.quoting" />
            <span class="field-tip dim">
              <template v-if="archiveOf(b)?.price != null">档案现价 {{ formatPrice(archiveOf(b)!.price) }}</template>
              <template v-else-if="b.factoryId && !b.quoting">档案里没有这家这种方式的价</template>
              <template v-if="b.processMode !== PROCESS_MODE_FULL"> · 工价合计 {{ formatMoney(b.style.laborTotal) }}</template>
            </span>
          </el-form-item>
          <el-form-item v-if="priceChanged(b)" label="改价原因" required>
            <el-input v-model="b.priceChangeReason" maxlength="100" placeholder="单价和档案现价不一样：写为什么" />
          </el-form-item>
          <el-form-item label="交期" required>
            <el-date-picker v-model="b.dueDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
          </el-form-item>
          <el-form-item label="生产跟单" required>
            <UserSelect v-model="b.merchId" :label="b.merchName" />
          </el-form-item>
          <el-form-item label="结算方式">
            <DictSelect v-model="b.settleMethod" type="settle_method" clearable placeholder="按工厂档案" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="b.remark" maxlength="200" />
          </el-form-item>
          <el-form-item label="条款" class="full">
            <el-input v-model="b.terms" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" maxlength="500" />
            <el-select v-if="termsDict.enabled.value.length" class="terms-pick" size="small" filterable placeholder="填常用条款" :model-value="''" @change="addTerm(b, $event)">
              <el-option v-for="t in termsDict.enabled.value" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
        </el-form>

        <SkuMatrix v-model="b.qtys" :sizes="b.style.sizes" :colors="b.style.colors" :skus="cellsOf(b.style)" :max="maxOf" :error="crossBlockError" :sub="remainSub" />
      </section>
    </div>

    <template #footer>
      <span class="foot-text dim">{{ footText }}</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!activeBlocks.length" @click="submit">生成生产单草稿</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.blocks {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 80px;
}

.block {
  padding: 14px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.block-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
}

.gap {
  flex: 1;
}

.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor),
.form-grid :deep(.el-input-number) {
  width: 100%;
}

.field-tip {
  font-size: 11.5px;
  line-height: 1.5;
}

.terms-pick {
  width: 180px !important;
  margin-top: 6px;
}

.foot-text {
  margin-right: 12px;
  font-size: 12.5px;
}
</style>
