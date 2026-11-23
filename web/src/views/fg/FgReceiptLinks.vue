<script setup lang="ts">
import type { FgReceiptStocked } from '@/types/fg'
import { formatQty } from '@/utils/format'
import FgReadFeedback from './FgReadFeedback.vue'
defineProps<{ companyId: string; receiptId: string; value?: FgReceiptStocked; loading: boolean; error: string | null }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="fg-receipt-links" @click.stop>
    <FgReadFeedback v-if="loading || error" :error="error" :loading="loading" label="成品仓" @retry="$emit('retry')" />
    <template v-else-if="value">
      <div class="dim small">已过账 {{ formatQty(value.postedQty) }} · 草稿 {{ formatQty(value.reservedQty) }} · 剩余 {{ formatQty(value.remainingQty) }}</div>
      <div v-if="value.documents.length > 7" class="dim small">最近 {{ value.documents.length }} 张，可滚动查看</div>
      <div v-if="value.documents.length" class="fg-receipt-preview" role="region" aria-label="相关入库单预览" :tabindex="value.documents.length > 7 ? 0 : undefined">
        <router-link v-for="doc in value.documents" :key="doc.id" class="code link fg-receipt-link" :to="`/fg-in/${doc.id}?companyId=${companyId}`">
          {{ doc.no }}<span v-if="doc.status === 'DRAFT'">（草稿）</span> · {{ formatQty(doc.qty) }} 件
        </router-link>
      </div>
      <span v-if="!value.documents.length" class="dim small">暂无可见入库单</span>
      <router-link v-if="value.hasMoreDocuments" class="link" :to="`/fg-in?tab=docs&receiptId=${receiptId}&companyId=${companyId}`">查看全部 {{ value.documentsTotal }} 张</router-link>
    </template>
    <span v-else class="dim">—</span>
  </div>
</template>

<style scoped>
.fg-receipt-preview { max-height: 143px; overflow-y: auto; }
.fg-receipt-link { display: block; line-height: 1.7; }
.small { font-size: 11.5px; }
</style>
