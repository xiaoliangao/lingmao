<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { statementApi as statementApiBase } from '@/api/settle'
import BlankState from '@/components/BlankState.vue'
import DocLogList from '@/components/DocLogList.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { promptReason, showSilentError, todayStr } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useTabsStore } from '@/stores/tabs'
import type { Attachment } from '@/types/biz'
import {
  COMPARE_OUTCOME, STATEMENT_LOG_ACTION, STATEMENT_SOURCE, STATEMENT_STATUS,
  type CompareRow, type StatementAction, type StatementDetail, type StatementItem,
} from '@/types/settle'
import { bindCompanyApi } from '@/utils/companyScope'
import { docRoute } from '@/utils/docRoute'
import { routeId } from '@/utils/entityId'
import { ErrorCode } from '@/utils/errorCodes'
import { formatDate, formatDateTime, formatMoney, formatPrice, formatQty } from '@/utils/format'
import { ApiError } from '@/utils/request'

/**
 * 供应商对账单详情（/statements/:id，RFC 019 §5，docs/api/09）。
 * - 页头按 actions：导出、刷新、加扣款、上传对方对账单（Excel）、发送、撤回、确认、作废
 * - 明细：草稿能勾选移出；单号点进源单；没单价的标"缺单价"
 * - 比对结果：每处一致 / 不一致 / 对方有我方没有 / 我方有对方没有，不一致的写说明（每处都写了才能确认）
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const id = routeId(route.params.id)
const path = route.path
const units = useDict('unit')
const settleMethods = useDict('settle_method')

const detail = ref<StatementDetail | null>(null)
const notFound = ref(false)
const theirFiles = ref<Attachment[]>([])
const logRef = ref<InstanceType<typeof DocLogList>>()
const selected = ref<StatementItem[]>([])
const statementApi = bindCompanyApi(statementApiBase, () => detail.value?.companyId)

const can = (a: StatementAction) => detail.value?.actions.includes(a) ?? false
const docLabel = computed(() => `对账单 ${detail.value?.no ?? ''}`)

const reader = useLatestRequest()
async function load() {
  const read = reader.begin()
  try {
    const d = await read.run(() => statementApiBase.get(id))
    if (!read.current()) return
    detail.value = d
    selected.value = []
    Object.keys(notes).forEach((k) => delete notes[k])
    d.compare?.rows.forEach((r) => (notes[r.key] = r.note ?? ''))
    notesGuard.markSaved()
    tabs.rename(path, `对账 ${d.no}`)
    theirFiles.value = d.compare ? await statementApiBase.theirFiles(id) : []
  } catch (e) {
    if (!read.current()) return
    if (e instanceof ApiError && e.code === 404) notFound.value = true
    else throw e
  }
}

async function refreshAll() {
  await load()
  logRef.value?.reload()
}

onMounted(load)
let activated = false
onActivated(() => {
  if (!activated) {
    activated = true
    return
  }
  if (!notFound.value) void refreshAll()
})

/** 带 version 的请求出错：1002 重取，别的照常显示（1000 带清单的弹表格） */
async function guarded<T>(run: () => Promise<T>): Promise<T | undefined> {
  try {
    return await run()
  } catch (e) {
    if (!(e instanceof ApiError)) throw e
    showSilentError(e)
    if (e.code === ErrorCode.STATE_CHANGED) void refreshAll()
    return undefined
  }
}

// ---------- 状态动作 ----------
const CONFIRM_TEXT: Record<'send' | 'withdraw' | 'void', { title: string; text: string; button: string; danger?: boolean }> = {
  send: { title: '发送', text: '发送后明细不能再改，把导出的 Excel 发给对方核对。对方有异议可以撤回再改。', button: '发送' },
  withdraw: { title: '撤回', text: '撤回到草稿，能刷新、移出、加扣款，改好了再发送。', button: '撤回' },
  void: { title: '作废', text: '', button: '作废', danger: true },
}

async function act(action: 'send' | 'withdraw' | 'confirm' | 'void') {
  const d = detail.value
  if (!d) return
  let reason: string | null = null
  if (action === 'void') {
    reason = await promptReason(`作废 ${d.no}`, '写一句原因：作废后里面的单据、扣款回到「待对账」，能重新生成', '作废')
    if (reason === null) return
  } else if (action === 'confirm') {
    try {
      const res = await ElMessageBox.prompt('双方对上了就确认，确认后不能改、不能作废；差错另开单冲回，进下个月的对账单。', `确认 ${d.no}`, {
        confirmButtonText: '确认', cancelButtonText: '取消', inputPlaceholder: '选填：怎么对上的（电话、微信、盖章回传）',
      })
      reason = res.value?.trim() || null
    } catch {
      return
    }
  } else {
    const c = CONFIRM_TEXT[action]
    try {
      await ElMessageBox.confirm(c.text, `${c.title} ${d.no}`, { confirmButtonText: c.button, cancelButtonText: '取消' })
    } catch {
      return
    }
  }
  const done = await guarded(() => statementApi.act(id, action, { version: d.version, reason }, { silent: true }).then(() => true))
  if (!done) return
  ElMessage.success(`${docLabel.value} 已${action === 'send' ? '发送' : action === 'withdraw' ? '撤回' : action === 'confirm' ? '确认' : '作废'}`)
  await refreshAll()
}

async function refreshItems() {
  const d = detail.value
  if (!d) return
  const r = await guarded(() => statementApi.refresh(id, { version: d.version }))
  if (!r) return
  ElMessage.success(r.added ? `加进来 ${r.added} 行` : '没有新审核的单据')
  await refreshAll()
}

async function removeSelected() {
  const d = detail.value
  if (!d || !selected.value.length) return
  try {
    await ElMessageBox.confirm(`移出的 ${selected.value.length} 行回到「待对账」，下次生成对账单时再拉进来。比对结果会清掉，要重新上传。`,
      `移出 ${selected.value.length} 行`, { confirmButtonText: '移出', cancelButtonText: '取消' })
  } catch {
    return
  }
  const v = await guarded(() => statementApi.removeItems(id, { version: d.version, itemIds: selected.value.map((i) => i.id) }))
  if (v == null) return
  ElMessage.success('移出了')
  await refreshAll()
}

function exportFile() {
  if (detail.value) void statementApiBase.export(id, detail.value.no)
}

// ---------- 加扣款 ----------
const dedVisible = ref(false)
const dedRef = ref<FormInstance>()
const ded = reactive({ bizDate: '' as string, amount: undefined as number | undefined, reason: '', refNo: '' })
const dedGuard = useUnsavedChanges(ded, dedVisible)
const dedSaving = ref(false)
const dedRules: FormRules = {
  bizDate: [{ required: true, message: '请选日期', trigger: 'change' }],
  amount: [{ required: true, message: '请填金额', trigger: 'blur' }],
  reason: [{ required: true, message: '请写原因', trigger: 'blur' }],
}
function openDeduction() {
  const d = detail.value
  if (!d) return
  const today = todayStr()
  Object.assign(ded, { bizDate: d.periodEnd < today ? d.periodEnd : today, amount: undefined, reason: '', refNo: '' })
  dedGuard.markSaved()
  dedVisible.value = true
}
const outOfPeriod = (date: Date) => {
  const d = detail.value
  if (!d) return false
  const s = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return s < d.periodStart || s > d.periodEnd || s > todayStr()
}
async function saveDeduction() {
  const d = detail.value
  await dedRef.value?.validate()
  if (!d || !ded.amount) return
  dedSaving.value = true
  try {
    const v = await guarded(() => statementApi.addDeduction(id, {
      version: d.version, bizDate: ded.bizDate, amount: ded.amount!, reason: ded.reason.trim(), refNo: ded.refNo.trim() || null,
    }))
    if (v == null) return
    ElMessage.success('扣款加上了，也记进了「扣款登记」')
    dedGuard.markSaved()
    dedVisible.value = false
    await refreshAll()
  } finally {
    dedSaving.value = false
  }
}

// ---------- 比对 ----------
const fileInput = ref<HTMLInputElement>()
const comparing = ref(false)
const onlyDiff = ref(true)
const notes = reactive<Record<string, string>>({})
const notesGuard = useUnsavedChanges(notes)
const savingNotes = ref(false)

function pickFile() {
  fileInput.value?.click()
}
async function onFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const d = detail.value
  if (!file || !d) return
  comparing.value = true
  try {
    const r = await guarded(() => statementApi.compare(id, d.version, file))
    if (!r) return
    const differs = r.rows.filter((x) => x.outcome !== 'MATCH').length
    ElMessage.success(differs ? `比对完了：${differs} 处不一致，逐条写说明` : '比对完了：全部一致')
    onlyDiff.value = differs > 0
    await refreshAll()
  } finally {
    comparing.value = false
  }
}

const compareRows = computed(() => {
  const rows = detail.value?.compare?.rows ?? []
  return onlyDiff.value ? rows.filter((r) => r.outcome !== 'MATCH') : rows
})
const diffCount = computed(() => detail.value?.compare?.rows.filter((r) => r.outcome !== 'MATCH').length ?? 0)
const unexplained = computed(() => detail.value?.compare?.rows.filter((r) => r.outcome !== 'MATCH' && !notes[r.key]?.trim()).length ?? 0)
const notesDirty = computed(() => (detail.value?.compare?.rows ?? []).some((r) => (r.note ?? '') !== (notes[r.key] ?? '')))

async function saveNotes() {
  const d = detail.value
  if (!d?.compare) return
  const changed = d.compare.rows.filter((r) => (r.note ?? '') !== (notes[r.key] ?? '')).map((r) => ({ key: r.key, note: notes[r.key]?.trim() || null }))
  if (!changed.length) return
  savingNotes.value = true
  try {
    const v = await guarded(() => statementApi.saveNotes(id, { version: d.version, notes: changed }))
    if (v == null) return
    ElMessage.success(`存好了 ${changed.length} 处说明`)
    notesGuard.markSaved()
    await refreshAll()
  } finally {
    savingNotes.value = false
  }
}

// ---------- 显示 ----------
const sourceLink = (it: StatementItem) =>
  it.sourceType === 'DEDUCTION'
    ? null
    : it.sourceType === 'PRODUCTION_RECEIPT'
      ? docRoute({ type: 'PRODUCTION_RECEIPT', kind: null, id: it.sourceId, parentId: it.parentId })
      : docRoute({ type: 'INV_DOC', kind: it.sourceType === 'PURCHASE_RETURN' ? 'OUT' : 'IN', id: it.sourceId, parentId: null })
const unitText = (it: StatementItem) => (it.unit ? units.labelOf(it.unit) : it.sourceType === 'PRODUCTION_RECEIPT' ? '件' : '')
const missingPrice = (it: StatementItem) => it.sourceType !== 'DEDUCTION' && it.price == null
const canRemove = computed(() => can('removeItems'))
const diffText = (r: CompareRow) => (r.outcome === 'MATCH' ? '' : `${r.diffAmount > 0 ? '+' : ''}${formatMoney(r.diffAmount)}`)
const headerSub = computed(() => {
  const d = detail.value
  return d ? `${d.partnerName} · ${formatDate(d.periodStart)} ~ ${formatDate(d.periodEnd)}` : undefined
})
const LOG_STATUSES = Object.fromEntries(Object.entries(STATEMENT_STATUS).map(([k, v]) => [k, v.label]))
const loadLogs = () => statementApiBase.logs(id)
</script>

<template>
  <div>
    <PageHeader :title="detail?.no ?? '对账单'" :sub="headerSub" :back="{ label: '供应商对账', to: '/statements' }">
      <template v-if="detail" #title>
        <StatusTag :tone="STATEMENT_STATUS[detail.status].tone">{{ STATEMENT_STATUS[detail.status].label }}</StatusTag>
      </template>
      <template v-if="detail" #actions>
        <el-button v-if="can('void')" @click="act('void')">作废</el-button>
        <el-button v-if="can('withdraw')" @click="act('withdraw')">撤回</el-button>
        <el-button v-if="can('refresh')" @click="refreshItems">刷新</el-button>
        <el-button v-if="can('addDeduction')" @click="openDeduction">加扣款</el-button>
        <el-button icon="Download" @click="exportFile">导出</el-button>
        <el-button v-if="can('compare')" icon="Upload" :loading="comparing" @click="pickFile">上传对方对账单</el-button>
        <el-button v-if="can('send')" type="primary" @click="act('send')">发送</el-button>
        <el-button v-if="can('confirm')" type="primary" @click="act('confirm')">确认</el-button>
        <input ref="fileInput" type="file" accept=".xls,.xlsx" hidden @change="onFile" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张对账单不存在，可能不在你的范围里">
      <el-button @click="router.push('/statements')">回列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <div class="totals">
          <div><span class="dim">货款</span><b class="num">{{ formatMoney(detail.totalAmount) }}</b></div>
          <div><span class="dim">扣款</span><b class="num">{{ formatMoney(detail.deductAmount) }}</b></div>
          <div class="pay"><span class="dim">实付</span><b class="num">{{ formatMoney(detail.payableAmount) }}</b></div>
          <div v-if="detail.theirAmount != null">
            <span class="dim">对方合计</span><b class="num">{{ formatMoney(detail.theirAmount) }}</b>
          </div>
        </div>

        <section class="block">
          <div class="block-head">
            <span>明细 {{ detail.items.length }} 行</span>
            <el-button v-if="canRemove && selected.length" size="small" @click="removeSelected">移出选中的 {{ selected.length }} 行</el-button>
          </div>
          <el-table :data="detail.items" row-key="id" scrollbar-always-on
            empty-text="这张对账单没有明细：点「刷新」把这段时间里后来审核的单据加进来，或者作废" @selection-change="selected = $event">
            <el-table-column v-if="canRemove" type="selection" width="40" fixed="left" />
            <el-table-column label="类型" width="84">
              <template #default="{ row }">
                <StatusTag :tone="STATEMENT_SOURCE[(row as StatementItem).sourceType].tone">{{ STATEMENT_SOURCE[(row as StatementItem).sourceType].label }}</StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="日期" width="108" class-name="num">
              <template #default="{ row }">{{ formatDate(row.bizDate) }}</template>
            </el-table-column>
            <el-table-column label="单号" width="150">
              <template #default="{ row }">
                <router-link v-if="sourceLink(row as StatementItem)" class="code link" :to="sourceLink(row as StatementItem)!">{{ row.sourceNo }}</router-link>
                <span v-else class="code">{{ row.sourceNo || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="送货单号" width="130">
              <template #default="{ row }"><span class="code">{{ row.deliveryNo || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="订单号" width="150">
              <template #default="{ row }"><span class="code">{{ row.orderNo || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="摘要" min-width="180" prop="description" />
            <el-table-column label="单位" width="60">
              <template #default="{ row }">{{ unitText(row as StatementItem) }}</template>
            </el-table-column>
            <el-table-column label="数量" width="90" class-name="num" align="right">
              <template #default="{ row }">{{ row.qty == null ? '' : formatQty(row.qty) }}</template>
            </el-table-column>
            <el-table-column label="单价" width="100" class-name="num" align="right">
              <template #default="{ row }">
                <StatusTag v-if="missingPrice(row as StatementItem)" tone="danger">缺单价</StatusTag>
                <template v-else-if="row.price != null">{{ formatPrice(row.price) }}</template>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ row.sourceType === 'DEDUCTION' ? '' : formatMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="扣款" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ row.deductAmount ? formatMoney(row.deductAmount) : '' }}</template>
            </el-table-column>
          </el-table>
        </section>

        <section v-if="detail.compare" class="block">
          <div class="block-head">
            <span>
              和对方的对账单比对：{{ detail.compare.fileName }}
              <span class="dim small">（{{ detail.compare.sheetName }}，{{ detail.compare.theirRowCount }} 行；{{ detail.compare.comparedByName ?? '' }} {{ formatDateTime(detail.compare.comparedAt) }}）</span>
            </span>
            <span class="grow" />
            <el-segmented v-model="onlyDiff" size="small" :options="[{ label: `不一致的 ${diffCount}`, value: true }, { label: '全部', value: false }]" />
            <el-button v-if="can('notes')" size="small" type="primary" :disabled="!notesDirty" :loading="savingNotes" @click="saveNotes">保存说明</el-button>
          </div>
          <p class="sub">
            对方实付 <b class="num">{{ formatMoney(detail.compare.theirTotal) }}</b>，我方 <b class="num">{{ formatMoney(detail.compare.ourTotal) }}</b>，
            差 <b class="num" :class="{ danger: Math.abs(detail.compare.diff) >= 0.01 }">{{ formatMoney(detail.compare.diff) }}</b>
            <template v-if="diffCount">；还有 <b>{{ unexplained }}</b> 处没写说明（每处都写了才能确认）</template>
          </p>
          <el-table :data="compareRows" row-key="key" scrollbar-always-on :empty-text="onlyDiff ? '全部一致' : '对方的表里没有能比的行'">
            <el-table-column label="结果" width="130" fixed="left">
              <template #default="{ row }">
                <StatusTag :tone="COMPARE_OUTCOME[(row as CompareRow).outcome].tone">{{ COMPARE_OUTCOME[(row as CompareRow).outcome].label }}</StatusTag>
              </template>
            </el-table-column>
            <el-table-column label="对方" min-width="240">
              <template #default="{ row }">
                <template v-if="(row as CompareRow).their">
                  <span class="code">{{ row.their.no || '—' }}</span>
                  <span class="dim small gap">{{ formatDate(row.their.date) }} {{ row.their.description }}</span>
                  <span class="dim small gap">第 {{ row.their.rowNos.join('、') }} 行</span>
                </template>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column label="对方金额" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ row.their ? formatMoney(row.their.amount) : '' }}</template>
            </el-table-column>
            <el-table-column label="我方" min-width="220">
              <template #default="{ row }">
                <template v-if="(row as CompareRow).ours">
                  <span class="code">{{ row.ours.no || '—' }}</span>
                  <span class="dim small gap">{{ formatDate(row.ours.date) }} {{ row.ours.description }}</span>
                </template>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column label="我方金额" width="110" class-name="num" align="right">
              <template #default="{ row }">{{ row.ours ? formatMoney(row.ours.amount) : '' }}</template>
            </el-table-column>
            <el-table-column label="差额" width="100" class-name="num" align="right">
              <template #default="{ row }"><span :class="{ danger: row.outcome !== 'MATCH' }">{{ diffText(row as CompareRow) }}</span></template>
            </el-table-column>
            <el-table-column label="说明" min-width="220" :show-overflow-tooltip="false">
              <template #default="{ row }">
                <el-input v-if="can('notes') && (row as CompareRow).outcome !== 'MATCH'" v-model="notes[(row as CompareRow).key]" size="small"
                  maxlength="255" placeholder="和对方谈的结果" />
                <span v-else>{{ (row as CompareRow).note || '' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
        <p v-else-if="can('compare')" class="dim small hint">
          对方发来 Excel 的对账单，点「上传对方对账单」自动比对（按单号、送货单号对，对不上的按日期和数量、金额找）。照片、PDF 暂时要人工核对。
        </p>
      </div>

      <div class="panels">
        <section class="panel">
          <div class="panel-head">对账单信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>供应商</dt>
              <dd><router-link class="link" :to="`/suppliers/${detail.partnerId}`">{{ detail.partnerName }}</router-link></dd>
              <dt>主体</dt>
              <dd>{{ detail.companyName }}</dd>
              <dt>期间</dt>
              <dd class="num">{{ formatDate(detail.periodStart) }} ~ {{ formatDate(detail.periodEnd) }}</dd>
              <dt>结算方式</dt>
              <dd>
                {{ detail.partnerSettleMethod ? settleMethods.labelOf(detail.partnerSettleMethod) : '—' }}
                <template v-if="detail.partnerCreditDays">，账期 {{ detail.partnerCreditDays }} 天</template>
              </dd>
              <dt>备注</dt>
              <dd>{{ detail.remark || '—' }}</dd>
              <dt>制单</dt>
              <dd>{{ detail.creatorName ?? '—' }} · {{ formatDateTime(detail.createTime) }}</dd>
              <template v-if="detail.sentTime">
                <dt>发送</dt>
                <dd>{{ detail.sentByName ?? '—' }} · {{ formatDateTime(detail.sentTime) }}</dd>
              </template>
              <template v-if="detail.auditTime">
                <dt>确认</dt>
                <dd>{{ detail.auditByName ?? '—' }} · {{ formatDateTime(detail.auditTime) }}<template v-if="detail.confirmNote"><br>{{ detail.confirmNote }}</template></dd>
              </template>
              <template v-if="detail.voidReason">
                <dt>作废原因</dt>
                <dd>{{ detail.voidReason }}</dd>
              </template>
            </dl>
          </div>
        </section>

        <section v-if="theirFiles.length" class="panel">
          <div class="panel-head">对方的对账单</div>
          <div class="panel-body">
            <a v-for="f in theirFiles" :key="f.id" class="link file" :href="f.url" target="_blank" rel="noopener">{{ f.name }}</a>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">操作记录</div>
          <div class="panel-body">
            <DocLogList ref="logRef" :load="loadLogs" :actions="STATEMENT_LOG_ACTION" :statuses="LOG_STATUSES" />
          </div>
        </section>
      </div>
    </div>

    <el-dialog v-model="dedVisible" :title="`加扣款 · ${detail?.partnerName ?? ''}`" width="min(520px, 94vw)" :before-close="dedGuard.beforeClose">
      <el-form ref="dedRef" class="form-grid" :model="ded" :rules="dedRules" label-position="top">
        <el-form-item label="日期" prop="bizDate">
          <el-date-picker v-model="ded.bizDate" value-format="YYYY-MM-DD" :disabled-date="outOfPeriod" :clearable="false" style="width: 100%" />
        </el-form-item>
        <el-form-item label="金额（元）" prop="amount">
          <el-input-number v-model="ded.amount" :min="0.01" :max="999999999999" :precision="2" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因" prop="reason" class="span-all">
          <el-input v-model="ded.reason" maxlength="500" placeholder="延期几天、什么质量问题" />
        </el-form-item>
        <el-form-item label="相关单号" class="span-all">
          <el-input v-model="ded.refNo" maxlength="64" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dedVisible = false">取消</el-button>
        <el-button type="primary" :loading="dedSaving" @click="saveDeduction">加上</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 明细、比对两张表都宽：占满整页，对账单信息、原件、操作记录排在下面一行 */
.panels {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 14px;
  align-items: start;
}

.body {
  min-width: 0;
}

.totals {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 32px;
  margin-bottom: 16px;
}

.totals > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}

.totals b {
  font-size: 18px;
}

.totals .pay b {
  color: var(--lm-accent);
}

.block {
  margin-bottom: 20px;
}

.block-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 13px;
}

.grow {
  flex: 1;
}

.gap {
  margin-left: 8px;
}

.sub {
  margin: 0 0 8px;
  font-size: 13px;
}

.hint {
  margin-top: 0;
}

.small {
  font-size: 11.5px;
}

.danger {
  color: var(--lm-danger-text);
}

.link {
  color: var(--lm-accent);
  text-decoration: none;
}

.file {
  display: block;
  font-size: 13px;
}
</style>
