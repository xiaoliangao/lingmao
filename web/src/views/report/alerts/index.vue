<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { homeApi } from '@/api/home'
import BlankState from '@/components/BlankState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { TODO_COUNT, TODO_GROUP, TODO_GROUP_MORE, type HomeTodos, type TodoCountKey, type TodoGroup, type TodoItem } from '@/types/home'
import { docRoute } from '@/utils/docRoute'

/**
 * 预警中心（/alerts，RFC 019 §3.5，甲方 11-27 答 Q66"统一的预警中心"）：把各模块的预警放在一页。
 * 数据和首页的待办是同一个接口（/api/board/home/todos），只是不要"待我审核"、只要预警类的数，按模块排；
 * 每组列出具体单据（最多 20 条），多的去对应列表。权限、数据范围和首页一样
 */
const COUNT_SECTIONS: { title: string; keys: TodoCountKey[] }[] = [
  { title: '生产', keys: ['PRODUCTION_LATE', 'PRODUCTION_NODE_LATE', 'PRODUCTION_NEAR', 'PRODUCTION_ISSUE', 'UNSCHEDULED', 'QC_PENDING', 'QC_REWORK'] },
  { title: '物料', keys: ['ARRIVAL_LATE', 'ARRIVAL_CUT', 'REORDER', 'STALE_MATERIAL', 'CONSUME_PENDING'] },
  { title: '订单和供应商', keys: ['UPSTREAM', 'CERT_EXPIRING'] },
]

const router = useRouter()
const todos = ref<HomeTodos | null>(null)
const loading = ref(false)
const failed = ref(false)
const mine = ref(false)
const reader = useLatestRequest()

async function load() {
  const read = reader.begin()
  loading.value = true
  try {
    const r = await read.run(() => homeApi.todos(mine.value ? { mine: true } : {}))
    if (!read.current()) return
    todos.value = r
    failed.value = false
  } catch {
    if (read.current()) failed.value = true
  } finally {
    if (read.current()) loading.value = false
  }
}
onActivated(load)

const sections = computed(() => {
  const counts = new Map((todos.value?.counts ?? []).map((c) => [c.key, c.count]))
  return COUNT_SECTIONS.map((s) => ({ title: s.title, items: s.keys.filter((k) => counts.has(k)).map((k) => ({ key: k, count: counts.get(k)! })) }))
    .filter((s) => s.items.length)
})
const groups = computed<TodoGroup[]>(() => (todos.value?.groups ?? []).filter((g) => g.key !== 'AUDIT'))
const nothing = computed(() => !sections.value.length && !groups.value.length)

function go(item: TodoItem) {
  const to = docRoute(item.ref)
  if (to) void router.push(to)
}
</script>

<template>
  <div>
    <PageHeader sub="交期、到料、库存、证照的预警，点了去处理">
      <template #actions>
        <el-segmented v-if="todos?.canSeeAll" v-model="mine" :options="[{ label: '全部', value: false }, { label: '我负责的', value: true }]"
          @change="load" />
        <el-button icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading && !todos" class="page">
      <BlankState v-if="failed" title="预警暂时取不到"><el-button size="small" @click="load">重试</el-button></BlankState>
      <BlankState v-else-if="todos && nothing" title="没有预警：交期、到料、库存、证照都正常" />
      <template v-else-if="todos">
        <div class="sections">
          <section v-for="s in sections" :key="s.title" class="panel">
            <div class="panel-head">{{ s.title }}</div>
            <div class="panel-body chips">
              <button v-for="c in s.items" :key="c.key" type="button" class="chip" :class="TODO_COUNT[c.key].tone ?? ''"
                @click="router.push(TODO_COUNT[c.key].to)">
                <span>{{ TODO_COUNT[c.key].label }}</span>
                <strong class="num">{{ c.count }}</strong>
              </button>
            </div>
          </section>
        </div>

        <div class="groups">
          <section v-for="g in groups" :key="g.key" class="panel">
            <div class="panel-head">
              <StatusTag :tone="TODO_GROUP[g.key].tone">{{ TODO_GROUP[g.key].label }}</StatusTag>
              <span class="dim num">{{ g.total }}</span>
            </div>
            <ul class="items">
              <li v-for="item in g.items" :key="`${item.ref.type}-${item.ref.id}`" @click="go(item)">
                <ThumbImage v-if="item.image" :src="item.image.thumbUrl" alt="" class="thumb" loading="lazy" />
                <span v-else class="thumb blank" />
                <div class="meta">
                  <div class="t1">{{ item.title }}</div>
                  <div class="t2 dim">{{ item.sub }}</div>
                </div>
                <el-icon class="dim"><ArrowRight /></el-icon>
              </li>
            </ul>
            <button v-if="g.total > g.items.length && TODO_GROUP_MORE[g.key]" type="button" class="more" @click="router.push(TODO_GROUP_MORE[g.key]!)">
              还有 {{ g.total - g.items.length }} 条
            </button>
          </section>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sections {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.chips {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-ink);
  text-align: left;
  cursor: pointer;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius);
}

.chip:hover {
  background: var(--lm-hover);
  border-color: var(--lm-line-strong);
}

.chip.danger strong {
  color: var(--lm-danger-text);
}

.chip.pending strong {
  color: var(--lm-pending-text);
}

.groups {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 14px;
}

.items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 420px;
  padding: 6px;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.items li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  cursor: pointer;
  border-radius: var(--lm-radius);
}

.items li:hover {
  background: var(--lm-hover);
}

.thumb {
  flex: none;
  width: 36px;
  height: 36px;
  object-fit: cover;
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.thumb.blank {
  background: var(--lm-subtle);
}

.meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.t1,
.t2 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.t1 {
  font-size: 13px;
}

.t2 {
  font-size: 12px;
}

.more {
  display: block;
  width: 100%;
  padding: 8px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: 0;
  border-top: 1px solid var(--lm-line-soft);
}

@media (max-width: 600px) {
  .groups {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
