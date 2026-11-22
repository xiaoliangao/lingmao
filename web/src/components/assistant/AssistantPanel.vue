<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MAX_QUESTION, useAssistant, type ToolStep } from '@/composables/useAssistant'
import { useFloating } from '@/composables/useFloating'
import { stripHtml } from '@/utils/assistantText'
import type { AssistantConversation } from '@/types/assistant'
import { formatDateTime } from '@/utils/format'
import { isComposing } from '@/utils/ime'
import AssistantAnswer from './AssistantAnswer'

/**
 * 智能助手的对话卡片（docs/api/08-智能助手.md §1）：浮在页面右下角，不占页面的地方。按住标题栏能拖，位置记在本机；
 * "放大"变宽变高（双击标题栏也行），"收起"回到右下角的图标（AssistantLauncher）。AppLayout 在卡片开着时才渲染它，
 * 对话在 useAssistant 里，收起再打开还在、在答的接着答。
 * 用户的问题、答的过程中模型的原话只当纯文本显示；答完的全文交给 AssistantAnswer
 */
const assistant = useAssistant()
const { state } = assistant

const IS_MAC = /Mac|iPhone|iPad/.test(navigator.userAgent)
const SHORTCUT = IS_MAC ? '⌘J' : 'Ctrl+J'
const EXAMPLES = ['今天有哪些待我处理的事', '最近两周要交货的订单', '各工厂的生产情况怎么样']

const card = ref<HTMLElement>()
/** 放大前在哪：还原时回到那里（放大后又拖过的，就留在拖到的地方） */
let beforeLarge: { right: number; bottom: number } | null = null
const float = useFloating(card, 'lingmao-assistant-card', { right: 24, bottom: 24 }, () => {
  beforeLarge = null
})

const LARGE_KEY = 'lingmao-assistant-large'
const large = ref(readLarge())

function readLarge() {
  try {
    return localStorage.getItem(LARGE_KEY) === '1'
  } catch {
    return false
  }
}

/** 放大 / 还原：变了尺寸，露不全的拉回窗口里 */
async function toggleLarge() {
  if (!large.value) beforeLarge = { ...float.pos }
  large.value = !large.value
  try {
    localStorage.setItem(LARGE_KEY, large.value ? '1' : '0')
  } catch {
    // 存不了就只在这次打开里有效
  }
  await nextTick()
  if (!large.value && beforeLarge) Object.assign(float.pos, beforeLarge)
  if (!large.value) beforeLarge = null
  float.clamp()
}

function onHeadDblclick(event: MouseEvent) {
  if (!(event.target as HTMLElement | null)?.closest('button, .el-dropdown')) void toggleLarge()
}

const body = ref<HTMLElement>()
const input = ref<{ focus: () => void }>()
/** 看着最底下：新内容来了跟着滚；往上翻着看的时候不打扰 */
let stick = true

const canSend = computed(() => !!state.draft.trim() && !state.busy && !assistant.unavailable.value)

const modeText = computed(() => {
  if (state.mode === 'external') return '你的问题和助手查到的数据会发给外部的模型服务。'
  if (state.mode === 'self-hosted') return '用的是公司内网部署的模型，数据不出公司。'
  return ''
})

function toolText(step: ToolStep) {
  if (step.status === 'running') return `查${step.label}…`
  if (step.status === 'failed') return `${step.label}没查成`
  if (step.status === 'stopped') return `${step.label}没查完`
  return step.rows === null ? `查了${step.label}` : `查了${step.label}（${step.rows} 行）`
}

function onScroll() {
  const el = body.value
  if (el) stick = el.scrollHeight - el.scrollTop - el.clientHeight < 48
}

async function toBottom(force = false) {
  if (force) stick = true
  if (!stick) return
  await nextTick()
  body.value?.scrollTo({ top: body.value.scrollHeight })
}

// 内容一变（新问题、一段段的原话、工具进度、答完）就跟着滚
watch(
  () => {
    const last = state.turns.at(-1)
    return [state.turns.length, last?.draft.length, last?.answer, last?.tools.length, last?.state]
  },
  () => void toBottom(),
)

function submit() {
  if (!canSend.value) return
  const text = state.draft
  state.draft = ''
  void toBottom(true)
  void assistant.ask(text)
}

function onKeydown(event: KeyboardEvent | Event) {
  const e = event as KeyboardEvent
  if (e.key === 'Enter' && !e.shiftKey && !isComposing(e)) {
    e.preventDefault()
    submit()
  }
}

/**
 * 卡片里任何地方按 Esc 收起（焦点在示例问题、按钮、回答上也算）。
 * 输入法选词时按 Esc 是取消候选，不收；最近的对话下拉开着时让下拉先关
 */
function onCardKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape' || isComposing(e) || historyOpen) return
  e.preventDefault()
  assistant.hide()
}

function askExample(text: string) {
  if (assistant.unavailable.value) return
  void toBottom(true)
  void assistant.ask(text)
}

/** 点了回答里的链接：窄屏上卡片差不多盖满页面，收起来 */
function onNavigate() {
  if (window.matchMedia('(max-width: 900px)').matches) assistant.hide()
}

/** 最近的对话下拉开着没有。关的时候晚一拍再记：Esc 关下拉的那一下冒泡到卡片时，还当它开着 */
let historyOpen = false
let historyTimer: ReturnType<typeof setTimeout> | undefined

function onHistory(visible: boolean) {
  clearTimeout(historyTimer)
  if (visible) {
    historyOpen = true
    void assistant.loadHistory()
  } else {
    historyTimer = setTimeout(() => (historyOpen = false), 0)
  }
}

/** 最近的对话里点删除：说清后果再删；删的是正开着的，卡片清空 */
async function removeConversation(c: AssistantConversation) {
  try {
    await ElMessageBox.confirm(`"${c.title}"的问答都会删掉，找不回来。`, '删除对话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    if (await assistant.deleteConversation(c.id)) ElMessage.success('已删除')
  } catch (e) {
    if (e instanceof Error && e.message) ElMessage.error(e.message)
  }
}

async function openConversation(id: string) {
  await assistant.openConversation(id)
  void toBottom(true)
}

onMounted(async () => {
  await toBottom(true)
  input.value?.focus()
})
onBeforeUnmount(() => clearTimeout(historyTimer))
</script>

<template>
  <aside ref="card" class="ast" :class="{ large }" :style="float.style.value" aria-label="智能助手" @keydown="onCardKeydown">
    <header class="ast-head" title="按住拖动，双击放大或还原" @pointerdown="float.start" @dblclick="onHeadDblclick">
      <el-icon class="ast-mark"><ChatDotRound /></el-icon>
      <h2>智能助手</h2>
      <el-dropdown trigger="click" placement="bottom-end" :disabled="state.busy" @visible-change="onHistory" @command="openConversation">
        <button type="button" class="ast-icon" title="最近的对话" aria-label="最近的对话" :disabled="state.busy">
          <el-icon><Clock /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu class="ast-history">
            <li v-if="state.historyLoading" class="ast-history-note" role="presentation">正在加载…</li>
            <li v-else-if="state.historyError" class="ast-history-note" role="presentation">{{ state.historyError }}</li>
            <li v-else-if="!state.history.length" class="ast-history-note" role="presentation">还没有问过，问过的对话会列在这里</li>
            <template v-else>
              <el-dropdown-item v-for="c in state.history" :key="c.id" :command="c.id">
                <span class="ast-history-text">
                  <span class="ast-history-title">{{ c.title }}</span>
                  <small>{{ formatDateTime(c.updatedAt) }}</small>
                </span>
                <button type="button" class="ast-history-del" title="删除这个对话" aria-label="删除这个对话" @click.stop="removeConversation(c)">
                  <el-icon><Delete /></el-icon>
                </button>
              </el-dropdown-item>
            </template>
            <li v-if="state.retentionDays && !state.historyLoading" class="ast-history-note ast-history-foot" role="presentation">
              对话留 {{ state.retentionDays }} 天，超过的自动删掉
            </li>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <button type="button" class="ast-icon" title="新对话" aria-label="新对话" :disabled="!state.turns.length && !state.openError" @click="assistant.newChat()">
        <el-icon><Plus /></el-icon>
      </button>
      <button type="button" class="ast-icon" :title="large ? '还原' : '放大'" :aria-label="large ? '还原' : '放大'" @click="toggleLarge">
        <el-icon><ScaleToOriginal v-if="large" /><FullScreen v-else /></el-icon>
      </button>
      <button type="button" class="ast-icon" :title="`收起（${SHORTCUT}）`" aria-label="收起智能助手" @click="assistant.hide()">
        <el-icon><Minus /></el-icon>
      </button>
    </header>

    <div ref="body" class="ast-body" :aria-busy="state.busy" @scroll="onScroll">
      <div v-if="assistant.unavailable.value" class="ast-notice" role="status">
        <span>暂时用不了：{{ state.statusMessage }}</span>
        <el-button link type="primary" :loading="state.checking" @click="assistant.checkHealth(true)">再试一次</el-button>
      </div>
      <p v-if="state.openError" class="ast-error" role="alert">{{ state.openError }}</p>

      <div v-if="!state.turns.length" class="ast-empty">
        <p>问单据、库存、进度，助手替你查系统，用几句话回答。只能查，不能改。</p>
        <button
          v-for="q in EXAMPLES"
          :key="q"
          type="button"
          class="ast-example"
          :disabled="state.busy || assistant.unavailable.value"
          @click="askExample(q)"
        >
          {{ q }}
        </button>
        <p v-if="modeText" class="ast-dim">{{ modeText }}</p>
      </div>

      <div v-for="turn in state.turns" :key="turn.id" class="ast-turn">
        <div class="ast-q">{{ turn.question }}</div>
        <div class="ast-a">
          <ul v-if="turn.tools.length" class="ast-tools">
            <li v-for="(step, i) in turn.tools" :key="i" :class="step.status">
              <el-icon v-if="step.status === 'running'" class="is-loading"><Loading /></el-icon>
              <el-icon v-else-if="step.status === 'ok'"><Check /></el-icon>
              <el-icon v-else-if="step.status === 'stopped'"><Remove /></el-icon>
              <el-icon v-else><Warning /></el-icon>
              {{ toolText(step) }}
            </li>
          </ul>
          <AssistantAnswer v-if="turn.answer !== null" :text="turn.answer" @navigate="onNavigate" />
          <p v-else-if="turn.draft" class="ast-draft">{{ stripHtml(turn.draft) }}<span v-if="turn.state === 'asking'" class="ast-caret" /></p>
          <p v-else-if="turn.state === 'asking'" class="ast-dim">{{ turn.tools.length ? '整理回答…' : '在想…' }}</p>
          <p v-if="turn.state === 'failed'" class="ast-error">{{ turn.error }}</p>
          <p v-else-if="turn.state === 'stopped'" class="ast-dim">已停止</p>
        </div>
      </div>
    </div>

    <form class="ast-foot" @submit.prevent="submit">
      <el-input
        ref="input"
        v-model="state.draft"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 6 }"
        :maxlength="MAX_QUESTION"
        resize="none"
        placeholder="比如：PO2026112001 审了没有"
        aria-label="问智能助手"
        @keydown="onKeydown"
      />
      <el-button v-if="state.busy" native-type="button" @click="assistant.stop()">停止</el-button>
      <el-button v-else type="primary" native-type="submit" :disabled="!canSend">发送</el-button>
    </form>
    <p class="ast-hint">回车发出，Shift+回车换行。回答可能有错，以页面上的为准。</p>
  </aside>
</template>

<style scoped>
/* 浮在页面上的卡片：比对话框低一层（对话框、确认框盖得住它），比页面高 */
.ast {
  position: fixed;
  z-index: 1900;
  display: flex;
  flex-direction: column;
  width: min(400px, calc(100vw - 16px));
  height: min(640px, calc(100vh - 16px));
  overflow: hidden;
  background: var(--lm-paper);
  border-radius: var(--lm-radius-panel);
  box-shadow: var(--lm-shadow);
}

.ast.large {
  width: min(800px, calc(100vw - 16px));
  height: calc(100vh - 32px);
}

/* 标题栏：按住拖 */
.ast-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
  height: 46px;
  padding: 0 8px 0 16px;
  cursor: move;
  user-select: none;
  touch-action: none;
  background: var(--lm-subtle);
  border-bottom: 1px solid var(--lm-line-soft);
}

.ast-mark {
  margin-right: 4px;
  font-size: 16px;
  color: var(--lm-accent);
}

.ast-head h2 {
  flex: 1;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--lm-ink);
}

.ast-icon {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 30px;
  height: 30px;
  padding: 0;
  font-size: 15px;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius);
}

.ast-icon:hover:not(:disabled) {
  color: var(--lm-ink);
  background: var(--lm-hover);
}

.ast-icon:disabled {
  color: var(--lm-disabled);
  cursor: default;
}

.ast-icon:focus-visible {
  outline: 2px solid var(--lm-accent);
  outline-offset: -2px;
}

.ast-body {
  flex: 1;
  min-height: 0;
  padding: 16px;
  overflow-y: auto;
}

.ast-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  margin-bottom: 14px;
  padding: 8px 12px;
  font-size: 13px;
  color: var(--lm-pending-text);
  background: var(--lm-pending-bg);
  border: 1px solid var(--lm-pending-line);
  border-radius: var(--lm-radius);
}

.ast-empty p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--lm-ink-2);
}

.ast-example {
  display: block;
  width: 100%;
  margin-bottom: 8px;
  padding: 8px 12px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink-2);
  text-align: left;
  cursor: pointer;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.ast-example:hover:not(:disabled) {
  color: var(--lm-ink);
  border-color: var(--lm-line-strong);
}

.ast-example:disabled {
  color: var(--lm-disabled);
  cursor: default;
}

.ast-example:focus-visible {
  outline: 2px solid var(--lm-accent);
  outline-offset: -1px;
}

.ast-empty .ast-dim {
  margin-top: 12px;
}

.ast-turn + .ast-turn {
  margin-top: 18px;
}

/* 问题靠右，一块浅底；回答铺满 */
.ast-q {
  width: fit-content;
  max-width: 85%;
  margin: 0 0 8px auto;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--lm-ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--lm-accent-bg);
  border-radius: var(--lm-radius-panel);
}

.ast-a {
  font-size: 13px;
  line-height: 1.7;
  color: var(--lm-ink);
}

.ast-tools {
  margin: 0 0 6px;
  padding: 0;
  list-style: none;
}

.ast-tools li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 22px;
  color: var(--lm-muted);
}

.ast-tools li.ok .el-icon {
  color: var(--lm-done-text);
}

.ast-tools li.failed {
  color: var(--lm-danger-text);
}

.ast-draft {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.ast-caret {
  display: inline-block;
  width: 7px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--lm-accent);
  animation: ast-blink 1s steps(2) infinite;
}

@keyframes ast-blink {
  50% {
    opacity: 0;
  }
}

.ast-dim {
  margin: 0;
  font-size: 12.5px;
  color: var(--lm-muted);
}

.ast-error {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--lm-danger-text);
}

/* 回答的全文：AssistantAnswer 用 h() 拼的元素不带这里的作用域标记，用 :deep */
.ast-a :deep(.ast-answer) {
  overflow-wrap: anywhere;
}

.ast-a :deep(.ast-answer > :first-child) {
  margin-top: 0;
}

.ast-a :deep(.ast-answer > :last-child) {
  margin-bottom: 0;
}

.ast-a :deep(.ast-answer p) {
  margin: 0 0 8px;
}

.ast-a :deep(.ast-heading) {
  font-weight: 600;
}

.ast-a :deep(.ast-answer ul),
.ast-a :deep(.ast-answer ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.ast-a :deep(.ast-answer a) {
  color: var(--lm-accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.ast-a :deep(.ast-answer code),
.ast-a :deep(.ast-answer pre) {
  font-family: var(--lm-font-mono);
  font-size: 12px;
  background: var(--lm-subtle);
  border-radius: var(--lm-radius-sm);
}

.ast-a :deep(.ast-answer code) {
  padding: 1px 4px;
}

.ast-a :deep(.ast-answer pre) {
  margin: 0 0 8px;
  padding: 8px 10px;
  white-space: pre-wrap;
}

.ast-a :deep(.ast-answer hr) {
  margin: 10px 0;
  border: 0;
  border-top: 1px solid var(--lm-line-soft);
}

.ast-a :deep(.ast-table) {
  margin: 0 0 8px;
  overflow-x: auto;
}

.ast-a :deep(.ast-table table) {
  min-width: 100%;
  font-size: 12.5px;
  border-collapse: collapse;
}

.ast-a :deep(.ast-table th),
.ast-a :deep(.ast-table td) {
  padding: 4px 8px;
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--lm-line-soft);
}

.ast-a :deep(.ast-table th) {
  font-weight: 600;
  color: var(--lm-ink-2);
  background: var(--lm-subtle);
}

.ast-foot {
  display: flex;
  flex: none;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 16px 4px;
  border-top: 1px solid var(--lm-line-soft);
}

.ast-hint {
  flex: none;
  margin: 0;
  padding: 0 16px 10px;
  font-size: 11.5px;
  color: var(--lm-muted);
}
</style>

<style>
/* 最近的对话：下拉菜单挂在 body 上，不能用 scoped */
.ast-history {
  width: 300px;
  max-height: 360px;
  overflow-y: auto;
}

.ast-history .el-dropdown-menu__item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: auto;
  line-height: 1.4;
  padding-top: 6px;
  padding-right: 8px;
  padding-bottom: 6px;
}

.ast-history-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.ast-history-title {
  flex: none;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ast-history small {
  font-size: 11.5px;
  color: var(--lm-muted);
}

.ast-history-note {
  padding: 8px 16px;
  font-size: 12.5px;
  color: var(--lm-muted);
  list-style: none;
}

/* 删除：一直看得见（触屏没有悬停），浅一点，指上去变红 */
.ast-history-del {
  display: inline-grid;
  flex: none;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  font-size: 14px;
  color: var(--lm-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius-sm);
}

.ast-history-del:hover {
  color: var(--lm-danger-text);
  background: var(--lm-danger-bg);
}

.ast-history-del:focus-visible {
  outline: 2px solid var(--lm-accent);
  outline-offset: -2px;
}

.ast-history-foot {
  margin-top: 4px;
  font-size: 11.5px;
  border-top: 1px solid var(--lm-line-soft);
}
</style>
