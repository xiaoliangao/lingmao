<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ColorTab from './ColorTab.vue'
import SizeGroupTab from './SizeGroupTab.vue'
import CategoryTreeTab from './CategoryTreeTab.vue'
import WarehouseTab from './WarehouseTab.vue'
import CompanyTab from './CompanyTab.vue'

/**
 * 资料 › 基础（RFC 003 §12）：颜色、尺码组、品类、物料分类、仓库、主体公司，一个页签一种。
 * 别的页面跳过来可以带 ?tab=matcats 直接打开某个页签（物料列表的"管理分类"）。
 * 页签切过一次才加载（lazy），之后留着
 */
const TABS = ['colors', 'sizes', 'cats', 'matcats', 'whs', 'companies'] as const
type TabName = (typeof TABS)[number]

const route = useRoute()
const pick = (value: unknown): TabName | undefined => (TABS as readonly unknown[]).includes(value) ? (value as TabName) : undefined
const tab = ref<TabName>(pick(route.query.tab) ?? 'colors')

// 页面留在页签里，再从别处带 ?tab= 跳过来时换页签
watch(
  () => route.query.tab,
  (value) => {
    const name = pick(value)
    if (name && route.path === '/basics') tab.value = name
  },
)
</script>

<template>
  <div>
    <PageHeader />
    <el-tabs v-model="tab" class="basics-tabs">
      <el-tab-pane label="颜色" name="colors" lazy><ColorTab /></el-tab-pane>
      <el-tab-pane label="尺码组" name="sizes" lazy><SizeGroupTab /></el-tab-pane>
      <el-tab-pane label="品类" name="cats" lazy><CategoryTreeTab kind="category" /></el-tab-pane>
      <el-tab-pane label="物料分类" name="matcats" lazy><CategoryTreeTab kind="material" /></el-tab-pane>
      <el-tab-pane label="仓库" name="whs" lazy><WarehouseTab /></el-tab-pane>
      <el-tab-pane label="主体公司" name="companies" lazy><CompanyTab /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
/* 页签条接在页头下面、铺满一行，下面每个页签是"筛选一行 + 表格"，和列表页一样 */
.basics-tabs > :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 var(--lm-table-edge, 24px);
  background: var(--lm-paper);
}

.basics-tabs > :deep(.el-tabs__header .el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--lm-line-soft);
}
</style>
