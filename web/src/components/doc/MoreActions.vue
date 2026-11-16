<script setup lang="ts" generic="C extends string">
/**
 * 详情页右上角的"更多"（照供应商详情）：撤回、反审核、结案、作废、删除这类不常点、点了影响大的收在这里，
 * 顶上只留主要的几个按钮。一项都没有时不显示。
 *
 *   const moreItems = computed(() => [
 *     can('withdraw') && { command: 'withdraw', label: '撤回' },
 *     can('void') && { command: 'void', label: '作废', danger: true },
 *   ].filter(isMoreItem))
 *   <MoreActions :items="moreItems" @command="onMore" />
 */
export interface MoreItem<C extends string = string> {
  command: C
  label: string
  /** 红字：作废、删除、取消订单 */
  danger?: boolean
}

defineProps<{ items: MoreItem<C>[] }>()
const emit = defineEmits<{ command: [command: C] }>()
</script>

<template>
  <el-dropdown v-if="items.length" trigger="click" popper-class="doc-more" @command="(c: C) => emit('command', c)">
    <el-button icon="MoreFilled" aria-label="更多操作" />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="item in items" :key="item.command" :command="item.command" :class="{ danger: item.danger }">
          {{ item.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style>
/* 菜单挂在 body 下，scoped 的样式够不着 */
.doc-more .el-dropdown-menu__item.danger {
  color: var(--lm-danger-text);
}
</style>
