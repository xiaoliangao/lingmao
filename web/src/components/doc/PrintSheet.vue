<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { APP_NAME } from '@/config'

/**
 * 单据打印：页面里放一份 A4 的打印版，点"打印"时调 print()。
 *
 *   <PrintSheet ref="printRef" title="采购订单" :no="d.no">
 *     <template #meta>…单头几格…</template>
 *     <table class="lines">…</table>
 *     <template #sign>采购员、供应商确认…</template>
 *   </PrintSheet>
 *   printRef.value?.print()
 *
 * 为什么不是对话框里的预览：浏览器打印的是整页，对话框后面的列表、侧边栏会一起印出来。
 * 这里平时不渲染；print() 时才挂到 <body> 下，@media print 里只留它、把 #app 藏掉，打完（afterprint）再拿掉。
 * 页签缓存着好几张详情时，也只有正在打的那一张在。抬头用 config.ts 的系统名，不写死
 */
defineProps<{
  /** 单据名，如"采购订单""外发加工单" */
  title: string
  no: string
}>()

const active = ref(false)

function done() {
  active.value = false
  window.removeEventListener('afterprint', done)
}

async function print() {
  active.value = true
  await nextTick()
  window.addEventListener('afterprint', done)
  window.print()
}

onBeforeUnmount(done)
defineExpose({ print })
</script>

<template>
  <Teleport to="body">
    <div v-if="active" class="lm-print">
      <header class="lm-print-head">
        <span class="lm-print-app">{{ APP_NAME }}</span>
        <h1>{{ title }}</h1>
        <span class="lm-print-no">{{ no }}</span>
      </header>
      <div class="lm-print-meta"><slot name="meta" /></div>
      <slot />
      <footer v-if="$slots.sign" class="lm-print-sign"><slot name="sign" /></footer>
    </div>
  </Teleport>
</template>

<style>
/* 不加 scoped：打印时要藏的是 #app，打印版挂在 body 下 */
.lm-print {
  display: none;
}

@media print {
  @page {
    size: A4;
    margin: 12mm;
  }

  body > #app,
  body > .el-overlay,
  body > .el-popper {
    display: none !important;
  }

  .lm-print {
    display: block;
    font-size: 10.5pt;
    color: var(--lm-ink);
    background: var(--lm-paper);
  }

  .lm-print-head {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: end;
    padding-bottom: 6pt;
    margin-bottom: 8pt;
    border-bottom: 1.5pt solid var(--lm-ink);
  }

  .lm-print-head h1 {
    margin: 0;
    font-size: 16pt;
    letter-spacing: 2pt;
  }

  .lm-print-app {
    font-size: 9pt;
  }

  .lm-print-no {
    font-family: var(--lm-font-mono);
    text-align: right;
  }

  .lm-print-meta {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 3pt 16pt;
    margin-bottom: 8pt;
  }

  .lm-print table {
    width: 100%;
    margin-bottom: 8pt;
    border-collapse: collapse;
  }

  .lm-print th,
  .lm-print td {
    padding: 3pt 5pt;
    text-align: left;
    border: 0.75pt solid var(--lm-ink);
  }

  .lm-print .r {
    text-align: right;
  }

  .lm-print tr {
    break-inside: avoid;
  }

  .lm-print-sign {
    display: flex;
    justify-content: space-between;
    gap: 24pt;
    margin-top: 24pt;
  }

  .lm-print-sign span {
    flex: 1;
    padding-top: 18pt;
    border-top: 0.75pt solid var(--lm-ink);
  }
}
</style>
