<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { countApi } from '@/api/inventory'
import BlankState from '@/components/BlankState.vue'
import DataPick from '@/components/DataPick.vue'
import DocLogList from '@/components/DocLogList.vue'
import ImportDialog from '@/components/ImportDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import DocAttachments from '@/components/doc/DocAttachments.vue'
import MoreActions, { type MoreItem } from '@/components/doc/MoreActions.vue'
import { isItem, runDocAction } from '@/components/doc/docUtils'
import { useDict } from '@/composables/useDict'
import { useTabsStore } from '@/stores/tabs'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import type { MaterialPickRow } from '@/types/basic'
import type { DocAction } from '@/types/biz'
import {
  COUNT_LOG_ACTION,
  COUNT_PURPOSE_LABEL,
  COUNT_STATUS,
  type CountAction,
  type CountDetail,
  type CountItem,
} from '@/types/inventory'
import type { StylePickRow } from '@/types/style'
import type { FileVO } from '@/types/system'
import { formatDate, formatDateTime, formatQty } from '@/utils/format'
import { ErrorCode } from '@/utils/errorCodes'
import { ApiError } from '@/utils/request'
import { confirmRemove, orNull } from '@/views/basic/common'

/**
 * 物料盘点单详情（/stock-takes/:id，docs/api/04 §1.5、§3.5、§4）。草稿时详情页就是表单：
 * 实盘数、备注、盘点日期、盘点人在页面上直接改，useUnsavedChanges 跟踪，加载完、保存完 markSaved。
 * - 页头：导出盘点表、导回实盘数、保存、提交、审核过账 / 驳回；加物料、没填的按账面数填、重新取账面数、撤回、反审核、删除在"更多"里
 * - 有没保存的改动时点提交、加物料、重新取账面数：先存再做
 * - "没填的按账面数填"只改页面，标成没保存，点保存才生效
 * - 1002：重新取详情；审核没过（1001 盘亏后库存是负的）弹清单
 */
const route = useRoute()
const router = useRouter()
const tabs = useTabsStore()
const user = useUserStore()

const id = Number(route.params.id)
const path = route.path
const units = useDict('unit')

const detail = ref<CountDetail | null>(null)
const notFound = ref(false)
const saving = ref(false)
const lineFilter = ref<'ALL' | 'UNFILLED' | 'DIFF'>('ALL')
const skuPicking = ref(false)
const importVisible = ref(false)
const logRef = ref<InstanceType<typeof DocLogList>>()

const draftHead = reactive({
  countDate: '',
  counterId: null as number | null,
  counterName: '' as string | null,
  remark: '',
})
const draftItems = ref<CountItem[]>([])

const can = (a: CountAction) => detail.value?.actions.includes(a) ?? false
const editable = computed(() => detail.value?.status === 'DRAFT' && can('edit'))

const guard = useUnsavedChanges(() => (editable.value ? { draftHead, items: draftItems.value.map((i) => [i.materialSkuId, i.countedQty, i.remark]) } : null))

function syncFromDetail(d: CountDetail) {
  detail.value = d
  draftHead.countDate = d.countDate
  draftHead.counterId = d.counterId
  draftHead.counterName = d.counterName
  draftHead.remark = d.remark ?? ''
  draftItems.value = structuredClone(d.items)
  guard.markSaved()
}

async function load() {
  try {
    const d = await countApi.get(id)
    syncFromDetail(d)
    tabs.rename(path, `盘点 ${d.no}`)
  } catch (e) {
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

function liveDiff(row: CountItem): number | null {
  if (row.countedQty == null) return null
  return Number((row.countedQty - row.bookQty).toFixed(3))
}

const hasDiff = (i: CountItem) => {
  const d = liveDiff(i)
  return d !== null && Math.abs(d) > 1e-6
}
const unfilledCount = computed(() => draftItems.value.filter((i) => i.countedQty === null).length)
const filterOptions = computed(() => [
  { label: `全部 ${draftItems.value.length}`, value: 'ALL' },
  { label: `没填实盘 ${unfilledCount.value}`, value: 'UNFILLED' },
  { label: `有差异 ${draftItems.value.filter(hasDiff).length}`, value: 'DIFF' },
])
const filteredItems = computed(() => {
  if (lineFilter.value === 'UNFILLED') return draftItems.value.filter((i) => i.countedQty === null)
  if (lineFilter.value === 'DIFF') return draftItems.value.filter(hasDiff)
  return draftItems.value
})
const emptyText = computed(() =>
  lineFilter.value === 'ALL' ? '还没有物料：点「更多」里的「从物料库加物料」，或者导回实盘数' : lineFilter.value === 'UNFILLED' ? '实盘数都填了' : '没有差异',
)
const showAdjusted = computed(() => detail.value?.status === 'APPROVED')
const haveSkuIds = computed(() => draftItems.value.map((i) => i.materialSkuId))

function removeLine(row: CountItem) {
  draftItems.value = draftItems.value.filter((x) => x.materialSkuId !== row.materialSkuId)
}

function fillUnfilledWithBook() {
  let n = 0
  for (const row of draftItems.value) {
    if (row.countedQty == null) {
      row.countedQty = row.bookQty
      n++
    }
  }
  ElMessage.success(n ? `${n} 行按账面数填了，点「保存」才生效` : '实盘数都填了')
}

async function saveDraft(silent = false): Promise<boolean> {
  if (!detail.value) return false
  if (!draftHead.counterId) {
    ElMessage.warning('先选盘点人')
    return false
  }
  saving.value = true
  try {
    const nextVer = await countApi.update(id, {
      version: detail.value.version,
      countDate: draftHead.countDate,
      counterId: draftHead.counterId,
      remark: orNull(draftHead.remark),
      items: draftItems.value.map((i) => ({
        materialSkuId: i.materialSkuId,
        countedQty: i.countedQty,
        remark: orNull(i.remark),
      })),
    })
    detail.value.version = nextVer
    guard.markSaved()
    if (!silent) ElMessage.success('存好了')
    await refresh()
    return true
  } catch (e) {
    // 1002：别人刚改过，重新取（页面上没存的改动会被换掉，request.ts 已经提示过）
    if (e instanceof ApiError && e.code === ErrorCode.STATE_CHANGED) {
      await refresh()
      return false
    }
    throw e
  } finally {
    saving.value = false
  }
}

async function onPickedSkus(rows: MaterialPickRow[] | StylePickRow[]) {
  if (!detail.value) return
  if (guard.dirty.value) {
    const ok = await saveDraft(true)
    if (!ok) return
  }
  const skuIds = (rows as MaterialPickRow[]).map((r) => r.skuId)
  if (!skuIds.length) return
  const updated = await countApi.addItems(id, { version: detail.value.version, skuIds })
  syncFromDetail(updated)
  ElMessage.success(`加好了，现在 ${updated.itemCount} 行`)
}

async function doRefreshBook() {
  if (!detail.value) return
  try {
    await ElMessageBox.confirm('按仓库现在的库存重新取账面数，填了的实盘数留着。', '重新取账面数', {
      confirmButtonText: '重新取',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  if (guard.dirty.value) {
    const ok = await saveDraft(true)
    if (!ok) return
  }
  const updated = await countApi.refreshBook(id, { version: detail.value.version })
  syncFromDetail(updated)
  ElMessage.success('账面数重新取好了')
}

async function doSubmit() {
  if (!detail.value) return
  if (guard.dirty.value) {
    const ok = await saveDraft(true)
    if (!ok) return
  }
  if (unfilledCount.value) {
    ElMessage.warning(`还有 ${unfilledCount.value} 行没填实盘数：补上，或者点「更多」里的「没填的按账面数填」`)
    lineFilter.value = 'UNFILLED'
    return
  }
  await doAct('submit')
}

async function doAct(act: Extract<DocAction, 'submit' | 'withdraw' | 'reject' | 'audit' | 'unaudit'>) {
  const d = detail.value
  if (!d) return
  // 拍了账面数以后仓里又有进出：审核只按差异调，不冲销这段时间的进出（04 §3.5.8）
  const note = act === 'audit' && d.movesSinceBook > 0 ? `取账面数以后这个仓又有 ${d.movesSinceBook} 笔进出，审核只按差异调。` : undefined
  const ok = await runDocAction(act, d.no, d.version, (a, body, o) => countApi.act(id, a, body, o), { note, reload: refresh })
  if (ok) await refresh()
}

async function doRemove() {
  if (!detail.value) return
  await confirmRemove(`删除 ${detail.value.no}`, '草稿删除后找不回来，库存不受影响。')
  guard.markSaved()
  await countApi.remove(id)
  ElMessage.success(`已删除 ${detail.value.no}`)
  await router.push('/stock-takes')
  tabs.close(path, '/stock-takes')
}

type MoreCommand = 'add' | 'fill' | 'refreshBook' | 'withdraw' | 'unaudit' | 'remove'
const moreItems = computed(() => {
  const items: (MoreItem<MoreCommand> | false)[] = [
    editable.value && { command: 'add', label: '从物料库加物料' },
    editable.value && { command: 'fill', label: '没填的按账面数填' },
    editable.value && can('refreshBook') && { command: 'refreshBook', label: '重新取账面数' },
    can('withdraw') && { command: 'withdraw', label: '撤回' },
    can('unaudit') && { command: 'unaudit', label: '反审核' },
    can('remove') && { command: 'remove', label: '删除', danger: true },
  ]
  return items.filter(isItem)
})

function onMore(command: MoreCommand) {
  if (command === 'add') skuPicking.value = true
  else if (command === 'fill') fillUnfilledWithBook()
  else if (command === 'refreshBook') void doRefreshBook()
  else if (command === 'remove') void doRemove()
  else void doAct(command)
}

const canExport = computed(() => user.hasPerm('inventory:count:export'))
const canImport = computed(() => editable.value && user.hasPerm('inventory:count:import'))

function exportSheet() {
  if (detail.value) void countApi.sheet(id, detail.value.no)
}

// 传给子组件的取数函数在 script 里定义（模板里写引用 import 的箭头函数 vue-tsc 会报错）
const loadLogs = () => countApi.logs(id)
const loadFiles = () => countApi.attachments(id)
const saveFiles = (files: FileVO[]) => countApi.replaceAttachments(id, files)
const LOG_STATUSES = Object.fromEntries(Object.entries(COUNT_STATUS).map(([k, v]) => [k, v.label]))
</script>

<template>
  <div>
    <PageHeader :title="detail ? detail.no : '物料盘点单'" :back="{ label: '物料盘点单', to: '/stock-takes' }">
      <template v-if="detail" #title>
        <StatusTag :tone="COUNT_STATUS[detail.status].tone">{{ COUNT_STATUS[detail.status].label }}</StatusTag>
      </template>

      <template v-if="detail" #actions>
        <el-button v-if="canExport" icon="Download" @click="exportSheet">导出盘点表</el-button>
        <el-button v-if="canImport" icon="Upload" @click="importVisible = true">导回实盘数</el-button>
        <el-button v-if="editable" :loading="saving" @click="saveDraft(false)">保存</el-button>
        <el-button v-if="can('submit')" type="primary" :loading="saving" @click="doSubmit">提交</el-button>
        <el-button v-if="can('reject')" @click="doAct('reject')">驳回</el-button>
        <el-button v-if="can('audit')" type="primary" @click="doAct('audit')">审核过账</el-button>
        <MoreActions :items="moreItems" @command="onMore" />
      </template>
    </PageHeader>

    <BlankState v-if="notFound" title="这张盘点单不存在，可能已经删了">
      <el-button @click="router.push('/stock-takes')">回盘点单列表</el-button>
    </BlankState>

    <div v-else-if="detail" class="page detail">
      <div class="body">
        <div class="toolbar">
          <el-segmented v-model="lineFilter" :options="filterOptions" />
        </div>

        <el-table :data="filteredItems" row-key="materialSkuId" :empty-text="emptyText">
            <el-table-column label="#" prop="seq" width="46" class-name="num" />
            <el-table-column label="物料" min-width="200">
              <template #default="{ row }">
                <span class="code">{{ row.skuCode }}</span>
                <div class="dim small">{{ row.materialName }}<template v-if="row.colorName"> · {{ row.colorName }}</template></div>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="60">
              <template #default="{ row }">{{ units.labelOf(row.stockUnit) }}</template>
            </el-table-column>
            <el-table-column label="账面数" width="100" class-name="num" align="right">
              <template #default="{ row }">{{ formatQty(row.bookQty) }}</template>
            </el-table-column>
            <el-table-column label="实盘数" width="135" class-name="num" align="right">
              <template #default="{ row }">
                <el-input-number
                  v-if="editable"
                  v-model="row.countedQty"
                  size="small"
                  :min="0"
                  :precision="3"
                  :controls="false"
                  :value-on-clear="null"
                  placeholder="未填"
                />
                <span v-else>{{ row.countedQty == null ? '—' : formatQty(row.countedQty) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="差异" width="110" class-name="num" align="right">
              <template #default="{ row }">
                <template v-if="liveDiff(row as CountItem) !== null">
                  <span :class="{ gain: (liveDiff(row as CountItem) ?? 0) > 0, loss: (liveDiff(row as CountItem) ?? 0) < 0 }">
                    {{ (liveDiff(row as CountItem) ?? 0) > 0 ? '+' : '' }}{{ formatQty(liveDiff(row as CountItem)) }}
                  </span>
                </template>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column v-if="showAdjusted" label="已调整" width="100" class-name="num" align="right">
              <template #default="{ row }">{{ row.adjustedQty === null ? '—' : formatQty(row.adjustedQty) }}</template>
            </el-table-column>
            <el-table-column label="备注" min-width="150">
              <template #default="{ row }">
                <el-input v-if="editable" v-model="row.remark" size="small" maxlength="100" placeholder="差异原因" />
                <span v-else class="dim">{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="editable" label="" width="52" align="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" @click="removeLine(row as CountItem)">删</el-button>
              </template>
            </el-table-column>
        </el-table>
      </div>

      <aside class="doc-aside">
        <section class="panel">
          <div class="panel-head">盘点单信息</div>
          <div class="panel-body">
            <dl class="doc-props">
              <dt>仓库</dt>
              <dd>{{ detail.warehouseName }}</dd>
              <dt>用途</dt>
              <dd>{{ COUNT_PURPOSE_LABEL[detail.purpose] }}</dd>
              <dt>范围</dt>
              <dd>{{ detail.scopeText }}</dd>
              <dt>取账面数</dt>
              <dd class="num">{{ formatDateTime(detail.bookTime) }}</dd>
              <dt>盘点日期</dt>
              <dd>
                <el-date-picker v-if="editable" v-model="draftHead.countDate" size="small" type="date" value-format="YYYY-MM-DD" :clearable="false" />
                <span v-else class="num">{{ formatDate(detail.countDate) }}</span>
              </dd>
              <dt>盘点人</dt>
              <dd>
                <UserSelect v-if="editable" v-model="draftHead.counterId" :label="draftHead.counterName" />
                <span v-else>{{ detail.counterName }}</span>
              </dd>
              <dt>备注</dt>
              <dd>
                <el-input v-if="editable" v-model="draftHead.remark" size="small" maxlength="160" />
                <span v-else>{{ detail.remark || '—' }}</span>
              </dd>
            </dl>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">附件</div>
          <div class="panel-body">
            <DocAttachments :load="loadFiles" :save="saveFiles" />
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">操作记录</div>
          <div class="panel-body">
            <DocLogList ref="logRef" :load="loadLogs" :actions="COUNT_LOG_ACTION" :statuses="LOG_STATUSES" />
          </div>
        </section>
      </aside>
    </div>

    <DataPick v-model="skuPicking" kind="sku" :have="haveSkuIds" @pick="onPickedSkus" />

    <ImportDialog
      v-if="detail"
      v-model="importVisible"
      title="导回实盘数"
      :template-url="countApi.sheetUrl(id)"
      :import-url="countApi.importUrl(id)"
      @success="refresh"
    />
  </div>
</template>

<style scoped>
.detail {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 1180px) {
  .detail {
    grid-template-columns: 1fr;
  }
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.small {
  font-size: 11.5px;
}

.gain {
  font-weight: 600;
  color: var(--lm-done-text);
}

.loss {
  font-weight: 600;
  color: var(--lm-danger-text);
}

:deep(.el-input-number--small) {
  width: 92px;
}
</style>
