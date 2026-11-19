<script setup lang="ts">
import ThumbImage from '@/components/ThumbImage.vue'
import { routeId } from '@/utils/entityId'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { orderApi as orderApiBase } from '@/api/order'
import BlankState from '@/components/BlankState.vue'
import DataPick from '@/components/DataPick.vue'
import DictSelect from '@/components/DictSelect.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import UserSelect from '@/components/UserSelect.vue'
import CompanySelect from '@/components/doc/CompanySelect.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import { addDaysStr, reloadOnConflict, showSilentError, todayStr } from '@/components/doc/docUtils'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useTabsStore } from '@/stores/tabs'
import { useUserStore } from '@/stores/user'
import type { MaterialPickRow } from '@/types/basic'
import type { OrderForm, OrderItemForm, OrderStyleOption } from '@/types/order'
import type { StylePickRow } from '@/types/style'
import type { FileVO } from '@/types/system'
import { formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { applySkuValues, buildSkuRows, hydrateSkuValues, lineAmountCents, MAX_UNIT_PRICE, validateOrderCapacity, type SkuValues } from '@/utils/orderSku'
import { orNull } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeOrderPrice } = useFieldAccess()
const orderApi = bindCompanyApi(orderApiBase, () => savedCompanyId.value ?? head.companyId)

/**
 * 需求订单整页表单（/orders/new、/orders/:id/edit，docs/api/02 §1.2、§3.5、§4.2）。
 * - 单头两列；主体空着 = 后端用我的默认主体（登录信息里没有默认主体，前端选不上）
 * - 一个款一块，逐 SKU 保存单价/交期/备注；整款与尺码输入仅在显式应用时修改对应 SKU。
 * - 新建页带 ?styleId= 先把这个款加进来（款式详情的"下订单"）；页签留着时再带别的款进来，加到后面
 * - 存草稿 / 提交审核：新建成功后不管提交过没过，这张草稿已经有了——提交没过就转到它的编辑页再弹原因，
 *   不留在"新建"上（不然再点一次又建一张）。编辑页保存后记下新的 version，提交没过留在页上接着改
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()

const path = route.path
const id = route.params.id ? routeId(route.params.id) : null
const editing = id !== null

interface StyleBlock {
  opt: OrderStyleOption
  unitPrice: number | null
  bySizePrice: boolean
  sizePrices: Record<string, number | null | undefined>
  lineDueDate: string | null
  remark: string
  /** 款式 SKU id → 件数 */
  qtys: Record<string, number>
  values: Record<string, SkuValues>
}

const loaded = ref(!editing)
const notFound = ref(false)
const loadError = ref<string | null>(null)
const saving = ref(false)
const version = ref(0)
const savedCompanyId = ref<string>()
const orderNo = ref('')

const head = reactive({
  companyId: user.defaultCompanyId,
  customerId: null as string | null,
  customerOrderNo: null as string | null,
  orderType: 'STOCK',
  brand: null as string | null,
  contractNo: '',
  orderDate: todayStr(),
  requiredDate: addDaysStr(todayStr(), 60),
  salesId: user.info?.id ?? null,
  merchId: user.info?.id ?? null,
  currency: 'CNY',
  deliveryReq: '',
  remark: '',
})
/** 编辑时业务员、跟单员的名字（UserSelect 还没搜过，不知道 id 叫什么） */
const names = reactive({ sales: user.info?.nickname ?? null, merch: user.info?.nickname ?? null })

const files = ref<FileVO[]>([])
const blocks = ref<StyleBlock[]>([])
const stylePicking = ref(false)
const pickedStyleIds = computed(() => blocks.value.map((b) => b.opt.styleId))
/** 品类线（RFC 013 §2）：一张订单只能放一条线的款；挑过款以后，选款式只列这条线的 */
const orderLine = ref<string | null>(null)
const pickParams = computed(() => (blocks.value.length && orderLine.value ? { productLine: orderLine.value } : undefined))

const guard = useUnsavedChanges(() => ({ head, files: files.value, blocks: blocks.value }))

const skuCells = (opt: OrderStyleOption) => opt.skus.map((s) => ({ id: s.id, colorId: s.colorId, sizeCode: s.sizeCode, status: s.status }))

function valuesOf(b: StyleBlock, skuId: string): SkuValues {
  return b.values[skuId] ??= { price: null, lineDueDate: null, remark: null }
}
function mixedValues(b: StyleBlock): boolean {
  const rows = buildSkuRows(b.opt.skus.map((s) => s.id), b.qtys, b.values)
  return (['price', 'lineDueDate', 'remark'] as const).some((key) => new Set(rows.map((r) => r[key])).size > 1)
}
function applyPrice(b: StyleBlock, size?: string) {
  const ids = b.opt.skus.filter((s) => !size || s.sizeCode === size).map((s) => s.id)
  applySkuValues(b.values, ids, { price: size ? b.sizePrices[size] ?? null : b.unitPrice })
}
function applyDetails(b: StyleBlock) {
  applySkuValues(b.values, b.opt.skus.map((s) => s.id), { lineDueDate: b.lineDueDate, remark: orNull(b.remark) })
}
function blockQty(b: StyleBlock): number {
  return Object.values(b.qtys).reduce((s, q) => s + (q > 0 ? q : 0), 0)
}

function blockAmount(b: StyleBlock): number {
  return b.opt.skus.reduce((sum, sk) => {
    const q = b.qtys[sk.id] ?? 0
    const p = b.values[sk.id]?.price
    return Number.isSafeInteger(q) && q > 0 && p != null && Number.isFinite(p) && p >= 0 && p <= MAX_UNIT_PRICE ? sum + Number(lineAmountCents(p, q)) / 100 : sum
  }, 0)
}

const totalQty = computed(() => blocks.value.reduce((s, b) => s + blockQty(b), 0))
const totalAmount = computed(() => blocks.value.reduce((s, b) => s + blockAmount(b), 0))

const pricePlaceholder = (opt: OrderStyleOption) => (opt.tagPrice == null ? '' : `吊牌价 ${formatPrice(opt.tagPrice)}`)

/** 打开"按尺码填单价"：每个码先带统一单价，关掉时不动统一单价 */
function onBySizeChange(b: StyleBlock, on: boolean) {
  if (!on) return
  for (const sz of b.opt.sizes) if (b.sizePrices[sz] == null) b.sizePrices[sz] = b.unitPrice
}

async function appendStyles(styleIds: string[]) {
  const fresh = styleIds.filter((sid) => !blocks.value.some((b) => b.opt.styleId === sid))
  if (!fresh.length) return
  const opts = await orderApi.styleOptions(fresh)
  for (const opt of opts) {
    blocks.value.push({ opt, unitPrice: null, bySizePrice: false, sizePrices: {}, lineDueDate: null, remark: '', qtys: {}, values: {} })
  }
}

function onPickedStyles(rows: MaterialPickRow[] | StylePickRow[]) {
  const picked = rows as StylePickRow[]
  if (new Set(picked.map((r) => r.productLine)).size > 1) {
    ElMessage.warning('一张订单只能放一条品类线的款，这次挑的里有几条线的：分开下单')
    return
  }
  if (!blocks.value.length || !orderLine.value) orderLine.value = picked[0]?.productLine ?? null
  void appendStyles(picked.map((r) => r.id))
}

const queryStyleId = (value: unknown) => (typeof value === 'string' && /^\d+$/.test(value) ? value : null)

async function init() {
  loadError.value = null
  notFound.value = false
  loaded.value = false
  if (id === null) {
    try {
      const sid = queryStyleId(route.query.styleId)
      if (sid) await appendStyles([sid])
      loaded.value = true
      guard.markSaved()
    } catch (e) { loadError.value = e instanceof Error ? e.message : '款式加载失败，请重试' }
    return
  }
  try {
    const d = await orderApi.get(id)
    savedCompanyId.value = d.companyId
    version.value = d.version
    orderNo.value = d.no
    orderLine.value = d.productLine
    Object.assign(head, {
      companyId: d.companyId,
      customerId: d.customerId,
      customerOrderNo: d.customerOrderNo,
      orderType: d.orderType,
      brand: d.brand,
      contractNo: d.contractNo ?? '',
      orderDate: d.orderDate,
      requiredDate: d.requiredDate,
      salesId: d.salesId,
      merchId: d.merchId,
      currency: d.currency,
      deliveryReq: d.deliveryReq ?? '',
      remark: d.remark ?? '',
    })
    names.sales = d.salesName
    names.merch = d.merchName
    const opts = d.styles.length ? await orderApi.styleOptions(d.styles.map((s) => s.styleId)) : []
    const optMap = new Map(opts.map((o) => [o.styleId, o]))
    blocks.value = d.styles.flatMap((st) => {
      const opt = optMap.get(st.styleId)
      if (!opt) throw new Error(`款式 ${st.styleCode} 的矩阵信息加载不全，请重试；原明细不会被删除`)
      const items = d.items.filter((i) => i.styleId === st.styleId)
      const qtys: Record<string, number> = {}
      const sizePrices: Record<string, number | null | undefined> = {}
      for (const it of items) {
        qtys[it.styleSkuId] = it.qty
        sizePrices[it.sizeCode] = it.price
      }
      if (items.some((item) => !opt.skus.some((sku) => sku.id === item.styleSkuId))) {
        throw new Error(`款式 ${st.styleCode} 的部分历史 SKU 无法加载，请重试；原明细不会被删除`)
      }
      const prices = new Set(items.map((i) => i.price))
      const dates = new Set(items.map((i) => i.lineDueDate))
      const remarks = new Set(items.map((i) => i.remark))
      return [{ opt, unitPrice: prices.size === 1 ? items[0]?.price ?? null : null, bySizePrice: false, sizePrices,
        lineDueDate: dates.size === 1 ? items[0]?.lineDueDate ?? null : null,
        remark: remarks.size === 1 ? items[0]?.remark ?? '' : '', qtys, values: hydrateSkuValues(items) }]
    })
    tabs.rename(path, `编辑 ${d.no}`)
    loaded.value = true
    guard.markSaved()
  } catch (e) {
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else loadError.value = e instanceof Error ? e.message : '订单加载失败，请重试'
  }
}

onMounted(init)

// 新建页签留着、款式详情又点"下订单"带了别的款进来：加到后面
watch(
  () => route.query.styleId,
  (value) => {
    if (route.path !== path || editing) return
    const sid = queryStyleId(value)
    if (sid) void appendStyles([sid])
  },
)

function buildItems(): OrderItemForm[] {
  return blocks.value.flatMap((b) => buildSkuRows(b.opt.skus.map((s) => s.id), b.qtys, b.values))
}

function buildForm(): OrderForm | null {
  if (!head.companyId || !head.orderType || !head.orderDate || !head.requiredDate || !head.salesId || !head.merchId || !head.currency) {
    ElMessage.warning('主体、订单类型、下单日期、要求交期、业务员、跟单员、币种要填')
    return null
  }
  const items = buildItems()
  if (!items.length) {
    ElMessage.warning('至少填一格件数')
    return null
  }
  const capacityError = validateOrderCapacity(items)
  if (capacityError) { ElMessage.warning(capacityError); return null }
  return {
    companyId: head.companyId,
    orderType: head.orderType,
    customerId: head.customerId,
    customerOrderNo: head.customerOrderNo,
    contractNo: orNull(head.contractNo),
    brand: head.brand,
    orderDate: head.orderDate,
    requiredDate: head.requiredDate,
    salesId: head.salesId,
    merchId: head.merchId,
    currency: head.currency,
    deliveryReq: orNull(head.deliveryReq),
    remark: orNull(head.remark),
    items,
  }
}

/** 提交审核：静默请求，资料包检查没过（1000 + 清单）弹表格。返回提交上了没有 */
async function submit(orderId: string, ver: number): Promise<boolean> {
  try {
    await orderApi.act(orderId, 'submit', { version: ver }, { silent: true })
    return true
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    return false
  }
}

async function save(andSubmit: boolean) {
  if (!(await guard.waitForUploads())) return
  const form = buildForm()
  if (!form) return
  saving.value = true
  try {
    if (id !== null) {
      version.value = await orderApi.update(id, { ...form, version: version.value, expectedCompanyId: savedCompanyId.value })
      savedCompanyId.value = form.companyId
      guard.markSaved()
      if (andSubmit && !(await submit(id, version.value))) return
      ElMessage.success(andSubmit ? `${orderNo.value} 提交审核了` : `${orderNo.value} 已保存`)
      await leave(`/orders/${id}`)
      return
    }
    const saved = await orderApi.create({ ...form, files: files.value })
    guard.markSaved()
    if (andSubmit && !(await submit(saved.id, saved.version))) {
      // 草稿已经建好了：去它的编辑页接着改（原因已经弹出来了），新建页签关掉
      await leave(`/orders/${saved.id}/edit`)
      return
    }
    ElMessage.success(andSubmit ? '订单建好了，已提交审核' : '订单草稿存好了')
    await leave(`/orders/${saved.id}`)
  } catch (e) {
    if (!reloadOnConflict(e, init) && !(e instanceof ApiError)) throw e
  } finally {
    saving.value = false
  }
}

/** 去别的页，关掉这个表单页签（取消是明确不要了，不再问） */
async function leave(to: string) {
  guard.discardUploads()
  guard.markSaved()
  await router.push(to)
  tabs.close(path, to)
}

const backTo = editing ? `/orders/${id}` : '/orders'
</script>

<template>
  <div>
    <PageHeader :title="editing ? `编辑 ${orderNo}` : '新建需求订单'" :back="{ label: editing ? orderNo || '需求订单' : '需求订单', to: backTo }">
      <template #actions>
        <el-button @click="leave(backTo)">取消</el-button>
        <el-button :loading="saving" :disabled="!loaded" @click="save(false)">{{ editing ? '保存' : '存草稿' }}</el-button>
        <el-button v-perm="'order:order:submit'" type="primary" :loading="saving" :disabled="!loaded" @click="save(true)">
          {{ editing ? '保存并提交' : '提交审核' }}
        </el-button>
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张订单不存在，可能已经删了">
      <el-button @click="leave('/orders')">回订单列表</el-button>
    </BlankState>

    <el-alert v-else-if="loadError" :title="loadError" type="error" :closable="false"><el-button @click="init">重试</el-button></el-alert>
    <div v-else v-loading="!loaded" class="page">
      <el-form label-position="top" class="form-grid order-head">
        <el-form-item label="主体" required>
          <CompanySelect v-model="head.companyId" placeholder="选主体" />
        </el-form-item>
        <el-form-item label="订单类型" required>
          <DictSelect v-model="head.orderType" type="order_type" />
        </el-form-item>
        <el-form-item label="品牌">
          <DictSelect v-model="head.brand" type="brand" clearable />
        </el-form-item>
        <el-form-item label="合同号">
          <el-input v-model="head.contractNo" maxlength="60" />
        </el-form-item>
        <el-form-item label="下单日期" required>
          <el-date-picker v-model="head.orderDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
        <el-form-item label="要求交期" required>
          <el-date-picker v-model="head.requiredDate" type="date" value-format="YYYY-MM-DD" :clearable="false" />
        </el-form-item>
        <el-form-item label="业务员" required>
          <UserSelect v-model="head.salesId" :label="names.sales" />
        </el-form-item>
        <el-form-item label="跟单员" required>
          <UserSelect v-model="head.merchId" :label="names.merch" />
        </el-form-item>
        <el-form-item label="币种" required>
          <DictSelect v-model="head.currency" type="currency" />
        </el-form-item>
        <el-form-item label="送货要求">
          <el-input v-model="head.deliveryReq" maxlength="200" placeholder="如：分色分码装箱" />
        </el-form-item>
        <el-form-item label="备注" class="full">
          <el-input v-model="head.remark" maxlength="200" />
        </el-form-item>
      </el-form>

      <div class="sec-bar">
        <h3 class="sec">款式</h3>
        <span class="summary num">合计 <strong>{{ formatQty(totalQty) }}</strong> 件<template v-if="canSeeOrderPrice"> · {{ head.currency }} <strong>{{ formatMoney(totalAmount) }}</strong></template></span>
        <el-button icon="Plus" @click="stylePicking = true">加款式</el-button>
      </div>

      <p v-if="!blocks.length" class="empty dim">还没加款式：点「加款式」从款式库挑，一个款一张矩阵</p>

      <section v-for="(b, bIdx) in blocks" :key="b.opt.styleId" class="panel style-block">
        <div class="panel-head block-head">
          <span class="st-info">
            <ThumbImage v-if="b.opt.image" :src="b.opt.image.thumbUrl" alt="" class="thumb" />
            <span class="code">{{ b.opt.code }}</span>
            <strong>{{ b.opt.name }}</strong>
            <span v-if="b.opt.problem" class="problem">{{ b.opt.problem }}</span>
            <span v-else-if="b.opt.currentPackVersion != null" class="dim">资料包 V{{ b.opt.currentPackVersion }}</span>
          </span>
          <span class="st-acts num">
            {{ formatQty(blockQty(b)) }} 件<template v-if="canSeeOrderPrice"> · {{ formatMoney(blockAmount(b)) }}</template>
            <el-button link type="danger" @click="blocks.splice(bIdx, 1)">去掉这个款</el-button>
          </span>
        </div>
        <div class="panel-body">
          <div class="line-controls">
            <label v-if="canSeeOrderPrice" class="ctrl">
              <span class="dim">单价</span>
              <el-input-number
                v-model="b.unitPrice"
                data-testid="order-batch-price"
                :max="MAX_UNIT_PRICE"
                size="small"
                :min="0"
                :precision="4"
                :controls="false"
                :disabled="b.bySizePrice"
                :placeholder="pricePlaceholder(b.opt)"
              />
            </label>
            <el-button v-if="canSeeOrderPrice" data-testid="order-apply-price" size="small" :disabled="b.bySizePrice" @click="applyPrice(b)">应用整款单价</el-button>
            <el-checkbox v-if="canSeeOrderPrice" v-model="b.bySizePrice" @change="onBySizeChange(b, Boolean($event))">按尺码填单价</el-checkbox>
            <label class="ctrl">
              <span class="dim">行交期</span>
              <el-date-picker v-model="b.lineDueDate" size="small" type="date" value-format="YYYY-MM-DD" placeholder="同要求交期" />
            </label>
            <label class="ctrl grow">
              <span class="dim">行备注</span>
              <el-input v-model="b.remark" size="small" maxlength="120" />
            </label>
            <el-button size="small" @click="applyDetails(b)">应用整款交期 / 备注</el-button>
          </div>
          <p v-if="mixedValues(b)" class="dim">多个值：各 SKU 的明细分别保留；批量输入后点「应用」才覆盖。</p>
          <SkuMatrix v-model="b.qtys" :sizes="b.opt.sizes" :colors="b.opt.colors" :skus="skuCells(b.opt)">
            <template v-if="canSeeOrderPrice && b.bySizePrice" #prepend="{ sizes }">
              <tr class="price-row">
                <td class="dim">单价</td>
                <td v-for="sz in sizes" :key="sz">
                  <el-input-number :data-testid="`order-size-price-${sz}`" :max="MAX_UNIT_PRICE" v-model="b.sizePrices[sz]" size="small" :min="0" :precision="4" :controls="false" :placeholder="pricePlaceholder(b.opt)" />
                  <el-button :data-testid="`order-apply-size-price-${sz}`" size="small" link @click="applyPrice(b, sz)">应用本尺码</el-button>
                </td>
                <td />
              </tr>
            </template>
          </SkuMatrix>
          <el-collapse class="sku-values">
            <el-collapse-item :title="canSeeOrderPrice ? '逐 SKU 单价 / 行交期 / 备注' : '逐 SKU 行交期 / 备注'" name="sku-values">
              <el-table :data="b.opt.skus.filter((sk) => (b.qtys[sk.id] ?? 0) > 0)" size="small">
                <el-table-column label="SKU" min-width="170"><template #default="{ row }">{{ row.code }} · {{ row.sizeCode }}</template></el-table-column>
                <el-table-column v-if="canSeeOrderPrice" label="单价" width="150"><template #default="{ row }"><el-input-number v-model="valuesOf(b, row.id).price" :min="0" :max="MAX_UNIT_PRICE" :precision="4" :controls="false" size="small" /></template></el-table-column>
                <el-table-column label="行交期" width="175"><template #default="{ row }"><el-date-picker v-model="valuesOf(b, row.id).lineDueDate" type="date" value-format="YYYY-MM-DD" placeholder="同要求交期" size="small" /></template></el-table-column>
                <el-table-column label="备注" min-width="180"><template #default="{ row }"><el-input v-model="valuesOf(b, row.id).remark" maxlength="120" size="small" /></template></el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </div>
      </section>

      <template v-if="!editing">
        <h3 class="sec files-h">附件</h3>
        <DocAttachments v-model="files" />
      </template>
    </div>

    <DataPick v-model="stylePicking" kind="style" :have="pickedStyleIds" :params="pickParams" @pick="onPickedStyles" />
  </div>
</template>

<style scoped>
.order-head {
  max-width: 860px;
}

.order-head :deep(.el-select),
.order-head :deep(.el-date-editor) {
  width: 100%;
}

.sec-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-top: 14px;
  margin: 8px 0 12px;
  border-top: 1px solid var(--lm-line-soft);
}

.sec {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.files-h {
  margin: 18px 0 10px;
}

.summary {
  flex: 1;
  font-size: 13px;
}

.empty {
  padding: 28px 0;
  text-align: center;
}

.style-block {
  margin-bottom: 14px;
}

.block-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.st-info,
.st-acts {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.st-acts {
  font-size: 12.5px;
  font-weight: 400;
}

.thumb {
  width: 30px;
  height: 30px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: 4px;
}

.problem {
  font-size: 12px;
  font-weight: 400;
  color: var(--lm-danger-text);
}

.line-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-bottom: 12px;
  font-size: 12.5px;
}

.ctrl {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.ctrl.grow {
  flex: 1;
  min-width: 200px;
}

.price-row :deep(.el-input-number) {
  width: 76px;
}
</style>
