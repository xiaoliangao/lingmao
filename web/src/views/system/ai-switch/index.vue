<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiSwitchApi } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUserStore } from '@/stores/user'
import type { AiSwitch } from '@/types/system'
import { formatDateTime } from '@/utils/format'

/**
 * 管理后台 › AI 功能（RFC 017 §3.7）：只给超级管理员。一个总开关，管所有让模型或 agent 碰系统的功能。
 * 改了立刻生效（后端每次都读，助手令牌、agent 的请求随各自那一档上线时接上拦截）；别人的入口看登录信息的 features.ai，刷新页面后才变。
 * 下面的表列出它管哪几档。现在都还没上线，开关打开也没有变化；哪一档上线了，把它的 live 改成 true，再加它自己的分开关
 */
const FEATURES = [
  { name: '助手', what: '一句话问数、打开筛好的列表、问"为什么不能反审核"', live: false },
  { name: '识别预填', what: '拍码单、报价截图、工艺单，识别后填进新建表单，人核对了再保存', live: false },
  { name: '企查查 agent', what: '定期查供应商的工商和风险，结果送回供应商档案', live: false },
  { name: '后台归因', what: '把超耗、生产异常按原因归类', live: false },
]

const user = useUserStore()
const data = ref<AiSwitch | null>(null)
const loading = ref(false)
const saving = ref(false)

const loadRequest = useLatestRequest()
async function load() {
  const read = loadRequest.begin()
  loading.value = true
  try {
    const fetched = await read.run(() => aiSwitchApi.get())
    if (!read.current()) return
    data.value = fetched
  } catch (error) {
    if (read.current()) throw error
  } finally {
    if (read.current()) loading.value = false
  }
}

onMounted(() => void load())

/** 开关不直接改值：先问一句，后端改成功了再按返回的显示 */
async function toggle(): Promise<boolean> {
  if (!data.value) return false
  const on = !data.value.enabled
  try {
    await ElMessageBox.confirm(
      on
        ? '打开后，已上线的 AI 功能就能用：用户的问题和他能看到的数据会发给部署时配的模型。现在还没有上线的 AI 功能，打开后没有变化。'
        : '关闭后，助手和 agent 的请求马上被拒；对话、提案、风险记录都留着，再打开接着用。',
      on ? '打开 AI 功能' : '关闭 AI 功能',
      { confirmButtonText: on ? '打开' : '关闭', cancelButtonText: '取消', type: on ? 'info' : 'warning' },
    )
  } catch {
    return false
  }
  saving.value = true
  try {
    data.value = await aiSwitchApi.set(on)
    if (user.info) user.info.features = { ai: data.value.enabled }
    ElMessage.success(on ? 'AI 功能已打开' : 'AI 功能已关闭')
  } finally {
    saving.value = false
  }
  return false
}
</script>

<template>
  <div>
    <PageHeader title="AI 功能" sub="一个开关管所有 AI 功能，改了立刻生效" />

    <div v-loading="loading" class="page">
      <section class="panel switch-row">
        <div>
          <div class="switch-title">AI 功能</div>
          <div v-if="data?.updatedBy" class="dim">
            上次 {{ data.updatedBy }} 在 {{ formatDateTime(data.updatedAt) }} {{ data.enabled ? '打开' : '关闭' }}
          </div>
          <div v-else class="dim">装好以后还没人改过</div>
        </div>
        <el-switch
          :model-value="data?.enabled ?? false"
          :disabled="!data"
          :loading="saving"
          :before-change="toggle"
          active-text="开"
          inactive-text="关"
          inline-prompt
          size="large"
        />
      </section>

      <el-table :data="FEATURES" empty-text="">
        <el-table-column prop="name" label="管哪几档" width="140" />
        <el-table-column prop="what" label="做什么" min-width="320" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <StatusTag :tone="row.live ? 'active' : 'off'">{{ row.live ? '已上线' : '还没上线' }}</StatusTag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.switch-title {
  font-weight: 600;
  margin-bottom: 4px;
}
</style>
