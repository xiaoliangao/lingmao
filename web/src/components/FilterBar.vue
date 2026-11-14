<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, shallowReactive, watch } from 'vue'
import { FILTER_BAR, type FilterEntry } from './filterBar'

/**
 * 列表页的筛选一行（原型的 Filters）：页头下面、表格上面，铺满一行。
 * - 关键字搜索框在最右边，回车、点 × 清空都触发 search
 * - 其他条件收进"筛选"弹层，每个条件是一个 FilterItem；选了的条件在按钮旁边显示成小标签，点 × 去掉
 * - 弹层里改了条件，停 0.4 秒就查（打字时不会一个字查一次），关弹层时还没查的马上查
 * - left 插槽放在最左边，一般放状态分段（el-segmented），它变了页面自己 search；right 插槽在搜索框左边
 *
 *   const { query, search, ... } = useTable({ fetch: userApi.page, query: { username: '', status: undefined as number | undefined, createDate: null } })
 *
 *   <FilterBar v-model:keyword="query.username" placeholder="用户名" @search="search">
 *     <template #left><el-segmented v-model="query.status" :options="STATUS_OPTIONS" @change="search" /></template>
 *     <FilterItem label="创建日期" :text="formatDateRange(query.createDate)" @clear="query.createDate = null">
 *       <DateRange v-model="query.createDate" />
 *     </FilterItem>
 *   </FilterBar>
 *
 * 不传 v-model:keyword 就没有搜索框；没有 FilterItem 就没有"筛选"按钮
 */
const keyword = defineModel<string>('keyword')
withDefaults(defineProps<{ placeholder?: string }>(), { placeholder: '搜索' })
const emit = defineEmits<{ search: [] }>()

// ---------- FilterItem 登记进来 ----------
const entries = shallowReactive<FilterEntry[]>([])
provide(FILTER_BAR, {
  register: (entry) => entries.push(entry),
  unregister: (entry) => {
    const index = entries.indexOf(entry)
    if (index >= 0) entries.splice(index, 1)
  },
})

/** 选了值的条件：外面出小标签 */
const active = computed(() => entries.filter((entry) => entry.text))
/** 条件现在的样子，用来判断改没改 */
const signature = computed(() => entries.map((entry) => `${entry.label}=${entry.text}`).join('\n'))

// ---------- 弹层 ----------
const open = ref(false)
const wrap = ref<HTMLElement>()
/** 上一次查询时条件的样子：一样就不再查 */
let applied = ''
let timer: ReturnType<typeof setTimeout> | undefined

function apply() {
  clearTimeout(timer)
  if (signature.value === applied) return
  applied = signature.value
  emit('search')
}

function toggle() {
  if (open.value) {
    close()
  } else {
    applied = signature.value
    open.value = true
  }
}

function close() {
  if (!open.value) return
  open.value = false
  apply()
}

// 弹层开着时改了条件：停一会儿再查
watch(signature, () => {
  if (!open.value) return
  clearTimeout(timer)
  timer = setTimeout(apply, 400)
})

async function remove(entry: FilterEntry) {
  entry.clear()
  await nextTick()
  apply()
}

function clearAll() {
  for (const entry of entries) entry.clear()
}

/** 点在弹层外面就关。下拉框、日期面板挂在 body 下面（.el-popper），点它们不算外面 */
function onPointerDown(event: PointerEvent) {
  if (!open.value) return
  const target = event.target as Element | null
  if (!target || wrap.value?.contains(target) || target.closest('.el-popper, .el-overlay')) return
  close()
}

onMounted(() => {
  applied = signature.value
  document.addEventListener('pointerdown', onPointerDown, true)
})

onBeforeUnmount(() => {
  clearTimeout(timer)
  document.removeEventListener('pointerdown', onPointerDown, true)
})
</script>

<template>
  <div class="filter-bar">
    <slot name="left" />

    <div v-if="$slots.default" ref="wrap" class="flt">
      <button type="button" class="flt-btn" :class="{ on: open || active.length }" :aria-expanded="open" @click="toggle">
        <el-icon><Filter /></el-icon>
        <span>筛选</span>
        <span v-if="active.length" class="count">{{ active.length }}</span>
      </button>
      <form v-show="open" class="flt-pop" role="dialog" aria-label="筛选条件" @submit.prevent="close" @keydown.esc="close">
        <slot />
        <footer>
          <el-button size="small" text :disabled="!active.length" @click="clearAll">清空条件</el-button>
          <el-button size="small" type="primary" native-type="submit">完成</el-button>
        </footer>
      </form>
    </div>

    <span v-for="entry in active" :key="entry.label" class="fchip">
      {{ entry.label }}：{{ entry.text }}
      <button type="button" :aria-label="`去掉条件：${entry.label}`" @click="remove(entry)">
        <el-icon><Close /></el-icon>
      </button>
    </span>

    <span class="spacer" />
    <slot name="right" />

    <el-input
      v-if="keyword !== undefined"
      v-model="keyword"
      class="search"
      :placeholder="placeholder"
      :aria-label="placeholder"
      prefix-icon="Search"
      clearable
      @keyup.enter="emit('search')"
      @clear="emit('search')"
    />
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 9px var(--lm-table-edge, 24px);
  background: var(--lm-paper);
  border-bottom: 1px solid var(--lm-line-soft);
}

.spacer {
  flex: 1;
}

/* ---------- 筛选按钮和弹层 ---------- */
.flt {
  position: relative;
}

.flt-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 29px;
  padding: 0 10px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-muted);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
  box-shadow: var(--lm-shadow-sm);
  transition:
    background 0.14s var(--lm-ease),
    border-color 0.14s var(--lm-ease);
}

.flt-btn:hover {
  background: var(--lm-subtle);
  border-color: var(--lm-line-strong);
}

.flt-btn.on {
  font-weight: 500;
  color: var(--lm-accent-2);
  background: var(--lm-accent-bg);
  border-color: var(--lm-accent-line);
}

.flt-btn .count {
  display: inline-grid;
  place-items: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10.5px;
  font-style: normal;
  font-weight: 600;
  color: var(--lm-on-accent);
  background: var(--lm-accent);
  border-radius: 99px;
}

.flt-pop {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 336px;
  max-width: calc(100vw - 32px);
  padding: 14px 16px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
  box-shadow: var(--lm-shadow-pop);
  transform-origin: top left;
  animation: pop-in 0.14s var(--lm-ease);
}

@keyframes pop-in {
  from {
    opacity: 0;
    transform: translateY(-4px) scale(0.97);
  }
}

.flt-pop footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--lm-line-soft);
}

/* ---------- 选了的条件：小标签 ---------- */
.fchip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 5px 0 9px;
  font-size: 12px;
  color: var(--lm-muted);
  white-space: nowrap;
  background: var(--lm-greige-2);
  border: 1px solid var(--lm-line);
  border-radius: 99px;
}

.fchip button {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  font-size: 11px;
  color: var(--lm-faint);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 50%;
}

.fchip button:hover {
  color: var(--lm-ink);
  background: var(--lm-sunken);
}

/* ---------- 关键字搜索 ---------- */
.search {
  width: 210px;
}

.search :deep(.el-input__wrapper) {
  height: 29px;
  background: var(--lm-greige-2);
  box-shadow: none;
}

.search :deep(.el-input__wrapper:hover) {
  background: var(--lm-paper);
  box-shadow: 0 0 0 1px var(--lm-line) inset;
}

.search :deep(.el-input__wrapper.is-focus) {
  background: var(--lm-paper);
  box-shadow:
    0 0 0 1px var(--lm-accent) inset,
    var(--lm-focus-ring);
}

.search :deep(.el-input__inner) {
  font-size: 12.5px;
}

@media (max-width: 900px) {
  .search {
    flex: 1 1 160px;
    width: auto;
  }
}
</style>
