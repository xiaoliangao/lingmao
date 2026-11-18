<script setup lang="ts">
/**
 * 右下角的"正在核对 n 笔提交"（docs/api/README §2「提交编号」）。建单时网络中断、不确定有没有保存的，
 * 系统用提交编号自动问服务器，问出结果弹通知；还没问出来的（比如网络一直不通）在这里列着，不用人去点"确认"。
 * App 打开时核对一次刷新前留下的
 */
import { onMounted } from 'vue'
import { formatDateTime } from '@/utils/format'
import { pendingWriteNotices, resolvePendingWrites } from '@/utils/request'

onMounted(() => { void resolvePendingWrites() })
</script>

<template>
  <el-popover v-if="pendingWriteNotices.length" placement="top-end" :width="320" trigger="click">
    <template #reference>
      <el-button class="pending-actions-trigger" round>正在核对 {{ pendingWriteNotices.length }} 笔提交</el-button>
    </template>
    <p class="pending-actions-tip">网络中断时不确定有没有保存的提交。系统会自动核对，有结果会提醒你，不会重复保存。</p>
    <ul class="pending-actions-list">
      <li v-for="notice in pendingWriteNotices" :key="notice.key">
        <span>{{ notice.title }}</span>
        <span class="pending-actions-time">{{ formatDateTime(notice.createdAt) }}{{ notice.checking ? ' · 核对中' : '' }}</span>
      </li>
    </ul>
    <el-button size="small" @click="resolvePendingWrites()">现在核对</el-button>
  </el-popover>
</template>

<style scoped>
.pending-actions-trigger { position: fixed; right: 20px; bottom: 20px; z-index: 2200; box-shadow: var(--lm-shadow-pop); }
.pending-actions-tip { margin: 0 0 8px; line-height: 1.6; color: var(--lm-muted); }
.pending-actions-list { margin: 0 0 12px; padding: 0; list-style: none; }
.pending-actions-list li { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0; border-bottom: 1px solid var(--lm-line-soft); }
.pending-actions-time { color: var(--lm-muted); white-space: nowrap; }
</style>
