<script setup lang="ts">
import { accountChanged, updateAvailable } from '@/utils/uiHealth'
const reload = () => location.reload()
</script>
<template>
  <div v-if="accountChanged || updateAvailable" class="recovery-banner" role="alert">
    <span>{{ accountChanged ? '另一个标签页已切换账号。本页已停止提交，请确认账号后重新打开。' : '页面资源已更新或暂时无法加载，请刷新后重试。' }}</span>
    <el-button size="small" @click="reload">重新打开</el-button>
  </div>
</template>
<style scoped>
.recovery-banner { position: fixed; top: 8px; left: 50%; transform: translateX(-50%); z-index: 10000; display: flex; gap: 12px; align-items: center; max-width: calc(100vw - 24px); padding: 12px 16px; color: var(--lm-pending-text); background: var(--lm-pending-bg); border: 1px solid var(--lm-pending-line); border-radius: var(--lm-radius); box-shadow: var(--lm-shadow-pop); }
</style>
