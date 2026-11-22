<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAssistant } from '@/composables/useAssistant'
import { useFloating } from '@/composables/useFloating'
import { shortcut } from '@/utils/shortcut'

/**
 * 智能助手的图标（docs/api/08-智能助手.md §1）：卡片收着时浮在页面右下角，点开对话卡片；按住能拖到别处，位置记在本机。
 * 默认比分页条高一截，不挡列表页右下角的翻页。收着的时候还在答，图标外圈转；答完了亮个琥珀色的点；
 * 服务调不通（没起、没配模型）变灰，照样能点开看原因。AppLayout 在卡片收着时才渲染它
 */
const assistant = useAssistant()
const { state } = assistant

const SHORTCUT = shortcut('J')

const el = ref<HTMLElement>()
const float = useFloating(el, 'lingmao-assistant-launcher', { right: 24, bottom: 72 })

const title = computed(() => {
  if (assistant.unavailable.value) return `智能助手暂时用不了：${state.statusMessage}`
  if (state.busy) return '智能助手正在答，点开看'
  if (state.unread) return '智能助手答完了，点开看'
  return `智能助手（${SHORTCUT}），按住能拖`
})

function onClick() {
  if (float.dragged()) return
  assistant.show()
}
</script>

<template>
  <button
    ref="el"
    type="button"
    class="ast-launcher"
    :class="{ off: assistant.unavailable.value, busy: state.busy }"
    :style="float.style.value"
    :title="title"
    :aria-label="title"
    data-drag
    @pointerdown="float.start"
    @click="onClick"
  >
    <el-icon><ChatDotRound /></el-icon>
    <span v-if="state.unread" class="dot" aria-hidden="true" />
  </button>
</template>

<style scoped>
.ast-launcher {
  position: fixed;
  z-index: 1900;
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  padding: 0;
  font-size: 22px;
  color: var(--lm-on-accent);
  cursor: pointer;
  touch-action: none;
  background: var(--lm-accent);
  border: 0;
  border-radius: 50%;
  box-shadow: var(--lm-shadow-pop);
  transition: background 0.15s, transform 0.15s;
}

.ast-launcher:hover {
  background: var(--lm-accent-2);
  transform: translateY(-1px);
}

.ast-launcher:active {
  transform: none;
}

.ast-launcher:focus-visible {
  outline: 2px solid var(--lm-accent);
  outline-offset: 3px;
}

/* 用不了：浅底灰图标 */
.ast-launcher.off {
  color: var(--lm-muted);
  background: var(--lm-paper);
  box-shadow: var(--lm-shadow-pop), inset 0 0 0 1px var(--lm-line);
}

.ast-launcher.off:hover {
  color: var(--lm-ink-2);
  background: var(--lm-subtle);
}

/* 收着还在答：外圈转 */
.ast-launcher.busy::after {
  position: absolute;
  inset: -5px;
  content: '';
  border: 2px solid var(--lm-accent-line);
  border-top-color: var(--lm-accent);
  border-radius: 50%;
  animation: ast-spin 1s linear infinite;
}

@keyframes ast-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .ast-launcher.busy::after {
    animation-duration: 3s;
  }
}

/* 答完了没看 */
.dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 11px;
  height: 11px;
  background: var(--lm-pending-text);
  border: 2px solid var(--lm-paper);
  border-radius: 50%;
}
</style>
