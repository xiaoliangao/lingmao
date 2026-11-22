<script setup lang="ts">
import { ref, watch } from 'vue'
import type { DocLog } from '@/types/biz'
import { formatDateTime } from '@/utils/format'

/**
 * 被驳回、退回草稿的单据，正文顶上一条红条："张三 11-22 10:30 驳回：原因"，制单人一眼知道要改什么（走查 T-7d453c）。
 * 需求订单、采购单、生产单这几种有审批的单据用；只在草稿时查一次操作记录（新的在前），
 * 从最新往回找：先碰到"驳回"就显示，先碰到"提交"（重新提交过了）就不显示；中间改过草稿不影响。
 *
 *   <RejectNotice :status="detail.status" :version="detail.version" :load="loadLogs" />
 *
 * version 变了（做了动作、存了草稿）重查。查失败不提示，不显示这条
 */
const props = defineProps<{
  status: string
  version?: number
  load: () => Promise<DocLog[]>
}>()

const rejected = ref<DocLog | null>(null)
let seq = 0

watch(
  () => [props.status, props.version] as const,
  async ([status]) => {
    const mine = ++seq
    if (status !== 'DRAFT') {
      rejected.value = null
      return
    }
    try {
      const logs = await props.load()
      if (mine !== seq) return
      const last = logs.find((l) => l.action === 'reject' || l.action === 'submit')
      rejected.value = last?.action === 'reject' ? last : null
    } catch {
      if (mine === seq) rejected.value = null
    }
  },
  { immediate: true },
)
</script>

<template>
  <div v-if="rejected" class="reject-banner" role="status">
    <strong>{{ rejected.operatorName ?? '审核人' }} {{ formatDateTime(rejected.createTime) }} 驳回了</strong>
    <template v-if="rejected.remark">：{{ rejected.remark }}</template>
    <span class="hint">改好后重新提交审核</span>
  </div>
</template>

<style scoped>
.reject-banner {
  padding: 10px 14px;
  margin-bottom: 14px;
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
  border: 1px solid var(--lm-danger-line);
  border-radius: var(--lm-radius);
  overflow-wrap: anywhere;
}

.hint {
  margin-left: 8px;
}
</style>
