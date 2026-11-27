<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { notifyApi } from '@/api/notify'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useTable } from '@/composables/useTable'
import { NOTIFY_ERROR, NOTIFY_MESSAGE_STATUS, type NotifyEvent, type NotifyMessageRow, type NotifySettings } from '@/types/notify'
import { formatDateTime } from '@/utils/format'

/**
 * 管理后台 › 消息推送（RFC 019 §4，甲方 11-27 答 Q65）：只给超级管理员。
 * 钉钉的三个参数在部署配置里（部署手册第 11 节），这里只显示配好没有；推哪几种在这里勾，立刻生效。
 * 下面是最近发的消息：没发出去的写原因、能重发
 */
const settings = ref<NotifySettings | null>(null)
const saving = ref(false)
const testing = ref(false)

const settingsRequest = useLatestRequest()
/** 没取到（网络断了、500）：不一直转圈，给一个重试 */
const settingsFailed = ref(false)
async function loadSettings() {
  const read = settingsRequest.begin()
  try {
    const s = await read.run(() => notifyApi.settings())
    if (read.current()) {
      settings.value = s
      settingsFailed.value = false
    }
  } catch {
    if (read.current()) settingsFailed.value = true
  }
}
onMounted(() => void loadSettings())

async function toggle(event: NotifyEvent, on: boolean) {
  if (!settings.value) return
  const events = settings.value.events.filter((e) => (e.value === event ? on : e.enabled)).map((e) => e.value)
  saving.value = true
  try {
    settings.value = await notifyApi.setEvents(events)
    ElMessage.success(on ? '已打开' : '已关闭，这种消息不再发')
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  try {
    ElMessage.success(await notifyApi.test())
    void search()
  } finally {
    testing.value = false
  }
}

const failedOnly = ref(false)
const { query, page, size, pageSizes, list, total, loading, search } = useTable({
  fetch: notifyApi.messages,
  query: { failed: undefined as boolean | undefined },
})
function switchFailed(v: boolean) {
  query.failed = v ? true : undefined
  void search()
}

const retrying = ref<string | null>(null)
async function retry(row: NotifyMessageRow) {
  if (retrying.value) return
  retrying.value = row.id
  try {
    await notifyApi.retry(row.id)
    ElMessage.success('已放回发件箱，几秒内重发：过一会儿点"刷新"看发出去没有')
    await Promise.all([search(), loadSettings()])
  } finally {
    retrying.value = null
  }
}
function refresh() {
  void search()
  void loadSettings()
}

function errorText(row: NotifyMessageRow) {
  if (!row.errorCode || row.status === 'SUCCEEDED') return ''
  return NOTIFY_ERROR[row.errorCode] ?? row.errorCode
}
</script>

<template>
  <div>
    <PageHeader title="消息推送" sub="单据被驳回、货期预警发到钉钉；钉钉的参数在部署配置里" />

    <div class="page">
      <section v-loading="!settings && !settingsFailed" class="panel">
        <div class="panel-head">钉钉</div>
        <div v-if="!settings && settingsFailed" class="panel-body status">
          <span class="dim">没取到钉钉的设置</span>
          <el-button @click="loadSettings">重试</el-button>
        </div>
        <div v-if="settings" class="panel-body status">
          <div class="row">
            <StatusTag :tone="settings.configured ? 'done' : 'off'">{{ settings.configured ? '已配置' : '没配置' }}</StatusTag>
            <span v-if="settings.configured" class="dim">
              {{ settings.linkConfigured ? '消息下面带"打开"按钮，点了到手机待办' : '没配系统地址（SCM_PUBLIC_URL），消息不带"打开"按钮' }}
            </span>
            <span v-else class="dim">部署配置里填上钉钉应用的 AppKey、AppSecret、AgentId 才发消息（部署手册第 11 节），现在什么都不发</span>
          </div>
          <el-button :disabled="!settings.configured" :loading="testing" @click="test">给我发一条测试消息</el-button>
        </div>
      </section>

      <section v-if="settings" class="panel">
        <div class="panel-head">推哪几种</div>
        <div class="panel-body events">
          <label v-for="e in settings.events" :key="e.value" class="event">
            <el-checkbox :model-value="e.enabled" :disabled="saving" @change="(v) => toggle(e.value, v === true)" />
            <span>
              <b>{{ e.label }}</b>
              <span class="dim">{{ e.description }}</span>
            </span>
          </label>
        </div>
      </section>

      <div class="list-head">
        <h3>最近的消息</h3>
        <el-segmented
          v-model="failedOnly"
          :options="[{ label: '全部', value: false }, { label: `没发出去的 ${settings?.failedCount ?? ''}`, value: true }]"
          @change="switchFailed"
        />
        <el-button class="refresh" icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="list" scrollbar-always-on
        :empty-text="failedOnly ? '没有发失败的消息' : '还没发过消息：钉钉配好、有单据被驳回或者每天早上有预警时才发'">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="eventLabel" label="种类" width="110" />
        <el-table-column label="发给" width="110">
          <template #default="{ row }">{{ row.recipientName ?? '（账号查不到）' }}</template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column label="原因" min-width="220">
          <template #default="{ row }">{{ errorText(row as NotifyMessageRow) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130" fixed="right">
          <template #default="{ row }">
            <StatusTag :tone="NOTIFY_MESSAGE_STATUS[row.status as keyof typeof NOTIFY_MESSAGE_STATUS]?.tone ?? 'off'">
              {{ NOTIFY_MESSAGE_STATUS[row.status as keyof typeof NOTIFY_MESSAGE_STATUS]?.label ?? row.status }}
            </StatusTag>
            <el-button v-if="row.status === 'DEAD_LETTER' || row.status === 'CANCELLED'" link type="primary"
              :loading="retrying === row.id" :disabled="retrying !== null && retrying !== row.id" @click="retry(row as NotifyMessageRow)">重发</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="pageSizes" :total="total"
        class="pagination" layout="total, sizes, prev, pager, next" />
    </div>
  </div>
</template>

<style scoped>
.panel {
  margin-bottom: 16px;
}
.status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.status .row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.events {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.event {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
}
.event b {
  display: block;
  font-weight: 600;
}
.list-head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin: 8px 0 10px;
}

.list-head h3 {
  flex: 1;
  margin: 0;
  font-size: 15px;
}
</style>
