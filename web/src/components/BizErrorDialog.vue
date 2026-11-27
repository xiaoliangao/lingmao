<script setup lang="ts">
import { computed } from 'vue'
import { useBizError } from '@/composables/useBizError'
import { ErrorCode } from '@/utils/errorCodes'
import type { DocRef } from '@/types/biz'
import BizErrorTable from './BizErrorTable.vue'
import DocRefTable from './doc/DocRefTable.vue'

// 带清单的业务错误（导入没通过、库存不够……）：一行消息说不清，弹一个表格。全局只有一个，挂在 App.vue
const state = useBizError()

const TITLES: Record<number, string> = {
  [ErrorCode.STOCK_SHORTAGE]: '库存不够',
  [ErrorCode.IMPORT_INVALID]: '导入没有通过',
  [ErrorCode.OVER_LIMIT]: '超出上限',
  [ErrorCode.HAS_DOWNSTREAM]: '有下游单据',
}
const title = computed(() => TITLES[state.code] ?? '操作没有完成')

/** 1005 的清单是 DocRef（README §4.5）：照"下游单据"页签显示成 类型 / 单号 / 负责人 / 状态，点单号去那张单 */
const isDocRef = (item: unknown) => item !== null && typeof item === 'object' && 'typeLabel' in item && 'no' in item && 'statusTone' in item
const docRefs = computed(() => (state.code === ErrorCode.HAS_DOWNSTREAM && state.items.every(isDocRef) ? (state.items as DocRef[]) : null))
</script>

<template>
  <el-dialog v-model="state.visible" :title="title" width="min(920px, 94vw)">
    <p class="message">{{ state.message }}</p>
    <DocRefTable v-if="docRefs" :rows="docRefs" :max-height="360" @open="state.visible = false" />
    <BizErrorTable v-else :items="state.items" />
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
