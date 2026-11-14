<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useSidebar } from '@/composables/useSidebar'

/**
 * 页头：每页最顶上一行（原型的 Head），左边标题，右边放这一页最主要的操作。吸在顶上，往下滚也看得见。
 * 标题默认取菜单名；侧边栏收起时左边多一个"展开导航"按钮。页面上不写大段说明，要说的放在按钮、空表格、确认框里。
 *
 *   <PageHeader>
 *     <template #actions><el-button type="primary" icon="Plus" @click="openCreate">新增用户</el-button></template>
 *   </PageHeader>
 *
 *   sub：标题后面一行浅灰小字（如首页的日期）
 *   back：详情页带一级面包屑，点了回列表
 *   <PageHeader :title="order.no" :back="{ label: '采购单', to: '/purchase/orders' }">
 *     <template #title><StatusTag tone="pending">待审核</StatusTag></template>
 *     <template #actions>…</template>
 *   </PageHeader>
 */
const props = defineProps<{ title?: string; sub?: string; back?: { label: string; to: string } }>()

const route = useRoute()
const { open, toggle } = useSidebar()
const text = computed(() => props.title ?? (route.meta.title as string | undefined) ?? '')
</script>

<template>
  <header class="page-header">
    <div class="crumb">
      <button v-if="!open" type="button" class="expand" title="展开导航（[）" aria-label="展开导航" @click="toggle">
        <el-icon><Expand /></el-icon>
      </button>
      <template v-if="back">
        <router-link :to="back.to" class="back">{{ back.label }}</router-link>
        <el-icon class="sep" aria-hidden="true"><ArrowRight /></el-icon>
      </template>
      <h1>{{ text }}</h1>
      <slot name="title" />
      <span v-if="sub" class="sub">{{ sub }}</span>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  position: sticky;
  top: 0;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--lm-header-height);
  padding: 0 24px;
  background: color-mix(in srgb, var(--lm-paper) 88%, transparent);
  border-bottom: 1px solid var(--lm-line);
  backdrop-filter: blur(12px);
}

.crumb {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

h1 {
  overflow: hidden;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.back {
  flex: none;
  font-size: 13.5px;
  color: var(--lm-muted);
  text-decoration: none;
  transition: color 0.12s;
}

.back:hover {
  color: var(--lm-ink);
}

.sep {
  flex: none;
  font-size: 13px;
  color: var(--lm-faint);
}

.sub {
  margin-left: 4px;
  font-size: 12.5px;
  color: var(--lm-faint);
  white-space: nowrap;
}

.expand {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-left: -6px;
  padding: 0;
  font-size: 15px;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius-sm);
}

.expand:hover {
  color: var(--lm-ink);
  background: var(--lm-sunken);
}

.actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

@media (max-width: 900px) {
  .page-header {
    padding: 0 16px;
  }

  .sub {
    display: none;
  }
}

/* 手机：按钮多了放不下，换到标题下面一行 */
@media (max-width: 600px) {
  .page-header {
    flex-wrap: wrap;
    row-gap: 8px;
    height: auto;
    min-height: var(--lm-header-height);
    padding-block: 10px;
  }

  /* 标题按自己的宽度占位，和按钮挤不下时按钮才会折到下一行 */
  .crumb {
    flex: 1 0 auto;
    max-width: 100%;
  }

  .actions {
    flex-wrap: wrap;
  }
}
</style>
