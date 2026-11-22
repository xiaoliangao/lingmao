<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import ProductionTable, { productionColumns } from '@/views/production/order/ProductionTable.vue'
import { useColumns } from '@/composables/useColumns'
import { useUserStore } from '@/stores/user'
import { setSessionSubject } from '@/utils/token'
import type { ProductionRow, ProductionStatus } from '@/types/production'

const user = useUserStore()
user.info = { id: '9007199254740993', username: 'synthetic', nickname: '齐料回归', roles: [], menus: [], perms: ['production:board:list'], defaultCompany: { id: '1', name: '合成主体' } }
setSessionSubject(user.info.id)
const route = useRoute()
const columns = useColumns('s3-production-kitting', productionColumns('board'))
for (const key of ['factory', 'due', 'nodes', 'issues', 'merch', 'returned'] as const) columns.show[key] = false
function row(index: number, status: ProductionStatus): ProductionRow {
  return {
    id: String(9007199254741000n + BigInt(index)), no: `JG-SYN-${index}`, kind: 'OUTSOURCE', status, version: 1,
    factoryId: '1', factoryShortName: '合成加工厂', processMode: 'CMT', orderId: String(9007199254742000n + BigInt(index)),
    orderNo: `DD-SYN-${index}`, styleId: '1', styleCode: `KS-SYN-${index}`, styleName: '合成测试款', image: null,
    totalQty: 100, dueDate: '2026-12-15', expectDate: null, daysLeft: 23, nodes: [], currentNodeName: null,
    issueStatus: null, returnedQty: 0, defectQty: 0, openIssueCount: 0, firstOpenIssueType: null,
    merchId: user.info!.id, merchName: '合成跟单员', risk: { late: false, near: false, nodeLate: false, issue: false, upstream: false }, actions: [],
  }
}
const rows = ref<ProductionRow[]>([row(1, 'RUNNING'), row(91, 'DRAFT')])
function showPage(index: number) { rows.value = [row(index, 'RUNNING'), row(index + 90, 'DRAFT')] }
</script>

<template>
  <main class="kitting-fixture">
    <header><h1>生产看板 · 齐料状态回归</h1><output data-testid="route">{{ route.path }}</output></header>
    <nav aria-label="合成父页面翻页">
      <el-button @click="showPage(2)">第二页</el-button>
      <el-button @click="showPage(3)">慢页</el-button>
      <el-button @click="showPage(4)">新页</el-button>
    </nav>
    <ProductionTable mode="board" :rows="rows" :loading="false" :columns="columns" empty-text="没有生产单" />
  </main>
</template>
<style scoped>
.kitting-fixture { padding: 24px; min-height: 100vh; background: var(--lm-bg); }
header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }
h1 { font-size: 16px; margin: 0; }
nav { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
