<script setup lang="ts">
import { useLatestRequest } from '@/composables/useLatestRequest'
import ThumbImage from '@/components/ThumbImage.vue'
import { businessToday } from '@/utils/businessDate'
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { homeApi } from '@/api/home'
import { backupApi } from '@/api/system'
import BlankState from '@/components/BlankState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import UserSelect from '@/components/UserSelect.vue'
import DaysLeftTag from '@/components/doc/DaysLeftTag.vue'
import QtyProgress from '@/components/doc/QtyProgress.vue'
import { isStandalone } from '@/router/standalone'
import { useUserStore } from '@/stores/user'
import {
  TODO_COUNT,
  TODO_GROUP,
  TODO_GROUP_MORE,
  type DeliveryRow,
  type HomeKpi,
  type HomeTodos,
  type TodoGroup,
  type TodoGroupKey,
  type TodoItem,
} from '@/types/home'
import type { BackupStatus } from '@/types/system'
import { docRoute } from '@/utils/docRoute'
import { formatDate, formatDateTime, formatFileSize, formatQty } from '@/utils/format'

defineOptions({ name: 'HomePage' })

/**
 * 首页（/home，docs/api/06 §1.1、§3）：概况四格、快捷入口、待处理事项、近期交期、其他提醒，超级管理员多一行备份情况。
 * - 概况、待办、近期交期三块分开取：一块慢了、出错了不挡别的；失败明确显示并可重试，不冒充成功空态
 * - 待办：管理层（canSeeAll）多"全部 / 我的"和负责人筛选，选的值记在 localStorage（读写包 try/catch，取不到就用默认）
 * - "全部"里每组列前 4 条；选了一组列这组的全部，多出来的"还有 n 条"去 TODO_GROUP_MORE
 * - 概况的小数：0 灰色，不是 0 按文档上色；资料包草稿不能点（款式列表没有按资料包筛的条件）
 * - 原来的"我的功能"面板去掉了（侧边栏已经有全部菜单），换成快捷入口
 */
const router = useRouter()
const store = useUserStore()

const now = new Date()
const greeting = (() => {
  const hour = Number(new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Shanghai', hour: '2-digit', hourCycle: 'h23' }).format(now))
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 13) return '中午好'
  if (hour >= 13 && hour < 18) return '下午好'
  return '晚上好'
})()
const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const day = businessToday(now)
const todayText = `${day.slice(0, 4)} 年 ${Number(day.slice(5, 7))} 月 ${Number(day.slice(8, 10))} 日 星期${WEEKDAYS[new Date(`${day}T00:00:00Z`).getUTCDay()]}`
const sub = computed(() => `${greeting}，${store.info?.nickname ?? ''}`)

// ---------- 快捷入口：只出有那个菜单的 ----------
const QUICK = [
  { label: '新建需求订单', to: '/orders/new', perm: 'order:order:add', icon: 'DocumentAdd' },
  { label: '新建款式', to: '/styles/new', perm: 'style:style:add', icon: 'CirclePlus' },
  { label: '算料', to: '/mrp', perm: 'purchase:mrp:list', icon: 'Operation' },
  { label: '待到料', to: '/stock-in?tab=pending', perm: 'inventory:doc:list', icon: 'Van' },
  { label: '生产看板', to: '/board', perm: 'production:board:list', icon: 'DataBoard' },
  { label: '库存', to: '/stock', perm: 'inventory:stock:list', icon: 'Box' },
  { label: '手机报工', to: '/mobile', perm: 'production:mobile:list', icon: 'Iphone' },
]
const quickActions = computed(() => QUICK.filter((a) => store.hasPerm(a.perm)))

/** 快捷入口：单独成页的（手机报工）和侧边栏一样在新标签里打开 */
function openQuick(to: string) {
  if (isStandalone(to)) window.open(to, '_blank')
  else router.push(to)
}

// ---------- 三块分开取 ----------
const kpi = ref<HomeKpi | null>(null)
const todos = ref<HomeTodos | null>(null)
const deliveries = ref<DeliveryRow[]>([])
const loadingKpi = ref(false)
const loadingTodos = ref(false)
const loadingDeliveries = ref(false)
const kpiFailed = ref(false)
const todosFailed = ref(false)
const deliveriesFailed = ref(false)
const activeTodoGroup = ref<'ALL' | TodoGroupKey>('ALL')

// 待办筛选：只对 canSeeAll 的人有用；记在本机
const FILTER_KEY = 'scm.home.todoFilter'
interface TodoFilter {
  scope: 'ALL' | 'MINE'
  ownerId: string | null
  ownerName: string | null
}
function readFilter(): TodoFilter {
  const fallback: TodoFilter = { scope: 'ALL', ownerId: null, ownerName: null }
  try {
    const raw = localStorage.getItem(FILTER_KEY)
    if (!raw) return fallback
    const v = JSON.parse(raw) as Partial<TodoFilter>
    return {
      scope: v.scope === 'MINE' ? 'MINE' : 'ALL',
      ownerId: typeof v.ownerId === 'string' && v.ownerId ? v.ownerId : null,
      ownerName: typeof v.ownerName === 'string' ? v.ownerName : null,
    }
  } catch {
    return fallback
  }
}
function saveFilter() {
  try {
    localStorage.setItem(FILTER_KEY, JSON.stringify(todoFilter))
  } catch {
    // 存不了（隐私模式、禁了站点数据）：这次照样筛，下次回到默认
  }
}
const todoFilter = reactive<TodoFilter>(readFilter())
const SCOPE_OPTIONS = [
  { label: '全部', value: 'ALL' },
  { label: '我的', value: 'MINE' },
]

let kpiSeq = 0
const loadKpiRequest = useLatestRequest()
async function loadKpi() {
  const read = loadKpiRequest.begin()
  const seq = ++kpiSeq
  loadingKpi.value = true
  kpiFailed.value = false
  try {
    const result = await read.run(() => homeApi.kpi())
    if (!read.current()) return
    if (seq === kpiSeq) kpi.value = result
  } catch {
    if (!read.current()) return
    if (seq === kpiSeq) {
      kpi.value = null
      kpiFailed.value = true
    }
  } finally {
    if (read.current() && (seq === kpiSeq)) loadingKpi.value = false
  }
}

let todoSeq = 0
const loadTodosRequest = useLatestRequest()
async function loadTodos() {
  const read = loadTodosRequest.begin()
  const seq = ++todoSeq
  loadingTodos.value = true
  todosFailed.value = false
  try {
    const res = await read.run(() => homeApi.todos(
      todoFilter.scope === 'MINE' ? { mine: true } : todoFilter.ownerId ? { ownerId: todoFilter.ownerId } : {},
    ))
    if (!read.current()) return
    if (seq !== todoSeq) return
    todos.value = res
    if (activeTodoGroup.value !== 'ALL' && !res.groups.some((g) => g.key === activeTodoGroup.value)) activeTodoGroup.value = 'ALL'
  } catch {
    if (!read.current()) return
    if (seq === todoSeq) {
      todos.value = null
      todosFailed.value = true
    }
  } finally {
    if (read.current() && (seq === todoSeq)) loadingTodos.value = false
  }
}

let deliverySeq = 0
const loadDeliveriesRequest = useLatestRequest()
async function loadDeliveries() {
  const read = loadDeliveriesRequest.begin()
  const seq = ++deliverySeq
  loadingDeliveries.value = true
  deliveriesFailed.value = false
  try {
    const result = await read.run(() => homeApi.deliveries())
    if (!read.current()) return
    if (seq === deliverySeq) deliveries.value = result
  } catch {
    if (!read.current()) return
    if (seq === deliverySeq) {
      deliveries.value = []
      deliveriesFailed.value = true
    }
  } finally {
    if (read.current() && (seq === deliverySeq)) loadingDeliveries.value = false
  }
}

const backup = ref<BackupStatus | null>(null)
const loadBackupRequest = useLatestRequest()
async function loadBackup() {
  const read = loadBackupRequest.begin()
  if (!store.isAdmin) return
  try {
    const fetched1 = await read.run(() => backupApi.status())
    if (!read.current()) return
    backup.value = fetched1
  } catch {
    if (!read.current()) return
    backup.value = null
  }
}

function reloadAll() {
  void loadKpi()
  void loadTodos()
  void loadDeliveries()
  void loadBackup()
}

onMounted(reloadAll)
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  reloadAll()
})

function onScopeChange() {
  if (todoFilter.scope === 'MINE') {
    todoFilter.ownerId = null
    todoFilter.ownerName = null
  }
  saveFilter()
  void loadTodos()
}

function onOwnerChange(id: string | null | undefined) {
  todoFilter.ownerId = id ?? null
  if (!todoFilter.ownerId) todoFilter.ownerName = null
  else todoFilter.scope = 'ALL'
  saveFilter()
  void loadTodos()
}

function onOwnerLabel(name: string | null | undefined) {
  todoFilter.ownerName = name ?? null
  saveFilter()
}

// ---------- 待办显示 ----------
const todoTotal = computed(() => (todos.value?.groups ?? []).reduce((s, g) => s + g.total, 0))
const todoSegOptions = computed(() => [
  { label: `全部 ${todoTotal.value}`, value: 'ALL' as const },
  ...(todos.value?.groups ?? []).map((g) => ({ label: `${TODO_GROUP[g.key].label} ${g.total}`, value: g.key })),
])
/** "全部"：每组前 4 条连在一起；选了一组：这组全部 */
const allPreview = computed(() =>
  (todos.value?.groups ?? []).flatMap((g) => g.items.slice(0, 4).map((item) => ({ key: g.key, item }))),
)
const activeGroup = computed<TodoGroup | null>(() =>
  activeTodoGroup.value === 'ALL' ? null : ((todos.value?.groups ?? []).find((g) => g.key === activeTodoGroup.value) ?? null),
)
const moreOf = (g: TodoGroup) => (g.total > g.items.length ? TODO_GROUP_MORE[g.key] : null)
const itemKey = (key: string, item: TodoItem) => `${key}-${item.ref.type}-${item.ref.id}`

function goRef(item: TodoItem | DeliveryRow) {
  const to = docRoute(item.ref)
  if (to) void router.push(to)
}

// ---------- 概况 ----------
const hasAnyKpi = computed(() => !!(kpi.value?.dev || kpi.value?.order || kpi.value?.production || kpi.value?.material))
/** 小数的颜色：0 灰，不是 0 按格子定（琥珀 / 红） */
const toneOf = (n: number | null, tone: 'warn' | 'danger') => (n ? tone : 'zero')

const backupAlert = computed(() => {
  const status = backup.value
  if (!status?.configured || !status.stale) return ''
  return status.latestTime
    ? `最近一次备份是 ${formatDateTime(status.latestTime)}，已超过 36 小时，请联系部署人员检查备份`
    : '还没有任何备份，请联系部署人员检查备份'
})

const backupLine = computed(() => {
  const status = backup.value
  if (!status?.configured || status.stale || !status.latestTime) return ''
  const size = status.sizeBytes == null ? '' : `（${formatFileSize(status.sizeBytes)}）`
  return `最近备份：${formatDateTime(status.latestTime)}${size}`
})
</script>

<template>
  <div class="home">
    <PageHeader title="首页" :sub="sub">
      <template #title>
        <span class="today dim">{{ todayText }}</span>
      </template>
      <template #actions>
        <el-button v-perm="'style:style:list'" @click="router.push('/styles')">款式库</el-button>
        <el-button v-perm="'order:order:add'" type="primary" icon="Plus" @click="router.push('/orders/new')">新建需求订单</el-button>
      </template>
    </PageHeader>

    <div class="wrap">
      <el-alert v-if="backupAlert" type="error" :title="backupAlert" :closable="false" show-icon />

      <el-alert v-if="kpiFailed" data-testid="home-kpi-error" type="warning" title="概况暂不可用，未取得相关数据" :closable="false">
        <el-button size="small" @click="loadKpi">重试概况</el-button>
      </el-alert>
      <p v-else-if="loadingKpi" role="status" class="dim">正在取得概况…</p>
      <section v-else-if="hasAnyKpi && kpi" class="kpi-grid">
        <div v-if="kpi.dev" class="kpi-card">
          <button type="button" class="kpi-top" @click="router.push('/styles')">
            <span class="kpi-label">开发款式</span>
            <strong class="kpi-num num">{{ formatQty(kpi.dev.styleCount) }}<small>款</small></strong>
          </button>
          <div class="kpi-subs">
            <span class="sub-chip static">
              资料包草稿 <b class="num" :class="toneOf(kpi.dev.packDraftCount, 'warn')">{{ kpi.dev.packDraftCount }}</b>
            </span>
          </div>
        </div>

        <div v-if="kpi.order" class="kpi-card">
          <button type="button" class="kpi-top" @click="router.push('/orders')">
            <span class="kpi-label">在手订单 · {{ kpi.order.count }} 张</span>
            <strong class="kpi-num num">{{ formatQty(kpi.order.qty) }}<small>件</small></strong>
          </button>
          <div class="kpi-subs">
            <button type="button" class="sub-chip" @click="router.push('/orders?status=PENDING')">
              待审核 <b class="num" :class="toneOf(kpi.order.pendingCount, 'warn')">{{ kpi.order.pendingCount }}</b>
            </button>
            <button type="button" class="sub-chip" @click="router.push('/orders?unscheduled=1')">
              待排单 <b class="num" :class="toneOf(kpi.order.unscheduledCount, 'warn')">{{ kpi.order.unscheduledCount }}</b>
            </button>
            <button v-if="kpi.order.changePendingCount !== null" type="button" class="sub-chip" @click="router.push('/order-changes?status=PENDING')">
              变更待审 <b class="num" :class="toneOf(kpi.order.changePendingCount, 'warn')">{{ kpi.order.changePendingCount }}</b>
            </button>
          </div>
        </div>

        <div v-if="kpi.production" class="kpi-card">
          <button type="button" class="kpi-top" @click="router.push('/board')">
            <span class="kpi-label">在制生产 · {{ kpi.production.count }} 张</span>
            <strong class="kpi-num num">{{ formatQty(kpi.production.qty) }}<small>件</small></strong>
          </button>
          <div class="kpi-subs">
            <button type="button" class="sub-chip" @click="router.push('/board?card=LATE')">
              已逾期 <b class="num" :class="toneOf(kpi.production.lateCount, 'danger')">{{ kpi.production.lateCount }}</b>
            </button>
            <button type="button" class="sub-chip" @click="router.push('/board?card=NODE_LATE')">
              节点延期 <b class="num" :class="toneOf(kpi.production.nodeLateCount, 'danger')">{{ kpi.production.nodeLateCount }}</b>
            </button>
            <button type="button" class="sub-chip" @click="router.push('/board?card=ISSUE')">
              未关异常 <b class="num" :class="toneOf(kpi.production.issueCount, 'danger')">{{ kpi.production.issueCount }}</b>
            </button>
          </div>
        </div>

        <div v-if="kpi.material" class="kpi-card">
          <button type="button" class="kpi-top" @click="router.push('/stock-in?tab=pending')">
            <span class="kpi-label">在途物料</span>
            <strong class="kpi-num num">{{ formatQty(kpi.material.transitLineCount) }}<small>项</small></strong>
          </button>
          <div class="kpi-subs">
            <button type="button" class="sub-chip" @click="router.push('/stock-in?tab=pending&risk=LATE')">
              到料逾期 <b class="num" :class="toneOf(kpi.material.arrivalLateCount, 'danger')">{{ kpi.material.arrivalLateCount }}</b>
            </button>
            <button type="button" class="sub-chip" @click="router.push('/purchases?status=PENDING')">
              采购待审 <b class="num" :class="toneOf(kpi.material.purchasePendingCount, 'warn')">{{ kpi.material.purchasePendingCount }}</b>
            </button>
            <button v-if="kpi.material.mrpDraftCount !== null" type="button" class="sub-chip" @click="router.push('/mrp?status=DRAFT')">
              算料待确认 <b class="num" :class="toneOf(kpi.material.mrpDraftCount, 'warn')">{{ kpi.material.mrpDraftCount }}</b>
            </button>
          </div>
        </div>
      </section>

      <section v-if="quickActions.length" class="quick-bar">
        <button v-for="a in quickActions" :key="a.to" type="button" class="quick-btn" @click="openQuick(a.to)">
          <el-icon><component :is="a.icon" /></el-icon>
          <span>{{ a.label }}</span>
        </button>
      </section>

      <div class="columns">
        <section v-loading="loadingTodos" class="panel todo-panel">
          <div class="panel-head">
            <span>待处理事项<span v-if="todoTotal" class="dim num"> {{ todoTotal }}</span></span>
            <div v-if="todos?.canSeeAll" class="acts">
              <el-segmented v-model="todoFilter.scope" size="small" :options="SCOPE_OPTIONS" @change="onScopeChange" />
              <div class="owner-pick">
                <UserSelect
                  :model-value="todoFilter.ownerId"
                  :label="todoFilter.ownerName"
                  clearable
                  size="small"
                  placeholder="负责人"
                  @update:model-value="onOwnerChange"
                  @update:label="onOwnerLabel"
                />
              </div>
            </div>
          </div>

          <BlankState v-if="todosFailed" data-testid="home-todos-error" title="待处理事项暂不可用，未取得待办数据">
            <el-button size="small" @click="loadTodos">重试待办</el-button>
          </BlankState>
          <div v-else-if="todos && todos.groups.length" class="panel-body">
            <div v-if="todos.groups.length > 1" class="seg-wrap">
              <el-segmented v-model="activeTodoGroup" size="small" :options="todoSegOptions" />
            </div>

            <ul v-if="!activeGroup" class="todo-list">
              <li v-for="{ key, item } in allPreview" :key="itemKey(key, item)" @click="goRef(item)">
                <ThumbImage v-if="item.image" :src="item.image.thumbUrl" alt="" class="thumb" loading="lazy" />
                <span v-else class="thumb blank" />
                <div class="meta">
                  <div class="title-txt">{{ item.title }}</div>
                  <div class="t2 dim">{{ item.sub }}</div>
                </div>
                <StatusTag :tone="TODO_GROUP[key].tone">{{ TODO_GROUP[key].label }}</StatusTag>
                <el-icon class="arrow"><ArrowRight /></el-icon>
              </li>
            </ul>

            <template v-else>
              <ul class="todo-list">
                <li v-for="item in activeGroup.items" :key="itemKey(activeGroup.key, item)" @click="goRef(item)">
                  <ThumbImage v-if="item.image" :src="item.image.thumbUrl" alt="" class="thumb" loading="lazy" />
                  <span v-else class="thumb blank" />
                  <div class="meta">
                    <div class="title-txt">{{ item.title }}</div>
                    <div class="t2 dim">{{ item.sub }}</div>
                  </div>
                  <el-icon class="arrow"><ArrowRight /></el-icon>
                </li>
              </ul>
              <button v-if="moreOf(activeGroup)" type="button" class="more-btn" @click="router.push(moreOf(activeGroup)!)">
                还有 {{ activeGroup.total - activeGroup.items.length }} 条
              </button>
            </template>
          </div>
          <BlankState v-else :title="loadingTodos ? '正在取得待办…' : '暂无待处理的事'" />
        </section>

        <section v-loading="loadingDeliveries" class="panel deliv-panel">
          <div class="panel-head">近期交期</div>
          <BlankState v-if="deliveriesFailed" data-testid="home-deliveries-error" title="近期交期暂不可用，未取得交期数据">
            <el-button size="small" @click="loadDeliveries">重试交期</el-button>
          </BlankState>
          <div v-else-if="deliveries.length" class="panel-body">
            <ul class="deliv-list">
              <li v-for="d in deliveries" :key="`${d.ref.type}-${d.ref.id}`" @click="goRef(d)">
                <ThumbImage v-if="d.image" :src="d.image.thumbUrl" alt="" class="thumb" loading="lazy" />
                <span v-else class="thumb blank" />
                <div class="meta">
                  <div class="t1">
                    <span class="type-pill">{{ d.ref.typeLabel }}</span>
                    <span class="code">{{ d.ref.no }}</span>
                    <span class="num due dim">{{ formatDate(d.dueDate) }}</span>
                  </div>
                  <div class="t2 dim ell">{{ d.sub }}</div>
                  <QtyProgress :done="d.doneQty" :total="d.totalQty" tone="done" />
                </div>
                <DaysLeftTag :days-left="d.daysLeft" />
              </li>
            </ul>
          </div>
          <BlankState v-else :title="loadingDeliveries ? '正在取得交期…' : '近 7 天没有要交的单'" />
        </section>

        <section class="panel remind-panel">
          <div class="panel-head">其他提醒</div>
          <BlankState v-if="todosFailed" title="其他提醒暂不可用，未取得提醒数据" />
          <div v-else-if="todos?.counts.length" class="panel-body remind-grid">
            <button
              v-for="c in todos.counts"
              :key="c.key"
              type="button"
              class="remind-chip"
              :class="TODO_COUNT[c.key].tone ?? ''"
              @click="router.push(TODO_COUNT[c.key].to)"
            >
              <span class="lbl">{{ TODO_COUNT[c.key].label }}</span>
              <strong class="num">{{ c.count }}</strong>
            </button>
          </div>
          <BlankState v-else :title="loadingTodos ? '正在取得提醒…' : '没有别的要处理'" />
        </section>
      </div>

      <p v-if="backupLine" class="backup-line">{{ backupLine }}</p>
    </div>
  </div>
</template>

<style scoped>
.home {
  min-height: 100%;
  background: var(--lm-greige-2);
}

.today {
  font-size: 13px;
}

.wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1360px;
  margin: 0 auto;
  padding: 20px 24px 40px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.kpi-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.kpi-top {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: none;
}

.kpi-top:hover .kpi-num {
  color: var(--lm-accent);
}

.kpi-label {
  font-size: 12.5px;
  color: var(--lm-muted);
}

.kpi-num {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--lm-ink);
  transition: color 0.15s var(--lm-ease);
}

.kpi-num small {
  margin-left: 4px;
  font-size: 12.5px;
  font-weight: 400;
  color: var(--lm-muted);
}

.kpi-subs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding-top: 10px;
  border-top: 1px solid var(--lm-line-soft);
}

.sub-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  font: inherit;
  font-size: 12px;
  color: var(--lm-ink-2);
  cursor: pointer;
  background: var(--lm-subtle);
  border: 1px solid var(--lm-line-soft);
  border-radius: var(--lm-radius-sm);
}

.sub-chip.static {
  cursor: default;
}

.sub-chip:not(.static):hover {
  background: var(--lm-hover);
  border-color: var(--lm-line-strong);
}

.sub-chip b.zero {
  font-weight: 400;
  color: var(--lm-muted);
}

.sub-chip b.warn {
  color: var(--lm-pending-text);
}

.sub-chip b.danger {
  color: var(--lm-danger-text);
}

.quick-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 0 14px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink);
  cursor: pointer;
  background: var(--lm-paper);
  border: 1px solid var(--lm-line);
  border-radius: var(--lm-radius-panel);
}

.quick-btn .el-icon {
  color: var(--lm-accent);
}

.quick-btn:hover {
  border-color: var(--lm-accent);
}

/* 宽屏：左边待处理占两行高，右边上近期交期、下其他提醒；窄屏一列（概况、快捷、待处理、近期交期、其他提醒） */
.columns {
  display: grid;
  grid-template-areas:
    'todo deliv'
    'todo remind';
  grid-template-rows: auto 1fr;
  grid-template-columns: minmax(0, 7fr) minmax(0, 5fr);
  gap: 16px;
  align-items: start;
}

.todo-panel {
  grid-area: todo;
}

.deliv-panel {
  grid-area: deliv;
}

.remind-panel {
  grid-area: remind;
}

.owner-pick {
  width: 140px;
}

.seg-wrap {
  overflow-x: auto;
  padding-bottom: 10px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--lm-line-soft);
}

.todo-list,
.deliv-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 560px;
  padding: 0;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.todo-list li,
.deliv-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  cursor: pointer;
  border-radius: var(--lm-radius);
  transition: background 0.14s var(--lm-ease);
}

.todo-list li:hover,
.deliv-list li:hover {
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

.t1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.title-txt {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.t2 {
  font-size: 12px;
}

.ell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.arrow {
  flex: none;
  color: var(--lm-muted);
}

.type-pill {
  padding: 0 6px;
  font-size: 11px;
  color: var(--lm-ink-2);
  background: var(--lm-sunken);
  border-radius: 4px;
}

.due {
  margin-left: auto;
  font-size: 12px;
}

.more-btn {
  margin-top: 4px;
  padding: 4px 10px;
  font: inherit;
  font-size: 12.5px;
  color: var(--lm-accent);
  cursor: pointer;
  background: none;
  border: none;
}

.more-btn:hover {
  text-decoration: underline;
}

.remind-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.remind-chip {
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

.remind-chip:hover {
  background: var(--lm-hover);
  border-color: var(--lm-line-strong);
}

.remind-chip.danger strong {
  color: var(--lm-danger-text);
}

.remind-chip.pending strong {
  color: var(--lm-pending-text);
}

.backup-line {
  font-size: 12px;
  color: var(--lm-muted);
  text-align: right;
}

@media (max-width: 1180px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .columns {
    grid-template-areas:
      'todo'
      'deliv'
      'remind';
    grid-template-rows: auto;
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .wrap {
    padding: 14px;
  }

  .kpi-grid,
  .remind-grid {
    grid-template-columns: 1fr;
  }
}
</style>
