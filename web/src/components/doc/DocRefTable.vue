<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { DocRef } from '@/types/biz'
import StatusTag from '@/components/StatusTag.vue'
import { docRoute } from '@/utils/docRoute'

/**
 * 一组"指向单据"（docs/api/README.md §4.5）的表：类型、单号、负责人、状态，点单号按 docRoute 去那张单。
 * 详情页的"下游单据"页签（DocRefs）和 1005 的错误清单（BizErrorDialog）共用。
 * 点了单号先发 open 再跳：对话框借它把自己关掉。没有页面可去的单号不做成链接
 *
 *   <DocRefTable :rows="refs" empty-text="还没有下游单据" @open="visible = false" />
 */
defineProps<{ rows: DocRef[]; loading?: boolean; emptyText?: string; maxHeight?: number }>()
const emit = defineEmits<{ open: [row: DocRef] }>()

/** 不同种类的单据 id 会重（采购单 1、生产单 1），行键带上种类（agy T-e19e38 #14） */
const rowKey = (row: DocRef) => `${row.type}-${row.id}`

const router = useRouter()
function open(row: DocRef) {
  const to = docRoute(row)
  if (!to) return
  emit('open', row)
  void router.push(to)
}
</script>

<template>
  <el-table v-loading="loading" :data="rows" :row-key="rowKey" :max-height="maxHeight" :empty-text="emptyText ?? '还没有关联的下游单据'">
    <el-table-column label="单据类型" prop="typeLabel" width="140" />
    <el-table-column label="单号" min-width="180" class-name="code">
      <template #default="{ row }">
        <button v-if="docRoute(row as DocRef)" type="button" class="doc-link code" @click="open(row as DocRef)">{{ row.no }}</button>
        <span v-else>{{ row.no }}</span>
      </template>
    </el-table-column>
    <el-table-column label="负责人" min-width="120">
      <template #default="{ row }">{{ row.ownerName ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="120">
      <template #default="{ row }">
        <StatusTag :tone="row.statusTone">{{ row.statusLabel }}</StatusTag>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.doc-link {
  padding: 0;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.doc-link:hover {
  text-decoration: underline;
}
</style>
