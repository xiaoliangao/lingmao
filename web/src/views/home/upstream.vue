<script setup lang="ts">
import { useSeekTable } from '@/composables/useSeekTable'
import { businessToday } from '@/utils/businessDate'
import { computed, onActivated, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type TableInstance } from 'element-plus'
import { upstreamApi } from '@/api/home'
import BatchBar from '@/components/BatchBar.vue'
import type { BatchAction } from '@/components/batchBar'
import FilterBar from '@/components/FilterBar.vue'
import FilterItem from '@/components/FilterItem.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useColumns } from '@/composables/useColumns'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import type { DocRef } from '@/types/biz'
import type { UpstreamDocType, UpstreamList, UpstreamRow } from '@/types/home'
import type { RequestOptions } from '@/utils/request'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateTime } from '@/utils/format'

defineOptions({ name: 'UpstreamPage' })

/**
 * 上游已变更（/upstream，docs/api/06 §1.2、§3）：需求订单的变更单审核后，受影响的生产单、采购单、算料单在这里一张一行，
 * 每张带最近 100 条未处理提醒和总数。从首页待办"上游已变更"一组的"还有 n 条"、其他提醒里的数进来，不进侧边栏。
 * - 每页 50 张、游标翻页；按单据种类分段；能看全部的人多"全部 / 我的"和负责人筛选
 * - "已处理"：弹小框写处理说明（选填），只处理当前展示这一批，成功后重取本页，余下的提醒继续显示
 * - 批量"标成已处理"：逐条、不弹错，没成功的留着勾、列出原因（BatchBar）
 * - 处理走各单据自己的 notices 接口（upstreamApi.handle 按 doc.type 找路径），board 只读
 * - 变更内容一条提醒一行，放不下就折行（不省略）
 */
const router = useRouter()

// 单号、"已处理"总在；单据的状态默认不显示，在"显示列"里勾
const cols = useColumns('upstream', [
  { key: 'type', label: '单据' },
  { key: 'owner', label: '负责人' },
  { key: 'time', label: '变更时间' },
  { key: 'content', label: '变更内容' },
  { key: 'status', label: '状态', hidden: true },
])

const TYPE_OPTIONS: { label: string; value: '' | UpstreamDocType }[] = [
  { label: '全部', value: '' },
  { label: '生产单', value: 'PRODUCTION_ORDER' },
  { label: '采购单', value: 'PURCHASE_ORDER' },
  { label: '算料单', value: 'MRP_CALC' },
]
const SCOPE_OPTIONS = [
  { label: '全部', value: false },
  { label: '我的', value: true },
]

const initialQuery = {
  type: '' as '' | UpstreamDocType,
  mine: false,
  ownerId: null as string | null,
}
const ownerName = ref<string | null>(null)

const tableRef = ref<TableInstance>()
const selected = ref<UpstreamRow[]>([])

/** 能不能看全部人的由列表接口返回，和首页待办的范围规则一致。 */
const canSeeAll = ref(false)
const total = ref(0)
const seek = useSeekTable<UpstreamRow, typeof initialQuery, UpstreamList & { list: UpstreamRow[] }>({
  query: initialQuery,
  fetch: async (params) => {
    const result = await upstreamApi.list({
      type: params.type || undefined,
      mine: params.mine || undefined,
      ownerId: !params.mine && params.ownerId ? params.ownerId : undefined,
      cursor: params.cursor,
      size: params.size,
    })
    return { ...result, list: result.rows }
  },
  onLoaded: (result) => { canSeeAll.value = result.canSeeAll; total.value = result.total },
})
const { query, list: rows, loading, error: loadError, load } = seek

function clearSelection() { tableRef.value?.clearSelection(); selected.value = [] }
function search() { clearSelection(); seek.search() }
function nextPage() { clearSelection(); seek.next() }
function previousPage() { clearSelection(); seek.previous() }

let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  void load()
})

function onScopeChange() {
  if (query.mine) {
    query.ownerId = null
    ownerName.value = null
  }
  search()
}
function onOwnerChange() {
  if (query.ownerId) query.mine = false
}
function clearOwner() {
  query.ownerId = null
  ownerName.value = null
}

const rowKey = (row: UpstreamRow) => `${row.doc.type}-${row.doc.id}`
/** 变更内容里的日期只写月-日，不是今年的带年份 */
const shortDay = (value: string) => {
  const date = formatDate(value)
  return date.startsWith(`${Number(businessToday().slice(0, 4))}-`) ? date.slice(5) : date
}

function open(ref: DocRef) {
  const to = docRoute(ref)
  if (to) void router.push(to)
}

/** 只处理确认时展示的提醒；不能把刷新后新出现的另一批提醒替换进来。 */
async function handleRow(row: UpstreamRow, remark: string | null, options: RequestOptions) {
  for (const notice of [...row.notices]) await upstreamApi.handle(row.doc, notice.id, remark, options)
}

// ---------- 单行"已处理" ----------
const handleVisible = ref(false)
const handleSaving = ref(false)
const handling = ref<UpstreamRow | null>(null)
const handleForm = reactive({ remark: '' })
const guard = useUnsavedChanges(handleForm, handleVisible)

function startHandle(row: UpstreamRow) {
  handling.value = { ...row, notices: [...row.notices] }
  handleForm.remark = ''
  handleVisible.value = true
}

async function confirmHandle() {
  const row = handling.value
  if (!row || handleSaving.value) return
  handleSaving.value = true
  try {
    await handleRow(row, handleForm.remark.trim() || null, { silent: true })
    handleVisible.value = false
    const displayed = rows.value.find((item) => rowKey(item) === rowKey(row))
    if (displayed) tableRef.value?.toggleRowSelection(displayed, false)
    ElMessage.success(`${row.doc.no} 的 ${row.notices.length} 条提醒已处理`)
    await load()
  } catch (e) {
    // 处理了一半：重新取，表里只剩没处理的那几条
    handleVisible.value = false
    ElMessage.error(`${row.doc.no} 没处理完：${e instanceof Error ? e.message : String(e)}`)
    void load()
  } finally {
    handleSaving.value = false
  }
}

// ---------- 批量 ----------
const batchActions: BatchAction<UpstreamRow>[] = [
  {
    label: '标成已处理',
    run: (row, options) => handleRow(row, null, options),
    confirm: '只把选中单据当前展示的提醒标成已处理（每张最多最近 100 条，不写处理说明）；其余提醒继续保留。',
  },
]
const rowName = (row: UpstreamRow) => row.doc.no
const emptyText = computed(() =>
  loading.value ? ' ' : total.value > 0 && seek.page.value > 1 ? '本页提醒已处理，可回上一页继续查看' : '没有待处理的上游变更。需求订单的变更单审核后，受影响的生产单、采购单、算料单会列在这里',
)
</script>

<template>
  <div>
    <PageHeader title="上游已变更" :back="{ label: '首页', to: '/home' }" />

    <FilterBar :columns="cols" @search="search">
      <template #left>
        <el-segmented v-model="query.type" :options="TYPE_OPTIONS" @change="search" />
        <el-segmented v-if="canSeeAll" v-model="query.mine" :options="SCOPE_OPTIONS" @change="onScopeChange" />
      </template>
      <FilterItem v-if="canSeeAll" label="负责人" :text="ownerName ?? ''" @clear="clearOwner">
        <UserSelect v-model="query.ownerId" v-model:label="ownerName" clearable @update:model-value="onOwnerChange" />
      </FilterItem>
    </FilterBar>

    <el-alert v-if="loadError" data-testid="upstream-load-error" :title="loadError" type="warning" :closable="false">
      <el-button :loading="loading" @click="load">重试</el-button>
    </el-alert>
    <el-table
      v-else
      ref="tableRef"
      v-loading="loading"
      :data="rows"
      :row-key="rowKey"
      scrollbar-always-on
      :empty-text="emptyText"
      @selection-change="selected = $event"
    >
      <el-table-column type="selection" width="40" fixed="left" reserve-selection />
      <el-table-column label="单号" width="150" fixed="left" class-name="code">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row.doc)">{{ row.doc.no }}</el-button>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.type" label="单据" width="110">
        <template #default="{ row }">{{ row.doc.typeLabel }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.status" label="状态" width="112">
        <template #default="{ row }">
          <StatusTag :tone="(row as UpstreamRow).doc.statusTone">{{ row.doc.statusLabel }}</StatusTag>
        </template>
      </el-table-column>
      <el-table-column v-if="cols.show.owner" label="负责人" width="100">
        <template #default="{ row }">{{ row.doc.ownerName ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.time" label="变更时间" width="150" class-name="num">
        <template #default="{ row }">{{ formatDateTime(row.notices[0]?.createTime) }}</template>
      </el-table-column>
      <el-table-column v-if="cols.show.content" label="变更内容" min-width="360" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <p v-if="row.noticesTotal > row.notices.length" class="notice-limit dim">显示最近 {{ row.notices.length }} 条，共 {{ row.noticesTotal }} 条</p>
          <div class="notice-list" tabindex="0" aria-label="变更提醒">
            <div v-for="n in row.notices" :key="n.id" class="notice-line">
              <span class="num dim">{{ shortDay(n.createTime) }}</span>
              <el-button link type="primary" class="code" @click="open(n.source)">{{ n.source.no }}</el-button>
              <span>：{{ n.content }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="" width="104" fixed="right" align="right" :show-overflow-tooltip="false">
        <template #default="{ row }">
          <el-button link type="primary" @click="startHandle(row as UpstreamRow)">已处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loadError" class="pagination seek-pagination" aria-label="上游提醒分页">
      <span class="dim">共 {{ total }} 张</span>
      <el-button :disabled="!seek.canPrevious.value || loading || handleSaving" @click="previousPage">上一页</el-button>
      <span>第 {{ seek.page.value }} 页</span>
      <el-button :disabled="!seek.hasMore.value || loading || handleSaving" @click="nextPage">下一页</el-button>
    </div>

    <BatchBar v-if="!loadError" :rows="selected" :table="tableRef" :actions="batchActions" :row-name="rowName" unit="张" @done="load" />

    <el-dialog v-model="handleVisible" title="标成已处理" width="420px" :before-close="guard.beforeClose">
      <p v-if="handling" class="dim tip">{{ handling.doc.typeLabel }} {{ handling.doc.no }} 当前展示的 {{ handling.notices.length }} 条提醒<template v-if="handling.noticesTotal > handling.notices.length">（共 {{ handling.noticesTotal }} 条，其余继续保留）</template></p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="处理说明（选填）">
          <el-input v-model="handleForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="比如：已和工厂确认改码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="handleSaving" @click="confirmHandle">标成已处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.seek-pagination { display: flex; align-items: center; gap: 12px; }
.notice-limit { margin: 0 0 6px; }
.notice-list { max-height: 220px; overflow: auto; }
.notice-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  line-height: 1.6;
}

.notice-line + .notice-line {
  margin-top: 2px;
}

.tip {
  margin: 0 0 12px;
  font-size: 13px;
}
</style>
