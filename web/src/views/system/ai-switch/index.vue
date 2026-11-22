<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assistantApi } from '@/api/assistant'
import { aiSwitchApi } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUserStore } from '@/stores/user'
import type { AiSwitch } from '@/types/system'
import { formatDateTime } from '@/utils/format'

/**
 * 管理后台 › AI 功能（RFC 017 §3.7）：只给超级管理员。一个总开关，管所有让模型或 agent 碰系统的功能。
 * 改了立刻生效（后端每次都读：助手令牌换不到、换到的也用不了）；别人的入口看登录信息的 features.ai，刷新页面后才变。
 * 下面的表列出它管哪几档；哪一档上线了，把它的 live 改成 true。现在只有助手上线，总开关就是它的开关；
 * 第二档上线时再加各档的分开关（RFC 017 §3.7 "分开关随那一档做"）。
 * 打开前的确认按助手服务的 /assistant/info 说数据去哪（外部模型还是内网模型），服务没起也照样能开
 */
const FEATURES = [
  { name: '助手', what: '页面右下角的助手图标和 ⌘K 里问单据、库存、进度，替用户查系统、回答带单据链接；只能查不能改', live: true },
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

/** 在问助手服务数据去哪：开关转圈、再点不响应（审查 T-c5c68e #9） */
const checking = ref(false)
/** 离开这页时停掉还在问的那一下，也不再弹确认框（不然弹在别的页上） */
let infoCtrl: AbortController | null = null
let left = false
onBeforeUnmount(() => {
  left = true
  infoCtrl?.abort()
})

/**
 * 打开前说清楚数据去哪：问助手服务配的是外部模型还是内网模型；服务没起、没配模型的，说打开后助手图标是灰的、点开说"暂时用不了"。
 * 最多等 3 秒（代理连不上时可能一直挂着），等不到按没启动说。离开了这页返回 null
 */
async function whereDataGoes(): Promise<string | null> {
  const ask = '打开后，角色里勾了"使用智能助手"的人能点页面右下角的助手图标、或在 ⌘K 里问助手（只能查、不能改）。'
  const ctrl = new AbortController()
  infoCtrl = ctrl
  const timer = setTimeout(() => ctrl.abort(), 3000)
  try {
    const { model } = await assistantApi.info(ctrl.signal)
    if (model.mode === 'external') return `${ask}他们的问题和助手替他们查到的数据，会发给外部的模型服务（数据离开公司）。`
    if (model.mode === 'self-hosted') return `${ask}问题和查到的数据发给公司内网部署的模型，不出公司。`
    return `${ask}智能助手还没有配置模型（部署手册第 10 节），打开后助手图标是灰的，点开说"暂时用不了"。`
  } catch {
    if (left) return null
    return `${ask}智能助手的服务现在没有启动（部署手册第 10 节），打开后助手图标是灰的，点开说"暂时用不了"；服务起来、配好模型以后，问题和查到的数据会发给配的模型。`
  } finally {
    clearTimeout(timer)
    if (infoCtrl === ctrl) infoCtrl = null
  }
}

/** 开关不直接改值：先问一句，后端改成功了再按返回的显示 */
async function toggle(): Promise<boolean> {
  if (!data.value || checking.value || saving.value) return false
  const on = !data.value.enabled
  let message = '关闭后，谁也问不了助手，正在答的那一句再查系统会被拒；对话记录照常留着（超过保留天数的自动删），再打开接着用。'
  if (on) {
    checking.value = true
    try {
      const said = await whereDataGoes()
      if (said === null || left) return false
      message = said
    } finally {
      checking.value = false
    }
  }
  try {
    await ElMessageBox.confirm(
      message,
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
          <div v-if="data?.updatedAt" class="dim">
            上次 {{ data.updatedBy ?? '（账号查不到）' }} 在 {{ formatDateTime(data.updatedAt) }} {{ data.enabled ? '打开' : '关闭' }}
          </div>
          <div v-else-if="data" class="dim">装好以后还没人改过</div>
        </div>
        <el-switch
          :model-value="data?.enabled ?? false"
          :disabled="!data"
          :loading="saving || checking"
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
  padding: 14px 16px;
  margin-bottom: 16px;
}
.switch-title {
  font-weight: 600;
  margin-bottom: 4px;
}
</style>
