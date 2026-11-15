<script setup lang="ts" generic="T">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import type { BatchAction, BatchResult, BatchTable } from './batchBar'

/**
 * 列表勾选以后，底部浮出来的批量操作条（原型的 BatchBar，RFC 003 §2）：已选几条、几个批量按钮、取消选择。
 * 不另写批量接口：点一个按钮，对勾选的逐条调单条接口（和详情页的按钮同一个接口、同一套校验）。
 * 只做现在能做的（can），其余跳过；一条出错不影响别的。做完成功的取消勾选，没成功的列出原因、留着勾，处理好可以再点一次。
 *
 *   <el-table ref="tableRef" row-key="id" :data="list" @selection-change="selected = $event">
 *     <el-table-column type="selection" width="40" reserve-selection />
 *     …
 *   </el-table>
 *   <el-pagination … />
 *   <BatchBar :rows="selected" :table="tableRef" :actions="batchActions" @done="load">
 *     <el-button size="small" @click="exportSelected">导出选中</el-button>     插槽：不是逐条调的操作
 *   </BatchBar>
 *
 *   const tableRef = ref<TableInstance>()                          import type { TableInstance } from 'element-plus'
 *   const selected = ref<Material[]>([])
 *   const batchActions: BatchAction<Material>[] = [
 *     { label: '启用', perm: 'basic:material:edit', can: (m) => m.status === 0, run: (m, o) => materialApi.enable(m.id, o) },
 *     { label: '停用', perm: 'basic:material:edit', can: (m) => m.status === 1, run: (m, o) => materialApi.disable(m.id, o),
 *       confirm: '停用后新建单据里选不到，已有单据不受影响，随时可以启用。' },
 *     { label: '删除', perm: 'basic:material:remove', danger: true, run: (m, o) => materialApi.remove(m.id, o),
 *       confirm: '删除以后找不回来。已经用过的删不掉，会列出来，请改成停用。' },
 *   ]
 *
 * 几点：
 * - run 的第二个参数是 { silent: true }，一定传给接口函数（接口函数多收一个 RequestOptions），出错由这里汇总，不一条弹一次
 * - el-table 要有 row-key，勾选列要加 reserve-selection：不然 @done 里刷新列表（换了一个新数组）时 el-table 清空全部勾选，
 *   没成功的那几条也不勾了。加了以后翻页、换筛选条件勾选也留着（"已选"会算上别的页的）；想在搜索时清空，调 tableRef.value?.clearSelection()
 * - 一条一条按顺序调，几十条要几秒，条上显示"正在停用 3 / 20"；做的时候按钮都点不了
 * - 提示里用 rowName 认出是哪一条，默认取 code、no、name、id 里第一个有的
 */
const props = defineProps<{
  /** 勾选的行（el-table 的 selection-change 给的） */
  rows: T[]
  actions?: BatchAction<T>[]
  /** el-table 的 ref：成功的取消勾选、"取消选择"清空。不传就只发 done、clear，由页面自己取消 */
  table?: BatchTable<T>
  /** 结果清单里怎么称呼一条，默认取 code、no、name、id */
  rowName?: (row: T) => string
  /** 量词，默认"条"；单据用"张" */
  unit?: string
}>()

const emit = defineEmits<{
  /** 一个批量操作做完（有成功有失败都算），页面一般拿它刷新列表 */
  done: [result: BatchResult<T>]
  /** 点了"取消选择" */
  clear: []
}>()

const store = useUserStore()

const unit = computed(() => props.unit ?? '条')
/** 有权限的按钮 */
const shown = computed(() =>
  (props.actions ?? []).filter((action) => !action.perm || [action.perm].flat().some((perm) => store.hasPerm(perm))),
)

/** 正在做的：条上显示进度，按钮都点不了 */
const running = ref<{ label: string; done: number; total: number } | null>(null)

/** 没全部成功时弹的清单 */
const reportVisible = ref(false)
const report = ref({ title: '', message: '', items: [] as { name: string; message: string }[] })

function nameOf(row: T): string {
  if (props.rowName) return props.rowName(row)
  const record = row as Record<string, unknown>
  const name = record.code ?? record.no ?? record.name ?? record.id
  return name == null ? '' : String(name)
}

async function run(action: BatchAction<T>) {
  if (running.value) return
  const rows = [...props.rows]
  const todo = action.can ? rows.filter((row) => action.can!(row)) : rows
  const skipped = rows.filter((row) => !todo.includes(row))
  if (todo.length === 0) {
    ElMessage.warning(`选中的 ${rows.length} ${unit.value}现在都不能${action.label}`)
    return
  }

  const tips = [
    action.confirm ?? `确定${action.label}选中的 ${todo.length} ${unit.value}？`,
    skipped.length > 0 ? `另外 ${skipped.length} ${unit.value}现在不能${action.label}，跳过。` : '',
  ]
  try {
    await ElMessageBox.confirm(tips.filter(Boolean).join(''), `${action.label} ${todo.length} ${unit.value}`, {
      confirmButtonText: action.label,
      cancelButtonText: '取消',
      confirmButtonClass: action.danger ? 'el-button--danger' : undefined,
    })
  } catch {
    return
  }

  const succeeded: T[] = []
  const failed: { row: T; message: string }[] = []
  running.value = { label: action.label, done: 0, total: todo.length }
  try {
    for (const row of todo) {
      try {
        await action.run(row, { silent: true })
        succeeded.push(row)
      } catch (e) {
        failed.push({ row, message: e instanceof Error ? e.message : String(e) })
      }
      if (running.value) running.value.done++
    }
  } finally {
    running.value = null
  }

  for (const row of succeeded) props.table?.toggleRowSelection(row, false)
  const doneText = succeeded.length > 0 ? `${action.label}了 ${succeeded.length} ${unit.value}` : ''
  if (failed.length > 0) {
    report.value = {
      title: succeeded.length > 0 ? `${action.label}：有 ${failed.length} ${unit.value}没成功` : `${action.label}没有成功`,
      message: `${doneText ? `${doneText}；` : ''}下面 ${failed.length} ${unit.value}没成功，还勾着，处理好可以再试一次。`,
      items: failed.map((item) => ({ name: nameOf(item.row), message: item.message })),
    }
    reportVisible.value = true
  } else {
    ElMessage.success(doneText)
  }
  emit('done', { label: action.label, succeeded, failed, skipped })
}

function clear() {
  props.table?.clearSelection()
  emit('clear')
}
</script>

<template>
  <div v-if="rows.length > 0" class="batch-bar" role="toolbar" aria-label="批量操作">
    <span class="count" aria-live="polite">
      <template v-if="running">正在{{ running.label }} <b>{{ running.done }}</b> / {{ running.total }}</template>
      <template v-else>已选 <b>{{ rows.length }}</b> {{ unit }}</template>
    </span>
    <span class="sep" />
    <el-button v-for="action in shown" :key="action.label" size="small" :disabled="!!running" @click="run(action)">
      {{ action.label }}
    </el-button>
    <slot :rows="rows" :running="!!running" />
    <span class="sep" />
    <el-button size="small" class="ghost" :disabled="!!running" @click="clear">取消选择</el-button>
  </div>

  <el-dialog v-model="reportVisible" :title="report.title" width="min(560px, 94vw)" append-to-body>
    <p class="report-message">{{ report.message }}</p>
    <el-table :data="report.items" max-height="360">
      <el-table-column prop="name" label="哪一条" min-width="140" class-name="code" />
      <el-table-column prop="message" label="原因" min-width="260" />
    </el-table>
    <template #footer>
      <el-button type="primary" @click="reportVisible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 深色胶囊，和消息提示同一种底；吸在内容区底部，表格长的时候也看得见 */
.batch-bar {
  position: sticky;
  bottom: 18px;
  z-index: 6;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: max-content;
  max-width: calc(100% - 32px);
  padding: 6px 8px 6px 14px;
  margin: 12px auto 0;
  color: var(--lm-on-accent);
  background: var(--lm-toast-bg);
  border-radius: var(--lm-radius-panel);
  box-shadow: 0 14px 34px -10px color-mix(in srgb, var(--lm-ink) 35%, transparent);
  animation: batch-in 0.15s var(--lm-ease);
}

/* 深底上的浅字：白色七成多一点，对比度约 8.8 */
.count {
  font-size: 12.5px;
  color: color-mix(in srgb, var(--lm-on-accent) 72%, transparent);
}

.count b {
  margin: 0 2px;
  font-variant-numeric: tabular-nums;
  color: var(--lm-on-accent);
}

.sep {
  width: 1px;
  height: 16px;
  background: color-mix(in srgb, var(--lm-on-accent) 18%, transparent);
}

/* 自己的按钮和插槽里页面放的按钮都改成深底上的样子 */
.batch-bar :deep(.el-button) {
  --el-button-text-color: var(--lm-on-accent);
  --el-button-bg-color: transparent;
  --el-button-border-color: color-mix(in srgb, var(--lm-on-accent) 22%, transparent);
  --el-button-hover-text-color: var(--lm-on-accent);
  --el-button-hover-bg-color: color-mix(in srgb, var(--lm-on-accent) 10%, transparent);
  --el-button-hover-border-color: color-mix(in srgb, var(--lm-on-accent) 40%, transparent);
  --el-button-active-text-color: var(--lm-on-accent);
  --el-button-active-bg-color: color-mix(in srgb, var(--lm-on-accent) 16%, transparent);
  --el-button-active-border-color: color-mix(in srgb, var(--lm-on-accent) 40%, transparent);
  --el-button-disabled-text-color: color-mix(in srgb, var(--lm-on-accent) 45%, transparent);
  --el-button-disabled-bg-color: transparent;
  --el-button-disabled-border-color: color-mix(in srgb, var(--lm-on-accent) 12%, transparent);

  margin-left: 0;
  box-shadow: none;
}

.batch-bar :deep(.el-button.ghost) {
  --el-button-text-color: color-mix(in srgb, var(--lm-on-accent) 72%, transparent);
  --el-button-border-color: transparent;
}

.report-message {
  margin: 0 0 12px;
  color: var(--lm-danger-text);
}

@keyframes batch-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
}
</style>
