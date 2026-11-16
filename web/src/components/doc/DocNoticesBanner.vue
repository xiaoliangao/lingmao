<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { DocNotice } from '@/types/biz'
import { docRoute } from '@/utils/docRoute'
import { formatDateTime } from '@/utils/format'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'

/**
 * 详情顶上的黄条"上游订单已变更"（docs/api/README.md §4.3、§4.5）：pendingNotices > 0 时出现，
 * 点"查看并处理"弹出没处理的提醒，一条一条点"已处理"（可以写一句怎么处理的）。外发加工单、成品采购单、采购单、算料单用。
 *
 *   const loadNotices = () => purchaseApi.notices(id)
 *   const handleNotice = (noticeId: number, remark: string | null) => purchaseApi.handleNotice(id, noticeId, remark)
 *   <DocNoticesBanner :pending-count="detail.pendingNotices" :load="loadNotices" :handle="handleNotice" @handled="refresh" />
 *
 * load、handle 在 script 里定义好再传（见 DocLogList）。处理完一条发 handled，详情页重新取（条数跟着变）
 */
const props = defineProps<{
  pendingCount: number
  load: () => Promise<DocNotice[]>
  handle: (noticeId: number, remark: string | null) => Promise<void>
}>()

const emit = defineEmits<{
  handled: []
}>()

const router = useRouter()
const notices = ref<DocNotice[]>([])
const dialogVisible = ref(false)
const loading = ref(false)
const workingId = ref<number | null>(null)
const remarks = ref<Record<number, string>>({})
const guard = useUnsavedChanges(remarks, dialogVisible)

async function fetchNotices() {
  if (props.pendingCount <= 0 && !dialogVisible.value) {
    notices.value = []
    return
  }
  loading.value = true
  try {
    notices.value = await props.load()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.pendingCount > 0) void fetchNotices()
})

watch(
  () => props.pendingCount,
  (n) => {
    if (n > 0) void fetchNotices()
  },
)

function openDialog() {
  dialogVisible.value = true
  void fetchNotices()
}

function goSource(notice: DocNotice) {
  const to = docRoute(notice.source)
  if (to) {
    dialogVisible.value = false
    router.push(to)
  }
}

async function markHandled(notice: DocNotice) {
  workingId.value = notice.id
  try {
    const remark = (remarks.value[notice.id] ?? '').trim() || null
    await props.handle(notice.id, remark)
    // 这一条的说明已经存进去了，不算没保存的修改
    delete remarks.value[notice.id]
    ElMessage.success('已标记处理')
    await fetchNotices()
    emit('handled')
  } finally {
    workingId.value = null
  }
}
</script>

<template>
  <div v-if="pendingCount > 0" class="notice-banner">
    <div class="left">
      <span class="dot" />
      <span class="txt">
        <strong>上游订单已变更（{{ pendingCount }} 条待处理）</strong>
        <template v-if="notices[0]">：{{ notices[0].content }}</template>
      </span>
    </div>
    <div class="acts">
      <el-button size="small" @click="openDialog">查看并处理</el-button>
    </div>
  </div>

  <el-dialog v-model="dialogVisible" title="上游变更提醒" width="640px" :before-close="guard.beforeClose">
    <div v-loading="loading" class="notice-list">
      <p v-if="!notices.length" class="dim empty">暂无变更提醒</p>
      <div v-for="n in notices" :key="n.id" class="notice-card" :class="{ done: !!n.handledTime }">
        <div class="head">
          <button type="button" class="link-btn code" @click="goSource(n)">{{ n.source.typeLabel }} {{ n.source.no }}</button>
          <span class="dim">{{ formatDateTime(n.createTime) }}</span>
        </div>
        <p class="content">{{ n.content }}</p>
        <div v-if="n.handledTime" class="handled dim">
          已处理：{{ n.handledByName }} · {{ formatDateTime(n.handledTime) }}
          <template v-if="n.handleRemark">（{{ n.handleRemark }}）</template>
        </div>
        <div v-else class="do-row">
          <el-input v-model="remarks[n.id]" size="small" maxlength="120" placeholder="处理说明（选填，如：已通知工厂改裁床单）" />
          <el-button type="primary" size="small" :loading="workingId === n.id" @click="markHandled(n)">标记已处理</el-button>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.notice-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 13px;
}

.dot {
  flex: none;
  width: 8px;
  height: 8px;
  background: var(--lm-pending-text);
  border-radius: 99px;
}

.txt {
  min-width: 0;
  overflow-wrap: anywhere;
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 80px;
}

.notice-card {
  padding: 12px 14px;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.notice-card.done {
  border-color: var(--lm-line-soft);
  opacity: 0.82;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
}

.link-btn {
  padding: 0;
  font: inherit;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.link-btn:hover {
  text-decoration: underline;
}

.content {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--lm-ink);
}

.handled {
  font-size: 12px;
}

.do-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.empty {
  margin: 24px 0;
  text-align: center;
}
</style>
