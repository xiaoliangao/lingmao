<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import { computed, onMounted, ref, watch } from 'vue'
import type { DocLog } from '@/types/biz'
import { formatDateTime } from '@/utils/format'

/**
 * 详情页的"操作记录"页签：什么时候、谁、做了什么、状态从哪到哪、备注（驳回原因、拉黑原因……）。
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
  /** 窄侧栏按记录分行显示；省略时保留完整五列表格 */
  compact?: boolean
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

function statusText(log: DocLog) {
  if (!log.fromStatus && !log.toStatus) return ''
  const name = (status: string | null) => (status ? (statusNames.value[status] ?? status) : '—')
  return `${name(log.fromStatus)} → ${name(log.toStatus)}`
}

onMounted(reload)
watch(() => props.load, reload)

defineExpose({ reload })
</script>

<template>
  <div v-if="compact" class="doc-log-list doc-log-compact" :aria-busy="loading">
    <p v-if="loading" class="log-feedback muted" role="status">正在加载操作记录…</p>
    <p v-else-if="failed" class="log-feedback" role="alert">
      操作记录没加载出来 <el-button link type="primary" @click="reload">再试一次</el-button>
    </p>
    <ol v-else-if="logs.length" class="log-entries" aria-label="操作记录">
      <li v-for="log in logs" :key="log.id" class="log-entry">
        <time class="log-time num muted">{{ formatDateTime(log.createTime) }}</time>
        <div class="log-person"><span class="muted">操作人 · </span>{{ log.operatorName ?? '系统' }}</div>
        <div class="log-transition">
          <span class="log-action">{{ actionNames[log.action] ?? log.action }}</span>
          <span v-if="statusText(log)" class="log-status muted">{{ statusText(log) }}</span>
        </div>
        <p v-if="log.remark" class="log-remark remark">{{ log.remark }}</p>
      </li>
    </ol>
    <p v-else class="log-feedback muted">还没有操作记录</p>
  </div>
  <el-table v-else v-loading="loading" :data="logs" class="doc-log-list">
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
    <el-table-column label="备注" min-width="220">
      <template #default="{ row }">
        <span class="remark">{{ (row as DocLog).remark }}</span>
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

.doc-log-compact {
  min-width: 0;
  font-size: 12px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.log-entries {
  margin: 0;
  padding: 0;
  list-style: none;
}

.log-entry + .log-entry {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--lm-line-soft);
}

.log-time {
  display: block;
  font-size: 11.5px;
}

.log-transition {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
}

.log-action {
  font-weight: 600;
}

.log-remark {
  margin: 4px 0 0;
}

.log-feedback {
  margin: 0;
}
</style>
