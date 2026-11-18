<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi, orderChangeApi } from '@/api/order'
import ImageUpload from '@/components/ImageUpload.vue'
import SkuMatrix, { type MatrixColor } from '@/components/SkuMatrix.vue'
import { reloadOnConflict, showSilentError } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { DocRef } from '@/types/biz'
import {
  RESPONSIBLE_PARTY,
  type ChangeTechPackForm,
  type OrderChangeDetail,
  type OrderChangeForm,
  type OrderDetail,
  type OrderStyleOption,
  type ResponsibleParty,
} from '@/types/order'
import type { FileVO } from '@/types/system'
import { formatDate, formatPrice } from '@/utils/format'
import { MAX_UNIT_PRICE, validateOrderCapacity } from '@/utils/orderSku'
import { ApiError } from '@/utils/request'
import { orNull } from '@/views/basic/common'

/**
 * 提变更 / 改变更单（docs/api/02 §3.14、§4.6，原型 OrderChange）。
 * - 发的是"改完以后的样子"：矩阵每一格的新件数（没改的也发）、要改单价的款的新单价、要换资料包的款、新的要求交期（不改发 null）
 * - 每个款：矩阵初始是订单现在的件数，小字"原 120 · 已排 80"，新加的颜色写"新加"；新件数小于已排产的格子标红。
 *   "加颜色"只列款上能下、订单里还没有的颜色；订单里原有的颜色照样能改（减数、改成 0）。停用的 SKU 订单里没有的格子灰掉
 * - 资料包只能在"现在锁的版本"和"现在生效的版本"两个里选
 * - 勾"取消整张订单"：隐藏矩阵和交期，查下游单据，有的话列出来、"提交变更"灰掉（后端也会拦 1005）
 * - 新建：create 成功后草稿就有了，接着提交；提交没过也去变更单详情（原因弹出来），不留在对话框里再建一张。
 *   改被驳回的（editing 传变更单详情）：标题"改变更单 BG…"，按钮"重新提交"（update + submit）
 *
 *   <OrderChangeDialog v-model="changeVisible" :order="detail" :default-cancel="false" @saved="refresh" />
 *   <OrderChangeDialog v-model="editVisible" :order="order" :editing="change" @saved="refresh" />
 */
const visible = defineModel<boolean>({ required: true })
const props = withDefaults(defineProps<{ order: OrderDetail; defaultCancel?: boolean; editing?: OrderChangeDetail | null }>(), {
  defaultCancel: false,
  editing: null,
})
const emit = defineEmits<{ saved: [changeId: number] }>()

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const styleOpts = ref<OrderStyleOption[]>([])
const downstream = ref<DocRef[] | null>(null)

const form = reactive({
  cancel: false,
  requiredDate: '',
  responsibleParty: 'SELF' as ResponsibleParty,
  reason: '',
  feeNote: '',
  files: [] as FileVO[],
  /** 款式 SKU id → 新件数 */
  qtys: {} as Record<number, number>,
  /** 款 id → 新单价 */
  prices: {} as Record<number, number | null>,
  priceChanges: {} as Record<number, boolean>,
  /** 款 id → 资料包 id */
  packs: {} as Record<number, number | null>,
  /** 款 id → 新加的颜色 id */
  addedColors: {} as Record<number, number[]>,
})
const guard = useUnsavedChanges(form, visible)

/** TRADE 关：责任方不列"客户"（02 §4.7） */
const PARTY_OPTIONS = (['SELF', 'FACTORY', 'SUPPLIER'] as ResponsibleParty[]).map((value) => ({ value, label: RESPONSIBLE_PARTY[value] }))

const itemBySku = computed(() => new Map(props.order.items.map((i) => [i.styleSkuId, i])))
const styleOf = (styleId: number) => props.order.styles.find((s) => s.styleId === styleId)
const mixedPrice = (styleId: number) => new Set(props.order.items.filter((i) => i.styleId === styleId).map((i) => i.price)).size > 1
const origPrice = (styleId: number) => mixedPrice(styleId) ? null : props.order.items.find((i) => i.styleId === styleId)?.price ?? null

/** 矩阵的行：订单里原有的颜色（能改）+ 新加的颜色 */
function rowsOf(opt: OrderStyleOption): MatrixColor[] {
  const inOrder = new Set(props.order.items.filter((i) => i.styleId === opt.styleId).map((i) => i.colorId))
  const added = new Set(form.addedColors[opt.styleId] ?? [])
  return opt.colors
    .filter((c) => inOrder.has(c.id) || added.has(c.id))
    .map((c) => ({ ...c, orderable: true, reason: null }))
}

/** 格子：订单里已有的 SKU 都能改；没有的 SKU 停用了就灰掉 */
const cellsOf = (opt: OrderStyleOption) =>
  opt.skus.map((s) => ({ id: s.id, colorId: s.colorId, sizeCode: s.sizeCode, status: itemBySku.value.has(s.id) ? 1 : s.status }))

/** "加颜色"下拉：款上能下、订单里还没有的颜色 */
function addableColors(opt: OrderStyleOption) {
  const shown = new Set(rowsOf(opt).map((c) => c.id))
  return opt.colors.filter((c) => c.orderable && !shown.has(c.id))
}

function addColor(opt: OrderStyleOption, colorId: number) {
  form.addedColors[opt.styleId] = [...(form.addedColors[opt.styleId] ?? []), colorId]
}

function subOf(skuId: number) {
  const item = itemBySku.value.get(skuId)
  return item ? `原 ${item.qty} · 已排 ${item.scheduledQty}` : '新加'
}

function belowScheduled(skuId: number, qty: number) {
  const item = itemBySku.value.get(skuId)
  return item && qty < item.scheduledQty ? `已排 ${item.scheduledQty}` : null
}

/** 资料包两个选项：现在锁的、现在生效的（一样时只有一个） */
function packOptions(styleId: number) {
  const st = styleOf(styleId)
  if (!st) return []
  const opts: { id: number; label: string }[] = []
  if (st.techPackId != null) opts.push({ id: st.techPackId, label: `V${st.techPackVersion}（现在锁的）` })
  if (st.currentPackId != null && st.currentPackId !== st.techPackId) opts.push({ id: st.currentPackId, label: `V${st.currentPackVersion}（现在生效的）` })
  return opts
}

watch(visible, async (on) => {
  if (!on) return
  loading.value = true
  downstream.value = null
  try {
    styleOpts.value = props.order.styles.length ? await orderApi.styleOptions(props.order.styles.map((s) => s.styleId)) : []
    resetForm()
    if (form.cancel) await loadDownstream()
    guard.markSaved()
  } finally {
    loading.value = false
  }
})

/** 订单现在的样子；改变更单时再把它的"新"值放上去 */
function resetForm() {
  const ch = props.editing
  Object.assign(form, {
    cancel: ch ? ch.cancel : props.defaultCancel,
    requiredDate: props.order.requiredDate,
    responsibleParty: ch?.responsibleParty ?? 'SELF',
    reason: ch?.reason ?? '',
    feeNote: ch?.feeNote ?? '',
    files: [],
    qtys: Object.fromEntries(props.order.items.map((i) => [i.styleSkuId, i.qty])),
    prices: Object.fromEntries(props.order.styles.map((s) => [s.styleId, origPrice(s.styleId)])),
    priceChanges: {},
    packs: Object.fromEntries(props.order.styles.map((s) => [s.styleId, s.techPackId])),
    addedColors: {},
  })
  if (!ch) return
  const skuColor = new Map(styleOpts.value.flatMap((o) => o.skus.map((s) => [s.id, { styleId: o.styleId, colorId: s.colorId }] as const)))
  for (const it of ch.items) {
    if ((it.changeType === 'QTY' || it.changeType === 'COLOR_SIZE') && it.styleSkuId != null) {
      form.qtys[it.styleSkuId] = it.toQty ?? 0
      const hit = skuColor.get(it.styleSkuId)
      if (hit && !itemBySku.value.has(it.styleSkuId) && !(form.addedColors[hit.styleId] ?? []).includes(hit.colorId)) {
        form.addedColors[hit.styleId] = [...(form.addedColors[hit.styleId] ?? []), hit.colorId]
      }
    } else if (it.changeType === 'DUE' && it.toDate) form.requiredDate = it.toDate
    else if (it.changeType === 'PRICE' && it.styleId != null) { form.prices[it.styleId] = it.toPrice; form.priceChanges[it.styleId] = true }
    else if (it.changeType === 'TECH_PACK' && it.styleId != null) form.packs[it.styleId] = it.toTechPackId
  }
}

async function loadDownstream() {
  try {
    downstream.value = await orderApi.references(props.order.id)
  } catch {
    downstream.value = null
  }
}

watch(
  () => form.cancel,
  (cancel) => {
    if (cancel && visible.value && downstream.value === null) void loadDownstream()
  },
)

const cancelBlocked = computed(() => form.cancel && (downstream.value?.length ?? 0) > 0)

function buildForm(): OrderChangeForm | null {
  if (!form.reason.trim()) {
    ElMessage.warning('写变更原因')
    return null
  }
  if (form.cancel) {
    return { cancel: true, reason: form.reason.trim(), responsibleParty: form.responsibleParty, feeNote: orNull(form.feeNote), requiredDate: null, quantities: [], prices: [], techPacks: [] }
  }
  const quantities = styleOpts.value.flatMap((opt) =>
    opt.skus
      .filter((s) => itemBySku.value.has(s.id) || (form.qtys[s.id] ?? 0) > 0)
      .map((s) => ({ styleSkuId: s.id, qty: form.qtys[s.id] ?? 0 })),
  )
  const prices = props.order.styles.flatMap((s) => {
    const next = form.prices[s.styleId]
    return form.priceChanges[s.styleId] && next != null ? [{ styleId: s.styleId, price: next }] : []
  })
  if (props.order.styles.some((style) => form.priceChanges[style.styleId] && form.prices[style.styleId] == null)) {
    ElMessage.warning('已勾选改单价的款要填写新单价'); return null
  }
  const capacityError = validateOrderCapacity(quantities.filter((row) => row.qty > 0).map((row) => {
    const original = itemBySku.value.get(row.styleSkuId)
    const styleId = original?.styleId ?? styleOpts.value.find((opt) => opt.skus.some((sku) => sku.id === row.styleSkuId))?.styleId
    const replacement = prices.find((price) => price.styleId === styleId)
    return { ...row, price: replacement?.price ?? original?.price ?? null, lineDueDate: original?.lineDueDate ?? null, remark: original?.remark ?? null }
  }))
  if (capacityError) { ElMessage.warning(capacityError); return null }
  const techPacks: ChangeTechPackForm[] = props.order.styles.flatMap((s) => {
    const next = form.packs[s.styleId]
    return next != null && next !== s.techPackId ? [{ styleId: s.styleId, techPackId: next }] : []
  })
  return {
    cancel: false,
    reason: form.reason.trim(),
    responsibleParty: form.responsibleParty,
    feeNote: orNull(form.feeNote),
    requiredDate: form.requiredDate !== props.order.requiredDate ? form.requiredDate : null,
    quantities,
    prices,
    techPacks,
  }
}

/** 提交：静默请求，1005（有下游单据）、检查没过弹出来。返回提交上了没有 */
async function submitChange(changeId: number, version: number): Promise<boolean> {
  try {
    await orderChangeApi.act(changeId, 'submit', { version }, { silent: true })
    return true
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    return false
  }
}

async function save() {
  if (!(await guard.waitForUploads())) return
  const body = buildForm()
  if (!body) return
  submitting.value = true
  try {
    const ch = props.editing
    let changeId: number
    let submitted: boolean
    if (ch) {
      const version = await orderChangeApi.update(ch.id, { ...body, version: ch.version })
      changeId = ch.id
      submitted = await submitChange(ch.id, version)
    } else {
      const saved = await orderChangeApi.create({ ...body, orderId: props.order.id, files: form.files })
      changeId = saved.id
      submitted = await submitChange(saved.id, saved.version)
    }
    guard.markSaved()
    visible.value = false
    emit('saved', changeId)
    if (submitted) ElMessage.success('变更提交了，等审核')
    if (!ch) void router.push(`/order-changes/${changeId}`)
  } catch (e) {
    reloadOnConflict(e, () => { guard.markSaved(); visible.value = false; if (props.editing) emit('saved', props.editing.id) })
    if (!(e instanceof ApiError)) throw e
  } finally {
    submitting.value = false
  }
}

const title = computed(() => (props.editing ? `改变更单 ${props.editing.no}` : `提变更 ${props.order.no}`))
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="840px" :before-close="guard.beforeClose">
    <div v-loading="loading" class="chg">
      <el-checkbox v-model="form.cancel">取消整张订单</el-checkbox>

      <template v-if="form.cancel">
        <div v-if="cancelBlocked" class="warn-bar">
          <p>先处理这些下游单据（作废或删除）再取消订单：</p>
          <ul>
            <li v-for="r in downstream" :key="`${r.type}-${r.id}`">{{ r.typeLabel }} <span class="code">{{ r.no }}</span> · {{ r.statusLabel }}</li>
          </ul>
        </div>
      </template>

      <template v-else>
        <section v-for="opt in styleOpts" :key="opt.styleId" class="style-block">
          <div class="st-head">
            <span class="code">{{ opt.code }}</span>
            <strong>{{ opt.name }}</strong>
            <span class="gap" />
            <label class="ctrl">
              <span class="dim">单价</span>
              <el-checkbox v-model="form.priceChanges[opt.styleId]">整款改单价</el-checkbox>
              <el-input-number v-model="form.prices[opt.styleId]" :disabled="!form.priceChanges[opt.styleId]" :max="MAX_UNIT_PRICE" size="small" :min="0" :precision="4" :controls="false" :placeholder="mixedPrice(opt.styleId) ? '多个值（保留原值）' : origPrice(opt.styleId) == null ? '' : formatPrice(origPrice(opt.styleId))" />
            </label>
            <label v-if="packOptions(opt.styleId).length > 1" class="ctrl">
              <span class="dim">资料包</span>
              <el-select v-model="form.packs[opt.styleId]" size="small" filterable class="pack-sel">
                <el-option v-for="p in packOptions(opt.styleId)" :key="p.id" :label="p.label" :value="p.id" />
              </el-select>
            </label>
            <el-select v-if="addableColors(opt).length" size="small" filterable placeholder="加颜色" class="add-color" :model-value="null" @change="addColor(opt, $event)">
              <el-option v-for="c in addableColors(opt)" :key="c.id" :label="`${c.code} ${c.name}`" :value="c.id" />
            </el-select>
          </div>
          <SkuMatrix v-model="form.qtys" :sizes="opt.sizes" :colors="rowsOf(opt)" :skus="cellsOf(opt)" :sub="subOf" :error="belowScheduled" />
        </section>
      </template>

      <el-form label-position="top" class="form-grid">
        <el-form-item v-if="!form.cancel" :label="`要求交期（原 ${formatDate(order.requiredDate)}）`">
          <el-date-picker v-model="form.requiredDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
        <el-form-item label="责任方" required>
          <el-select v-model="form.responsibleParty" filterable>
            <el-option v-for="p in PARTY_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因" required class="full">
          <el-input v-model="form.reason" type="textarea" :autosize="{ minRows: 2 }" maxlength="200" />
        </el-form-item>
        <el-form-item label="费用说明" class="full">
          <el-input v-model="form.feeNote" maxlength="160" placeholder="如：已裁片损失由我方承担" />
        </el-form-item>
        <el-form-item v-if="!editing" label="变更依据（审批截图、邮件、聊天记录）" class="full">
          <ImageUpload v-model="form.files" :max="9" />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="loading || cancelBlocked" @click="save">{{ editing ? '重新提交' : '提交变更' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.chg {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.style-block {
  padding: 12px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.st-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  font-size: 13px;
}

.gap {
  flex: 1;
}

.ctrl {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}

.ctrl :deep(.el-input-number) {
  width: 96px;
}

.pack-sel {
  width: 150px;
}

.add-color {
  width: 120px;
}

.warn-bar {
  padding: 10px 14px;
  font-size: 13px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.warn-bar p {
  margin: 0 0 4px;
}

.warn-bar ul {
  padding-left: 18px;
  margin: 0;
}

.form-grid :deep(.el-select),
.form-grid :deep(.el-date-editor) {
  width: 100%;
}
</style>
