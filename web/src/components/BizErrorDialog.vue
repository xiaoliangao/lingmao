<script setup lang="ts">
import { computed } from 'vue'
import { useBizError } from '@/composables/useBizError'
import { ErrorCode } from '@/utils/errorCodes'
import BizErrorTable from './BizErrorTable.vue'

// 带清单的业务错误（导入没通过、库存不够……）：一行消息说不清，弹一个表格。全局只有一个，挂在 App.vue
const state = useBizError()

const TITLES: Record<number, string> = {
  [ErrorCode.STOCK_SHORTAGE]: '库存不够',
  [ErrorCode.IMPORT_INVALID]: '导入没有通过',
  [ErrorCode.OVER_LIMIT]: '超出上限',
  [ErrorCode.HAS_DOWNSTREAM]: '有下游单据',
}
const title = computed(() => TITLES[state.code] ?? '操作没有完成')
</script>

<template>
  <el-dialog v-model="state.visible" :title="title" width="min(640px, 94vw)">
    <p class="message">{{ state.message }}</p>
    <BizErrorTable :items="state.items" />
    <template #footer>
      <el-button type="primary" @click="state.visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.message {
  margin: 0 0 12px;
  color: var(--lm-danger-text);
}
</style>
