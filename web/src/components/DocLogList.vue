<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onMounted, ref, watch } from 'vue'
import type { DocLog, FieldChange } from '@/types/biz'
import { formatDateTime } from '@/utils/format'

/**
 * 详情页的"操作记录"页签：什么时候、谁、做了什么、状态从哪到哪、备注（驳回原因、拉黑原因……），
 * 物料、款式、供应商的"修改"还列出改了哪些字段（"参考单价：18.5 → 20"，RFC 016 §3），多于 5 处先收起。
 * 后端每种资源一个 GET /api/<资源>/{id}/logs，返回 DocLogVO 数组，新的在前（RFC 001 §14）。
 *
 * 动作、状态存的是英文名，同一个名字在不同单据上叫法不一样（采购单的 RUNNING 叫"部分到货"），所以中文由调用方给，
 * 盖在下面的通用叫法上；两边都没有的原样显示英文名，一眼看得出漏了哪个。操作人为空是系统做的，显示"系统"。
 *
 *   const loadLogs = () => supplierApi.logs(id)
 *
 *   <el-tab-pane label="操作记录" name="logs" lazy>
 *     <DocLogList ref="logs" :load="loadLogs"
 *                 :actions="{ submit: '申请准入', audit: '准入通过', blacklist: '拉黑' }"
 *                 :statuses="SUPPLIER_STATUS_LABEL" />
 *   </el-tab-pane>
 *
 * load 在 script 里定义好再传。模板里写 `:load="() => supplierApi.logs(id)"`，箭头函数里用了 import 进来的东西，
 * vue-tsc 会报 "Property 'value' does not exist"（DocRefs、附件这类传函数的组件同理）。
 * 一进来就加载（页签加 lazy，切过来才加载）；换了 load（详情页换了 id）重新加载。
 * 详情页做完一个动作要刷新记录：logs.value?.reload()
 */
const props = defineProps<{
  /** 取这一条的操作记录，一般是 () => xxxApi.logs(id) */
  load: () => Promise<DocLog[]>
  /** 动作的中文名，如 { submit: '申请准入' } */
  actions?: Record<string, string>
  /** 状态的中文名，如 { PENDING_ADMIT: '待准入' } */
  statuses?: Record<string, string>
}>()

/** 各种单据、主数据都用得上的动作叫法；单据自己的叫法（供应商的 audit 是"准入通过"）由 actions 盖掉 */
const COMMON_ACTIONS: Record<string, string> = {
  create: '新建',
  update: '修改',
  import: '导入',
  enable: '启用',
  disable: '停用',
  submit: '提交',
  withdraw: '撤回',
  audit: '审核',
  reject: '驳回',
  unaudit: '反审核',
  void: '作废',
  close: '结案',
  images: '换图片',
}

/** 单据的通用状态（架构 §4.2）；主数据、各单据自己的状态由 statuses 给 */
const COMMON_STATUSES: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  APPROVED: '已审核',
  RUNNING: '执行中',
  DONE: '已完成',
  CLOSED: '已结案',
  VOIDED: '已作废',
}

const actionNames = computed(() => ({ ...COMMON_ACTIONS, ...props.actions }))
const statusNames = computed(() => ({ ...COMMON_STATUSES, ...props.statuses }))

const logs = ref<DocLog[]>([])
const loading = ref(false)
/** 没加载出来（错误提示已经由 request.ts 弹过了），表格里换一句话、给一个重试 */
const failed = ref(false)
/** 加载的序号：连着换了两次 load，先发的那次后回来时，不能盖掉后发的结果 */
let seq = 0

const reloadRequest = useLatestRequest()
async function reload() {
  const read = reloadRequest.begin()
  const current = ++seq
  loading.value = true
  failed.value = false
  try {
    const result = await read.run(() => props.load())
    if (!read.current()) return
    if (current === seq) logs.value = result
  } catch {
    if (!read.current()) return
    if (current === seq) {
      logs.value = []
      failed.value = true
    }
  } finally {
    if (read.current() && (current === seq)) loading.value = false
  }
}

/** 改动多于这么多处先收起，点"还有 N 处"展开 */
const DIFF_FOLD = 5
const expanded = ref(new Set<string>())
function shownDiff(log: DocLog): FieldChange[] {
  const diff = log.diff ?? []
  return expanded.value.has(log.id) ? diff : diff.slice(0, DIFF_FOLD)
}
function expand(log: DocLog) {
  expanded.value = new Set(expanded.value).add(log.id)
}
const valueText = (value: string | null) => value ?? '空'

function statusText(log: DocLog) {
  if (!log.fromStatus && !log.toStatus) return ''
  const name = (status: string | null) => (status ? (statusNames.value[status] ?? status) : '—')
  return `${name(log.fromStatus)} → ${name(log.toStatus)}`
}

onMounted(reload)
// 换了 load（详情页换了一条）：上一条展开过的记录不带过来
watch(() => props.load, () => {
  expanded.value = new Set()
  reload()
})

defineExpose({ reload })
</script>

<template>
  <el-table v-loading="loading" :data="logs" class="doc-log-list">
    <el-table-column label="时间" width="170" class-name="num">
      <template #default="{ row }">{{ formatDateTime((row as DocLog).createTime) }}</template>
    </el-table-column>
    <el-table-column label="操作人" width="110">
      <template #default="{ row }">
        <span :class="{ muted: !(row as DocLog).operatorName }">{{ (row as DocLog).operatorName ?? '系统' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="110">
      <template #default="{ row }">{{ actionNames[(row as DocLog).action] ?? (row as DocLog).action }}</template>
    </el-table-column>
    <el-table-column label="状态" min-width="150">
      <template #default="{ row }">{{ statusText(row as DocLog) }}</template>
    </el-table-column>
    <el-table-column label="备注 / 改动" min-width="280" :show-overflow-tooltip="false">
      <template #default="{ row }">
        <span v-if="(row as DocLog).remark" class="remark">{{ (row as DocLog).remark }}</span>
        <ul v-if="(row as DocLog).diff?.length" class="diff">
          <li v-for="c in shownDiff(row as DocLog)" :key="c.field">
            <span class="diff-label">{{ c.label }}</span>：<template v-if="c.hidden"><span class="muted">已修改（没有查看权限）</span></template>
            <template v-else><span class="diff-before">{{ valueText(c.before) }}</span> → {{ valueText(c.after) }}</template>
          </li>
          <li v-if="(row as DocLog).diff.length > shownDiff(row as DocLog).length">
            <el-button link type="primary" class="diff-more" @click="expand(row as DocLog)">还有 {{ (row as DocLog).diff.length - DIFF_FOLD }} 处</el-button>
          </li>
        </ul>
      </template>
    </el-table-column>
    <template #empty>
      <span v-if="failed">操作记录没加载出来 <el-button link type="primary" @click="reload">再试一次</el-button></span>
      <span v-else-if="!loading">还没有操作记录</span>
    </template>
  </el-table>
</template>

<style scoped>
/* 备注里可能有换行（调价原因、驳回原因），照原样分行 */
.remark {
  white-space: pre-line;
}

/* 改动一行一处，长的（成分、备注）照样换行 */
.diff {
  margin: 0;
  padding: 0;
  list-style: none;
  white-space: normal;
  word-break: break-all;
}

.remark + .diff {
  margin-top: 4px;
}

.diff-label {
  color: var(--lm-ink-2);
}

.diff-before {
  color: var(--lm-muted);
}

/* "还有 N 处"：表格里的链接按钮全局是灰字、左右垫 8px（element.css），这里要主色、和上面的字对齐 */
.diff .el-button.diff-more {
  height: auto;
  padding: 0;
  color: var(--lm-accent);
}
</style>
