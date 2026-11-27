<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { homeApi } from '@/api/home'
import StatusTag from '@/components/StatusTag.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { useUserStore } from '@/stores/user'
import { TODO_COUNT, TODO_GROUP, type HomeTodos, type TodoCountKey, type TodoGroup, type TodoItem } from '@/types/home'

/**
 * 手机待办（/mobile-todo，RFC 019 §4，甲方 11-27 答 Q65）：按 360px 宽做。两个页签：
 * - 待我审核：首页待办的"待我审核"那一组，点进去能直接审（需求订单、采购单、生产单、订单变更单；别的种类提示到电脑上审）
 * - 预警：首页待办里预警类的几组和数（和预警中心一样），钉钉的"货期预警"点开到这里（?tab=alerts）
 * 数据就是首页待办的接口，权限、数据范围和首页一样
 */
const ALERT_COUNTS: TodoCountKey[] = ['PRODUCTION_LATE', 'PRODUCTION_NEAR', 'PRODUCTION_NODE_LATE', 'PRODUCTION_ISSUE', 'ARRIVAL_LATE', 'ARRIVAL_CUT']

const route = useRoute()
const router = useRouter()
const user = useUserStore()

const tab = ref<'audit' | 'alerts'>(route.query.tab === 'alerts' ? 'alerts' : 'audit')
const todos = ref<HomeTodos | null>(null)
const loading = ref(false)
const failed = ref(false)

const reader = useLatestRequest()
async function load() {
  const read = reader.begin()
  loading.value = true
  try {
    const r = await read.run(() => homeApi.todos())
    if (!read.current()) return
    todos.value = r
    failed.value = false
  } catch {
    if (read.current()) failed.value = true
  } finally {
    if (read.current()) loading.value = false
  }
}
// 单独的外框里没有 keep-alive：从详情页回来是重新打开这一页，onMounted 会再取一次
onMounted(load)

const audit = computed<TodoGroup | null>(() => todos.value?.groups.find((g) => g.key === 'AUDIT') ?? null)
const alertGroups = computed(() => (todos.value?.groups ?? []).filter((g) => g.key !== 'AUDIT' && g.items.length))
const alertCounts = computed(() => {
  const counts = new Map((todos.value?.counts ?? []).map((c) => [c.key, c.count]))
  return ALERT_COUNTS.filter((k) => (counts.get(k) ?? 0) > 0).map((k) => ({ key: k, count: counts.get(k)! }))
})
const alertTotal = computed(() => alertGroups.value.reduce((n, g) => n + g.total, 0))

function switchTab(t: 'audit' | 'alerts') {
  tab.value = t
  void router.replace({ query: t === 'alerts' ? { tab: 'alerts' } : {} })
}

function open(item: TodoItem) {
  const q: Record<string, string> = { type: item.ref.type }
  if (item.ref.kind) q.kind = item.ref.kind
  if (item.ref.parentId) q.parentId = String(item.ref.parentId)
  void router.push({ path: `/mobile-todo/${item.ref.id}`, query: q })
}
</script>

<template>
  <div v-loading="loading && !todos" class="m-shell">
    <header class="m-top">
      <div class="title-box">
        <h1>手机待办</h1>
        <span class="dim">{{ user.info?.nickname ?? '' }}</span>
      </div>
      <button type="button" class="refresh-btn" :disabled="loading" @click="load">刷新</button>
    </header>

    <div class="m-tabs" role="tablist">
      <button type="button" role="tab" :aria-selected="tab === 'audit'" :class="{ on: tab === 'audit' }" @click="switchTab('audit')">
        待我审核<b v-if="audit?.total">{{ audit.total }}</b>
      </button>
      <button type="button" role="tab" :aria-selected="tab === 'alerts'" :class="{ on: tab === 'alerts' }" @click="switchTab('alerts')">
        预警<b v-if="alertTotal" class="warn">{{ alertTotal }}</b>
      </button>
    </div>

    <div v-if="failed" class="m-empty">
      待办暂时取不到
      <button type="button" class="refresh-btn" @click="load">重试</button>
    </div>

    <template v-else-if="todos && tab === 'audit'">
      <div v-if="!audit?.items.length" class="m-empty">没有等你审的单</div>
      <div v-else class="m-list">
        <button v-for="item in audit.items" :key="`${item.ref.type}-${item.ref.id}`" type="button" class="m-card" @click="open(item)">
          <ThumbImage v-if="item.image" :src="item.image.thumbUrl" alt="" class="pic" loading="lazy" />
          <span v-else class="pic blank" />
          <span class="info">
            <span class="t1">{{ item.title }}</span>
            <span class="t2 dim">{{ item.sub }}</span>
          </span>
        </button>
        <p v-if="audit.total > audit.items.length" class="more dim">还有 {{ audit.total - audit.items.length }} 张，审完这些再刷新</p>
      </div>
    </template>

    <template v-else-if="todos">
      <div v-if="alertCounts.length" class="m-counts">
        <span v-for="c in alertCounts" :key="c.key" class="count" :class="TODO_COUNT[c.key].tone ?? ''">
          <b class="num">{{ c.count }}</b>{{ TODO_COUNT[c.key].label }}
        </span>
      </div>
      <div v-if="!alertGroups.length" class="m-empty">没有预警：交期、到料都正常</div>
      <section v-for="g in alertGroups" :key="g.key" class="m-group">
        <h2>
          <StatusTag :tone="TODO_GROUP[g.key].tone">{{ TODO_GROUP[g.key].label }}</StatusTag>
          <span class="dim">{{ g.total }} 条</span>
        </h2>
        <div class="m-list">
          <button v-for="item in g.items" :key="`${item.ref.type}-${item.ref.id}-${item.title}`" type="button" class="m-card" @click="open(item)">
            <ThumbImage v-if="item.image" :src="item.image.thumbUrl" alt="" class="pic" loading="lazy" />
            <span v-else class="pic blank" />
            <span class="info">
              <span class="t1">{{ item.title }}</span>
              <span class="t2 dim">{{ item.sub }}</span>
            </span>
          </button>
          <p v-if="g.total > g.items.length" class="more dim">还有 {{ g.total - g.items.length }} 条，在电脑上看</p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.m-shell {
  max-width: 560px;
  min-height: 100%;
  padding: 14px 14px 32px;
  margin: 0 auto;
  background: var(--lm-greige-2);
}
.m-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.title-box h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.title-box span {
  font-size: 12px;
}
.refresh-btn {
  min-height: 44px;
  padding: 0 14px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius);
}
.m-tabs {
  display: flex;
  gap: 4px;
  padding: 3px;
  margin-bottom: 12px;
  background: var(--lm-subtle);
  border-radius: var(--lm-radius);
}
.m-tabs button {
  flex: 1;
  min-height: 40px;
  font: inherit;
  font-size: 14px;
  color: var(--lm-ink-2);
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: var(--lm-radius);
}
.m-tabs button.on {
  font-weight: 600;
  color: var(--lm-ink);
  background: var(--lm-paper);
}
.m-tabs b {
  margin-left: 6px;
  font-weight: 600;
  color: var(--lm-pending-text);
}
.m-tabs b.warn {
  color: var(--lm-danger-text);
}
.m-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 64px 16px;
  font-size: 14px;
  color: var(--lm-muted);
  text-align: center;
}
.m-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.count {
  padding: 6px 10px;
  font-size: 12px;
  color: var(--lm-ink-2);
  background: var(--lm-paper);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}
.count b {
  margin-right: 4px;
  font-size: 14px;
  color: var(--lm-ink);
}
.count.danger b {
  color: var(--lm-danger-text);
}
.count.pending b {
  color: var(--lm-pending-text);
}
.m-group {
  margin-bottom: 16px;
}
.m-group h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 400;
}
.m-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.m-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 64px;
  padding: 12px;
  font: inherit;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}
.m-card:active {
  background: var(--lm-hover);
}
.pic {
  flex: none;
  width: 44px;
  height: 44px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}
.pic.blank {
  background: var(--lm-subtle);
}
.info {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.t1 {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.t2 {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.more {
  margin: 0;
  font-size: 12px;
  text-align: center;
}
</style>
