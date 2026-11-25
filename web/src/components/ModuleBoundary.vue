<script setup lang="ts">
import { onErrorCaptured, ref } from 'vue'
import { isExpectedError } from '@/utils/request'
import { clientActionUuid } from '@/utils/clientAction'
const failed = ref(false)
const trace = ref('')
const generation = ref(0)
const copied = ref(false)
onErrorCaptured((error) => {
  // 接口错误、取消、确认框点了取消、表单校验没过是正常流程（事件处理里常直接 await 不 catch），不换掉整页
  if (isExpectedError(error)) return false
  trace.value = clientActionUuid()
  failed.value = true
  // 错误内容可能带服务端数据，控制台只记一个本地关联编号。
  console.error(`Module render failure [${trace.value}]`)
  return false
})
function retry() { failed.value = false; generation.value++; copied.value = false }
async function copy() {
  try { await navigator.clipboard.writeText(trace.value); copied.value = true } catch { copied.value = false }
}
</script>
<template>
  <section v-if="failed" class="module-failure" role="alert">
    <h3>这一部分暂时无法显示</h3>
    <p>其他页面可以继续使用。请重试，或将追踪码交给管理员。</p>
    <p class="code">{{ trace }}</p>
    <el-button @click="copy">{{ copied ? '已复制' : '复制追踪码' }}</el-button>
    <el-button type="primary" @click="retry">重试</el-button>
  </section>
  <div v-else :key="generation" class="module-content"><slot /></div>
</template>
<style scoped>
.module-content { display: contents; }
.module-failure { padding: 24px; color: var(--lm-ink); background: var(--lm-paper); }
.module-failure p { color: var(--lm-muted); }
</style>
