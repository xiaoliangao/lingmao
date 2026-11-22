<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { orderApi } from '@/api/order'
import { purchaseApi } from '@/api/purchase'
import { productionApi } from '@/api/production'
import { styleApi } from '@/api/style'
import { materialApi } from '@/api/basic'
import { supplierApi } from '@/api/supplier'
import { isStandalone } from '@/router/standalone'
import { PRODUCTION_KIND } from '@/types/production'

/**
 * 快捷跳转（照原型的 Palette）：点侧边栏的"搜索功能"或按 ⌘K / Ctrl+K 打开，浮在页面正中偏上。
 * - 一打开列出所有页面；输入字就按名字（和所在目录）筛，几个词用空格隔开都要对上
 * - 输入两个字以上，同时按单号、款号、编码、名称去查需求订单、采购单、生产单、款式、物料、供应商，每样最多 5 条；
 *   只查侧边栏里有的（没权限的不查），查不到、出错都不弹提示（silent），只是没有这一组
 * - 有智能助手入口的（canAsk），输入两个字以上时最后一项是"问助手：<输入的话>"，回车打开助手的对话卡片并发出这句话
 *   （RFC 009 §6.1 原来只在什么都搜不到时出；"ML0001 还有多少库存"这种，物料搜得到，就没有问助手了，所以改成一直在最后）
 * - ↑↓ 选，回车打开，Esc 或点外面关掉
 */
export interface PaletteItem {
  name: string
  path: string
  icon: string | null
  /** 右边的灰字：页面所在目录、单据的补充信息 */
  sub: string
  /** 有这个就不是去哪一页，是把这句话交给智能助手 */
  ask?: string
}

const props = defineProps<{ pages: PaletteItem[]; canAsk?: boolean }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ ask: [question: string] }>()

const router = useRouter()
const keyword = ref('')
const active = ref(0)
const input = ref<HTMLInputElement>()
const listEl = ref<HTMLElement>()

const words = computed(() => keyword.value.trim().toLowerCase().split(/\s+/).filter(Boolean))

const pageHits = computed(() =>
  props.pages.filter((page) => words.value.every((word) => `${page.name} ${page.sub}`.toLowerCase().includes(word))),
)

// ---------- 查数据 ----------
const SILENT = { silent: true }
const SIZE = 5

interface Source {
  /** 这一组的名字 */
  title: string
  /** 侧边栏里有这个页面才查 */
  menu: string
  search: (keyword: string) => Promise<PaletteItem[]>
}

const SOURCES: Source[] = [
  {
    title: '需求订单',
    menu: '/orders',
    search: (kw) =>
      orderApi.page({ page: 1, size: SIZE, keyword: kw }, SILENT).then((r) =>
        r.list.map((o) => ({ name: o.no, path: `/orders/${o.id}`, icon: 'Document', sub: o.styles.map((s) => s.styleCode).join(' ') })),
      ),
  },
  {
    title: '采购单',
    menu: '/purchases',
    search: (kw) =>
      purchaseApi.page({ page: 1, size: SIZE, keyword: kw }, SILENT).then((r) =>
        r.list.map((p) => ({ name: p.no, path: `/purchases/${p.id}`, icon: 'ShoppingCart', sub: p.supplierName })),
      ),
  },
  // 外发加工单、成品采购单一个接口，kind 必传（不传 400，SILENT 吞掉以后这组一直是空的，走查 T-120e64）：
  // 分两组查，各看各的菜单（只开了成品采购单菜单的人也搜得到 CP）
  ...(['OUTSOURCE', 'BUY'] as const).map((kind) => ({
    title: PRODUCTION_KIND[kind].label,
    menu: PRODUCTION_KIND[kind].path,
    search: (kw: string) =>
      productionApi.page({ page: 1, size: SIZE, keyword: kw, kind }, SILENT).then((r) =>
        r.list.map((p) => ({
          name: p.no,
          path: `${PRODUCTION_KIND[p.kind].path}/${p.id}`,
          icon: 'Scissor',
          sub: `${p.styleCode} · ${p.factoryShortName}`,
        })),
      ),
  })),
  {
    title: '款式',
    menu: '/styles',
    search: (kw) =>
      styleApi.page({ page: 1, size: SIZE, keyword: kw }, SILENT).then((r) =>
        r.list.map((s) => ({ name: `${s.code} ${s.name}`, path: `/styles/${s.id}`, icon: 'Goods', sub: s.aliasName ?? '' })),
      ),
  },
  {
    title: '物料',
    menu: '/materials',
    search: (kw) =>
      materialApi.page({ page: 1, size: SIZE, keyword: kw }, SILENT).then((r) =>
        r.list.map((m) => ({ name: `${m.code} ${m.name}`, path: `/materials/${m.id}`, icon: 'Box', sub: '' })),
      ),
  },
  {
    title: '供应商',
    menu: '/suppliers',
    search: (kw) =>
      supplierApi.page({ page: 1, size: SIZE, keyword: kw }, SILENT).then((r) =>
        r.list.map((s) => ({ name: s.name, path: `/suppliers/${s.id}`, icon: 'Shop', sub: s.code })),
      ),
  },
]

const sources = computed(() => {
  const open = new Set(props.pages.map((page) => page.path))
  return SOURCES.filter((source) => open.has(source.menu))
})

const records = ref<{ title: string; items: PaletteItem[] }[]>([])
const searching = ref(false)
/** 查询的序号：后发的先回来时，先发的那次结果不要了 */
let seq = 0
let timer: ReturnType<typeof setTimeout> | undefined

watch(keyword, (value) => {
  active.value = 0
  clearTimeout(timer)
  const kw = value.trim()
  const current = ++seq
  if (kw.length < 2 || !sources.value.length) {
    records.value = []
    searching.value = false
    return
  }
  searching.value = true
  // 停 0.25 秒再查，打字时不会一个字查一次
  timer = setTimeout(async () => {
    const results = await Promise.allSettled(sources.value.map((source) => source.search(kw)))
    if (current !== seq) return
    records.value = sources.value
      .map((source, i) => {
        const result = results[i]!
        return { title: source.title, items: result.status === 'fulfilled' ? result.value : [] }
      })
      .filter((group) => group.items.length)
    searching.value = false
  }, 250)
})

// ---------- 列表、键盘 ----------
const askItem = computed<PaletteItem[]>(() => {
  const kw = keyword.value.trim()
  return props.canAsk && kw.length >= 2 ? [{ name: `问助手：${kw}`, path: 'assistant:ask', icon: 'ChatDotRound', sub: '打开智能助手', ask: kw }] : []
})
const groups = computed(() =>
  [{ title: keyword.value.trim() ? '页面' : '所有页面', items: pageHits.value }, ...records.value, { title: '智能助手', items: askItem.value }].filter((g) => g.items.length),
)
const flat = computed(() => groups.value.flatMap((group) => group.items))

/** 第几组第几条在整个列表里排第几（高亮、回车用） */
function indexOf(groupIndex: number, itemIndex: number) {
  return groups.value.slice(0, groupIndex).reduce((sum, group) => sum + group.items.length, 0) + itemIndex
}

function go(item: PaletteItem | undefined) {
  if (!item) return
  visible.value = false
  if (item.ask) {
    emit('ask', item.ask)
    return
  }
  // 单独成页的（手机报工）和侧边栏一样在新标签里打开
  if (isStandalone(item.path)) window.open(item.path, '_blank')
  else router.push(item.path)
}

async function move(step: number) {
  if (!flat.value.length) return
  active.value = (active.value + step + flat.value.length) % flat.value.length
  await nextTick()
  listEl.value?.querySelector('.on')?.scrollIntoView({ block: 'nearest' })
}

function onKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    void move(event.key === 'ArrowDown' ? 1 : -1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    go(flat.value[active.value])
  } else if (event.key === 'Escape') {
    event.preventDefault()
    visible.value = false
  }
}

// 每次打开都是空的，光标在输入框里；关掉时停掉还没发的查询
watch(visible, async (open) => {
  if (open) {
    keyword.value = ''
    active.value = 0
    await nextTick()
    input.value?.focus()
  } else {
    clearTimeout(timer)
    seq++
  }
})
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="pal-mask" @pointerdown.self="visible = false">
      <div class="pal" role="dialog" aria-modal="true" aria-label="快捷跳转">
        <div class="pal-top">
          <el-icon><Search /></el-icon>
          <input
            ref="input"
            v-model="keyword"
            placeholder="搜索页面、单号、款号、物料、供应商…"
            aria-label="搜索页面、单号、款号、物料、供应商"
            role="combobox"
            aria-expanded="true"
            aria-controls="pal-list"
            @keydown="onKeydown"
          />
          <span v-if="searching" class="pal-busy">查找中…</span>
          <kbd>Esc</kbd>
        </div>
        <div id="pal-list" ref="listEl" class="pal-list" role="listbox">
          <section v-for="(group, gi) in groups" :key="group.title">
            <h3>{{ group.title }}</h3>
            <button
              v-for="(item, ii) in group.items"
              :key="item.path"
              type="button"
              role="option"
              :aria-selected="indexOf(gi, ii) === active"
              :class="{ on: indexOf(gi, ii) === active }"
              @mouseenter="active = indexOf(gi, ii)"
              @click="go(item)"
            >
              <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
              <span v-else class="no-icon" />
              <span class="name">{{ item.name }}</span>
              <small>{{ item.sub }}</small>
            </button>
          </section>
          <p v-if="!flat.length && !searching" class="pal-none">
            没有对得上"{{ keyword.trim() }}"的页面<template v-if="keyword.trim().length >= 2 && sources.length">、单据和资料</template>
          </p>
        </div>
        <footer class="pal-foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
          <span><kbd>↵</kbd> 打开</span>
          <span><kbd>[</kbd> 收起侧边栏</span>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
/* 样子照原型的 .pal：遮罩、正中偏上的白框、底下一行快捷键 */
.pal-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: grid;
  place-items: start center;
  padding: 10vh 16px 16px;
  background: var(--lm-mask);
}

.pal {
  display: flex;
  flex-direction: column;
  width: 580px;
  max-width: 100%;
  max-height: 72vh;
  overflow: hidden;
  background: var(--lm-paper);
  border-radius: var(--lm-radius-panel);
  box-shadow: var(--lm-shadow);
  animation: pal-in 0.15s var(--lm-ease);
}

@keyframes pal-in {
  from {
    opacity: 0;
    transform: translateY(-6px) scale(0.98);
  }
}

.pal-top {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  height: 50px;
  padding: 0 16px;
  color: var(--lm-faint);
  border-bottom: 1px solid var(--lm-line-soft);
}

.pal-top input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0;
  font: inherit;
  font-size: 14.5px;
  color: var(--lm-ink);
  background: none;
  border: 0;
  outline: none;
}

.pal-top input::placeholder {
  color: var(--lm-faint);
}

.pal-busy {
  font-size: 12px;
}

.pal-list {
  flex: 1;
  min-height: 0;
  padding: 4px 6px 6px;
  overflow-y: auto;
}

.pal-list h3 {
  margin: 0;
  padding: 10px 12px 4px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--lm-faint);
}

.pal-list button {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 35px;
  padding: 0 12px;
  font: inherit;
  font-size: 13px;
  color: var(--lm-ink-2);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--lm-radius);
}

.pal-list button.on {
  font-weight: 500;
  color: var(--lm-ink);
  background: var(--lm-hover);
}

.pal-list button.on .el-icon {
  color: var(--lm-accent);
}

.no-icon {
  width: 1em;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pal-list small {
  flex: none;
  max-width: 45%;
  margin-left: auto;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--lm-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pal-none {
  margin: 0;
  padding: 18px 12px;
  font-size: 13px;
  color: var(--lm-faint);
}

.pal-foot {
  display: flex;
  flex: none;
  gap: 16px;
  padding: 8px 16px;
  font-size: 11.5px;
  color: var(--lm-faint);
  background: var(--lm-subtle);
  border-top: 1px solid var(--lm-line-soft);
}

.pal-foot span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

@media (max-width: 599px) {
  .pal-mask {
    padding-top: 12px;
  }

  .pal-foot {
    display: none;
  }
}
</style>
