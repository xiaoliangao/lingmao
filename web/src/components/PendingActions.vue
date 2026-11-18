<script setup lang="ts">
import { ElMessageBox } from 'element-plus'
import { formatDateTime } from '@/utils/format'
import { acknowledgeManualWrite, acknowledgePendingWrite, checkPendingWrite, pendingWriteDialog, pendingWriteNotices } from '@/utils/request'
async function manualCheck(scope: string) {
  try {
    await ElMessageBox.confirm('请确认已在业务列表或详情核对本次结果。结束后再次提交会被视为新操作。', '核对提交结果', { confirmButtonText: '已核对，结束本次操作', cancelButtonText: '继续核对' })
    acknowledgeManualWrite(scope)
  } catch { /* Keep the unresolved receipt. */ }
}
</script>
<template>
  <el-button v-if="pendingWriteNotices.length" class="pending-actions-trigger" type="warning" @click="pendingWriteDialog = true">{{ pendingWriteNotices.length }} 笔操作待确认</el-button>
  <el-dialog v-model="pendingWriteDialog" title="待确认操作" width="min(600px, 94vw)">
    <section v-for="notice in pendingWriteNotices" :key="notice.key" class="pending-action">
      <h4>{{ notice.title }}</h4>
      <p>{{ formatDateTime(notice.createdAt) }} · 提交编号 <span class="code">{{ notice.key }}</span></p>
      <p>{{ notice.message }}</p>
      <el-button v-if="notice.manualCheck" @click="manualCheck(notice.key)">已在业务页面核对结果</el-button>
      <el-button v-if="notice.state !== 'confirmed'" :disabled="!notice.canRetry" :loading="notice.state === 'checking'" @click="checkPendingWrite(notice.key)">重试原提交并核对</el-button>
      <el-button v-else type="primary" @click="acknowledgePendingWrite(notice.key)">已核对，开始新动作</el-button>
    </section>
  </el-dialog>
</template>
<style scoped>
.pending-actions-trigger { position: fixed; right: 20px; bottom: 20px; z-index: 2200; }
.pending-action { padding: 8px 0 16px; border-bottom: 1px solid var(--lm-line-soft); }
.pending-action p { line-height: 1.6; color: var(--lm-muted); }
</style>
