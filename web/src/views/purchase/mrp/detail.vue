<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { routeId } from '@/utils/entityId'
import { computed, h, onActivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { mrpApi as mrpApiBase, purchaseApi as purchaseApiBase } from '@/api/purchase'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import SupplierSelect from '@/components/SupplierSelect.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import DocNoticesBanner from '@/components/doc/DocNoticesBanner.vue'
import DocRefs from '@/components/doc/DocRefs.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import { isItem, reloadOnConflict } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import type { FileVO } from '@/types/system'
import { MRP_CLOSE_REASON_LABEL, MRP_LOG_ACTION, MRP_STATUS, type MrpAction, type MrpCalcDetail, type MrpItem } from '@/types/purchase'
import { ErrorCode } from '@/utils/errorCodes'
import { formatDate, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'
import { confirmRemove, orNull } from '@/views/basic/common'
import { bindCompanyApi } from '@/utils/companyScope'
import { useFieldAccess } from '@/composables/useFieldAccess'
const { canSeeCost } = useFieldAccess()
const mrpApi = bindCompanyApi(mrpApiBase, () => detail.value?.companyId)
const purchaseApi = bindCompanyApi(purchaseApiBase, () => detail.value?.companyId)

/**
 * 算料单详情（/mrp/:id，docs/api/03 §1.4、§3.2、§4）。
 * - 页头：确认、生成采购单（对勾的行）；重新算料、关闭、删除在"更多"里
 * - 草稿里改格子：失焦调 updateItem，用返回的整张换掉（version 也换）；已确认的只有供应商能换（这一行还没转完）
 * - 确认（或生成采购单时自动确认）回 1000"可用量已变化"：出黄条，给"重新算料"按钮
 * - 生成采购单前先在前端拦：勾的行里没选供应商、供应商不是合作中的，标红这几行
 * - "本次用在途""赶不上的在途"点了展开那一行，看用的是哪几张采购单
 * - 1002（别人刚改过、有了重新算料的草稿）：重新取详情
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()

const id = routeId(route.params.id)
const path = route.path
const units = useDict('unit')

const detail = ref<MrpCalcDetail | null>(null)
const notFound = ref(false)
const working = ref(false)
const remark = ref('')
const tableRef = ref<TableInstance>()
const selected = ref<MrpItem[]>([])
const bottomTab = ref<'refs' | 'logs'>('refs')
const logRef = ref<InstanceType<typeof DocLogList>>()
const refsRef = ref<InstanceType<typeof DocRefs>>()
/** 确认时后端说可用量变了：出黄条让人重新算料 */
const staleAvail = ref(false)
/** 生成采购单前拦下来的行（没选供应商、供应商不是合作中），标红 */
const badRowIds = ref<Set<string>>(new Set())

const can = (a: MrpAction) => detail.value?.actions.includes(a) ?? false
const isDraft = computed(() => detail.value?.status === 'DRAFT' && can('edit'))
const supplierEditable = (row: MrpItem) => can('edit') && detail.value?.status !== 'CLOSED' && row.convertedQty < row.suggestQty

const purchaseRows = computed(() => detail.value?.items.filter((i) => i.block === 'PURCHASE') ?? [])
const factoryRows = computed(() => detail.value?.items.filter((i) => i.block === 'FACTORY') ?? [])

const detailRequest = useLatestRequest()
async function load() {
  const read = detailRequest.begin()
  try {
    const d = await read.run(() => mrpApi.get(id))
    if (!read.current()) return
    detail.value = d
    remark.value = d.remark ?? ''
    tabs.rename(path, `算料 ${d.no}`)
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

async function refresh() {
  await load()
  logRef.value?.reload()
  refsRef.value?.reload()
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

/** 别的动作出错：1002 重新取，别的照常抛（消息 request.ts 弹过了） */
function onActionError(e: unknown) {
  if (reloadOnConflict(e, refresh)) return
  if (e instanceof ApiError && e.code === ErrorCode.BIZ && e.message.includes('可用量已变化')) {
    staleAvail.value = true
    return
  }
  throw e
}

// ---------- 改格子、删行、备注 ----------
async function saveRemark() {
  const d = detail.value
  if (!d || d.status === 'CLOSED') return
  const next = orNull(remark.value)
  if (next === d.remark) return
  try {
    await mrpApi.updateRemark(id, { version: d.version, remark: next })
    await load()
  } catch (e) {
    onActionError(e)
  }
}

async function saveCell(row: MrpItem) {
  const d = detail.value
  if (!d) return
  try {
    detail.value = await mrpApi.updateItem(id, row.id, {
      version: d.version,
      supplierId: row.supplierId,
      useStockQty: row.useStockQty,
      useTransitQty: row.useTransitQty,
      suggestQty: row.suggestQty,
    })
    badRowIds.value.delete(row.id)
    logRef.value?.reload()
  } catch {
    // 超上限（400）、1002：消息弹过了，格子回到后端的数
    await load()
  }
}

function onSupplierChange(row: MrpItem, supplierId: string | null | undefined) {
  row.supplierId = supplierId ?? null
  void saveCell(row)
}

async function removeLine(row: MrpItem) {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删掉 ${row.skuCode}`, '这种料这次不由我方采购（比如改成工厂自备）才删；删了以后重新算料会再算出来。')
  try {
    detail.value = await mrpApi.removeItem(id, row.id, d.version)
    ElMessage.success(`删掉了 ${row.skuCode}`)
  } catch (e) {
    onActionError(e)
  }
}

// ---------- 确认、重新算料、关闭、删除 ----------
async function confirmCalc() {
  const d = detail.value
  if (!d) return
  working.value = true
  try {
    await mrpApi.confirm(id, { version: d.version })
    staleAvail.value = false
    ElMessage.success(`${d.no} 确认了：用的库存、在途算占着了`)
    await refresh()
  } catch (e) {
    onActionError(e)
  } finally {
    working.value = false
  }
}

async function recalc() {
  const d = detail.value
  if (!d) return
  if (d.status === 'DRAFT') {
    try {
      await ElMessageBox.confirm('按现在的订单、库存、在途重算这一张，手改过的数都不要了。', `重新算料 ${d.no}`, {
        confirmButtonText: '重新算料',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  working.value = true
  try {
    const res = await mrpApi.recalc(id, { version: d.version })
    staleAvail.value = false
    if (res.id !== id) {
      ElMessage.success(`另算了一张 ${res.no}：那张确认后这张自动关闭`)
      await router.push(`/mrp/${res.id}`)
    } else {
      ElMessage.success('重新算好了')
      await refresh()
    }
  } catch (e) {
    onActionError(e)
  } finally {
    working.value = false
  }
}

async function closeCalc() {
  const d = detail.value
  if (!d) return
  try {
    await ElMessageBox.confirm('关闭后它占着的库存、在途放出来给别的单用，不能再生成采购单；已经生成的采购单不受影响。', `关闭 ${d.no}`, {
      confirmButtonText: '关闭',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  try {
    await mrpApi.close(id, { version: d.version })
    ElMessage.success(`${d.no} 关闭了`)
    await refresh()
  } catch (e) {
    onActionError(e)
  }
}

async function removeCalc() {
  const d = detail.value
  if (!d) return
  await confirmRemove(`删除 ${d.no}`, '草稿删除后找不回来，订单不受影响，随时可以再算。')
  await mrpApi.remove(id)
  ElMessage.success(`已删除 ${d.no}`)
  await router.push('/mrp')
  tabs.close(path, '/mrp')
}

type MoreCommand = 'recalc' | 'close' | 'remove'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    can('recalc') && { command: 'recalc', label: '重新算料' },
    can('close') && { command: 'close', label: '关闭', danger: true },
    can('remove') && { command: 'remove', label: '删除', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'recalc') void recalc()
  else if (command === 'close') void closeCalc()
  else void removeCalc()
}

// ---------- 生成采购单 ----------
async function toPurchase() {
  const d = detail.value
  if (!d) return
  if (!selected.value.length) {
    ElMessage.warning('先勾要买的行')
    return
  }
  const bad = selected.value.filter((r) => !r.supplierId || (r.supplierStatus && r.supplierStatus !== 'ACTIVE'))
  badRowIds.value = new Set(bad.map((r) => r.id))
  if (bad.length) {
    ElMessage.warning(`${bad.map((r) => r.skuCode).join('、')}：还没选供应商，或者供应商不是合作中，先换一家`)
    return
  }
  working.value = true
  try {
    const created = await purchaseApi.fromMrp({ calcId: id, itemIds: selected.value.map((r) => r.id), version: d.version })
    tableRef.value?.clearSelection()
    ElMessage.success({
      message: h('span', [
        `按供应商拆成 ${created.length} 张采购单草稿：`,
        ...created.flatMap((c, i) => [
          i ? '、' : '',
          h('a', { href: '#', class: 'msg-link', onClick: (ev: Event) => (ev.preventDefault(), void router.push(`/purchases/${c.id}`)) }, `打开 ${c.no}`),
        ]),
      ]),
      duration: 6000,
    })
    bottomTab.value = 'refs'
    await refresh()
  } catch (e) {
    onActionError(e)
  } finally {
    working.value = false
  }
}

const rowClass = ({ row }: { row: MrpItem }) => (badRowIds.value.has(row.id) || (row.canConvert && !row.supplierId) ? 'bad-row' : '')
const canCheck = (row: MrpItem) => row.canConvert

// ---------- 展开：本次用在途、赶不上的在途 ----------
function toggleExpand(row: MrpItem) {
  tableRef.value?.toggleRowExpansion(row)
}

/** 需求变化：多了绿、少了红（03 §1.4） */
const diffClass = (qty: number) => (qty > 0 ? 'up' : qty < 0 ? 'down' : '')

// ---------- 侧栏、页签（script 里定义，模板里不写引用 import 的箭头函数） ----------
const loadNotices = () => mrpApi.notices(id)
const handleNotice = (noticeId: string, text: string | null) => mrpApi.handleNotice(id, noticeId, text)
const loadRefs = () => mrpApi.references(id)
const loadLogs = () => mrpApi.logs(id)
const loadFiles = () => mrpApi.attachments(id)
const saveFiles = (files: FileVO[]) => mrpApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(MRP_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? '算料单'" :back="{ label: '算料', to: '/mrp' }">
      <template v-if="detail" #title>
        <StatusTag :tone="MRP_STATUS[detail.status].tone">{{ MRP_STATUS[detail.status].label }}</StatusTag>
        <span v-if="detail.closeReason" class="dim small">{{ MRP_CLOSE_REASON_LABEL[detail.closeReason] }}</span>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('confirm')" :loading="working" @click="confirmCalc">确认</el-button>
        <el-button v-if="can('toPurchase')" type="primary" :loading="working" :disabled="!selected.length" @click="toPurchase">
          生成采购单{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张算料单不存在，可能已经删了">
      <el-button @click="router.push('/mrp')">回算料列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <DocNoticesBanner :pending-count="detail.pendingNotices" :load="loadNotices" :handle="handleNotice" @handled="refresh">
          <template #actions>
            <el-button v-if="can('recalc')" size="small" :loading="working" @click="recalc">重新算料</el-button>
          </template>
        </DocNoticesBanner>

        <div v-if="staleAvail" class="warn-bar">
          <span>可用量已变化（别人刚确认了一张算料单，或库存、在途变了），要重新算料</span>
          <el-button v-if="can('recalc')" size="small" :loading="working" @click="recalc">重新算料</el-button>
        </div>
        <div v-if="detail.nextCalcId" class="warn-bar">
          <span>已经重新算料成 <router-link :to="`/mrp/${detail.nextCalcId}`" class="code">{{ detail.nextCalcNo }}</router-link>（草稿），那张确认后这张自动关闭</span>
        </div>

        <p class="sub">
          <router-link v-for="o in detail.orders" :key="o.orderId" :to="`/orders/${o.orderId}`" class="code">{{ o.orderNo }}</router-link>
          <span class="code dim">{{ detail.styleCodes.join('、') }}</span>
          <span>要料 {{ detail.needDate ? formatDate(detail.needDate) : '—' }}</span>
          <span v-if="canSeeCost" class="num">预计 {{ formatMoney(detail.estAmount) }} · 已转采购 {{ formatMoney(detail.convertedAmount) }}</span>
          <span v-if="detail.prevCalcId">由 <router-link :to="`/mrp/${detail.prevCalcId}`" class="code">{{ detail.prevCalcNo }}</router-link> 重新算料</span>
        </p>

        <section v-if="detail.diff" class="panel">
          <div class="panel-head">和上一张比</div>
          <p v-if="!detail.diff.length" class="panel-body dim">需求没变</p>
          <el-table v-else :data="detail.diff" row-key="materialSkuId">
            <el-table-column label="物料" min-width="220">
              <template #default="{ row }"><span class="code">{{ row.skuCode }}</span> {{ row.materialName }}</template>
            </el-table-column>
            <el-table-column label="上一张需求" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.fromNeedQty) }}</template>
            </el-table-column>
            <el-table-column label="这一张需求" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.toNeedQty) }}</template>
            </el-table-column>
            <el-table-column label="变化" width="120" class-name="num" align="right">
              <template #default="{ row }">
                <span :class="diffClass(row.diffQty)">{{ row.diffQty > 0 ? '+' : '' }}{{ formatQty(row.diffQty) }} {{ units.labelOf(row.stockUnit) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="panel">
          <div class="panel-head">我方采购<span class="dim head-sub">{{ purchaseRows.length }} 种</span></div>
          <el-table ref="tableRef" :data="purchaseRows" row-key="id" :row-class-name="rowClass" empty-text="没有要我方采购的料" @selection-change="selected = $event">
            <el-table-column type="selection" width="40" :selectable="canCheck" />
            <el-table-column type="expand" width="1" class-name="expand-hidden">
              <template #default="{ row }">
                <div class="expand">
                  <div>
                    <h5>本次用在途</h5>
                    <table v-if="(row as MrpItem).transitUses.length" class="mini">
                      <thead><tr><th>采购单</th><th>预计到货</th><th class="num">用</th><th class="num">已到</th></tr></thead>
                      <tbody>
                        <tr v-for="t in (row as MrpItem).transitUses" :key="t.purchaseItemId">
                          <td><router-link :to="`/purchases/${t.purchaseOrderId}`" class="code">{{ t.purchaseNo }}</router-link></td>
                          <td class="num">{{ t.eta ? formatDate(t.eta) : '—' }}</td>
                          <td class="num">{{ formatQty(t.qty) }}</td>
                          <td class="num">{{ formatQty(t.arrivedQty) }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <p v-else class="dim">没用在途</p>
                  </div>
                  <div>
                    <h5>赶不上的在途</h5>
                    <table v-if="(row as MrpItem).lateTransits.length" class="mini">
                      <thead><tr><th>采购单</th><th>预计到货</th><th class="num">还能用</th></tr></thead>
                      <tbody>
                        <tr v-for="t in (row as MrpItem).lateTransits" :key="t.purchaseItemId">
                          <td><router-link :to="`/purchases/${t.purchaseOrderId}`" class="code">{{ t.purchaseNo }}</router-link></td>
                          <td class="num">{{ t.eta ? formatDate(t.eta) : '—' }}</td>
                          <td class="num">{{ formatQty(t.freeQty) }}</td>
                        </tr>
                      </tbody>
                    </table>
                    <p v-else class="dim">没有</p>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="物料" min-width="210" fixed="left">
              <template #default="{ row }">
                <span class="code">{{ row.skuCode }}</span>
                <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
              </template>
            </el-table-column>
            <el-table-column label="供应商" width="170">
              <template #default="{ row }">
                <SupplierSelect
                  v-if="supplierEditable(row as MrpItem)"
                  :model-value="row.supplierId"
                  :label="row.supplierName"
                  usage="BULK"
                  size="small"
                  placeholder="选供应商"
                  @update:model-value="onSupplierChange(row as MrpItem, $event)"
                />
                <span v-else-if="row.supplierName">{{ row.supplierName }}</span>
                <span v-else class="danger">没选</span>
                <div v-if="row.supplierStatus && row.supplierStatus !== 'ACTIVE'" class="danger small">供应商不是合作中</div>
              </template>
            </el-table-column>
            <el-table-column label="毛需求" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.grossQty) }}</template>
            </el-table-column>
            <el-table-column label="备料比" width="70" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.reservePct) }}%</template>
            </el-table-column>
            <el-table-column label="需求" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.needQty) }} <span class="dim">{{ units.labelOf(row.stockUnit) }}</span></template>
            </el-table-column>
            <el-table-column label="可用库存" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.availStockQty) }}</template>
            </el-table-column>
            <el-table-column label="本次用库存" width="104" class-name="num" align="right">
              <template #default="{ row }">
                <el-input-number
                  v-if="isDraft"
                  v-model="row.useStockQty"
                  size="small"
                  :min="0"
                  :max="Math.min(row.availStockQty, row.needQty)"
                  :precision="3"
                  :controls="false"
                  @change="saveCell(row as MrpItem)"
                />
                <template v-else>{{ formatQty(row.useStockQty) }}</template>
              </template>
            </el-table-column>
            <el-table-column label="可用在途" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.availTransitQty) }}</template>
            </el-table-column>
            <el-table-column label="本次用在途" width="104" class-name="num" align="right">
              <template #default="{ row }">
                <el-input-number
                  v-if="isDraft"
                  v-model="row.useTransitQty"
                  size="small"
                  :min="0"
                  :max="Math.min(row.availTransitQty, Math.max(0, row.needQty - row.useStockQty))"
                  :precision="3"
                  :controls="false"
                  @change="saveCell(row as MrpItem)"
                />
                <button v-else type="button" class="link-btn" :disabled="!(row as MrpItem).transitUses.length" @click="toggleExpand(row as MrpItem)">
                  {{ formatQty(row.useTransitQty) }}
                </button>
              </template>
            </el-table-column>
            <el-table-column label="赶不上的在途" width="96" class-name="num" align="right">
              <template #default="{ row }">
                <button v-if="row.lateTransitQty > 0" type="button" class="link-btn warn" @click="toggleExpand(row as MrpItem)">{{ formatQty(row.lateTransitQty) }}</button>
                <span v-else class="dim">0</span>
              </template>
            </el-table-column>
            <el-table-column label="净需求" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.netQty) }}</template>
            </el-table-column>
            <el-table-column label="建议采购" width="124" class-name="num" align="right">
              <template #default="{ row }">
                <el-input-number
                  v-if="isDraft"
                  v-model="row.suggestQty"
                  size="small"
                  :min="row.convertedQty"
                  :precision="3"
                  :controls="false"
                  @change="saveCell(row as MrpItem)"
                />
                <strong v-else>{{ formatQty(row.suggestQty) }}</strong>
                <span class="dim"> {{ units.labelOf(row.purchaseUnit) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canSeeCost" label="参考单价" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ row.refPrice == null ? '—' : formatPrice(row.refPrice) }}</template>
            </el-table-column>
            <el-table-column v-if="canSeeCost" label="预计金额" width="96" class-name="num" align="right">
              <template #default="{ row }">{{ row.estAmount == null ? '—' : formatMoney(row.estAmount) }}</template>
            </el-table-column>
            <el-table-column label="已转采购" width="84" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.convertedQty) }}</template>
            </el-table-column>
            <el-table-column label="齐料" width="104" align="right">
              <template #default="{ row }">
                <StatusTag v-if="row.shortQty <= 0" tone="done">齐</StatusTag>
                <span v-else class="warn num">差 {{ formatQty(row.shortQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="isDraft" label="" width="60" align="right" :show-overflow-tooltip="false">
              <template #default="{ row }">
                <el-button v-if="row.convertedQty === 0" link type="danger" size="small" @click="removeLine(row as MrpItem)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-if="factoryRows.length" class="panel">
          <div class="panel-head">工厂自备<span class="dim head-sub">只看，不采购</span></div>
          <el-table :data="factoryRows" row-key="id">
            <el-table-column label="物料" min-width="220">
              <template #default="{ row }"><span class="code">{{ row.skuCode }}</span> {{ row.materialName }}</template>
            </el-table-column>
            <el-table-column label="毛需求" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.grossQty) }} {{ units.labelOf(row.stockUnit) }}</template>
            </el-table-column>
            <el-table-column label="需求" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.needQty) }} {{ units.labelOf(row.stockUnit) }}</template>
            </el-table-column>
          </el-table>
        </section>

        <el-tabs v-model="bottomTab">
          <el-tab-pane label="生成的采购单" name="refs">
            <DocRefs ref="refsRef" :load="loadRefs" empty-text="还没生成采购单：勾要买的行，点「生成采购单」" />
          </el-tab-pane>
          <el-tab-pane label="操作记录" name="logs" lazy>
            <DocLogList ref="logRef" :load="loadLogs" :actions="MRP_LOG_ACTION" :statuses="LOG_STATUSES" />
          </el-tab-pane>
        </el-tabs>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">算料单信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>制单</dt>
              <dd>{{ detail.createByName ?? '—' }} · {{ formatDate(detail.createTime) }}</dd>
              <dt>确认</dt>
              <dd>{{ detail.auditTime ? `${detail.auditByName ?? '—'} · ${formatDate(detail.auditTime)}` : '—' }}</dd>
              <template v-if="detail.closeTime">
                <dt>关闭</dt>
                <dd>{{ detail.closeByName ?? '自动' }} · {{ formatDate(detail.closeTime) }}</dd>
              </template>
              <dt>备注</dt>
              <dd>
                <el-input v-model="remark" size="small" type="textarea" :autosize="{ minRows: 1 }" maxlength="160" :disabled="detail.status === 'CLOSED'" @change="saveRemark" />
              </dd>
            </dl>
          </div>
        </section>
        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" :disabled="detail.status === 'CLOSED'" @changed="logRef?.reload()" />
          </div>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 20px;
  align-items: start;
}

.body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

@media (max-width: 1280px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  margin: 0;
  font-size: 13px;
}

.small {
  font-size: 11.5px;
}

.head-sub {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
}

.warn-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.up {
  color: var(--lm-done-text);
}

.down,
.danger {
  color: var(--lm-danger-text);
}

.warn {
  color: var(--lm-pending-text);
}

.link-btn {
  padding: 0;
  font: inherit;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.link-btn.warn {
  color: var(--lm-pending-text);
}

.link-btn:disabled {
  color: inherit;
  cursor: default;
}

:deep(.bad-row) td {
  background: var(--lm-danger-bg) !important;
}

:deep(.expand-hidden .cell) {
  display: none;
}

.expand {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding: 6px 16px 10px 56px;
}

.expand h5 {
  margin: 0 0 6px;
  font-size: 12.5px;
  font-weight: 600;
}

.expand p {
  margin: 0;
  font-size: 12px;
}

.mini {
  font-size: 12px;
  border-collapse: collapse;
}

.mini th,
.mini td {
  padding: 4px 10px;
  text-align: left;
  border-bottom: 1px solid var(--lm-line-soft);
}

.mini th {
  font-weight: 500;
  color: var(--lm-muted);
}

.mini .num {
  text-align: right;
}

:global(.msg-link) {
  margin-left: 2px;
  color: var(--lm-accent);
}
</style>
