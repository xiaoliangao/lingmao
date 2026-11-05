/* ============================================================
 * 外框：手写 hash 路由、侧边栏、页签
 * ============================================================ */
const MENUS = [
  { path: '/home', title: '首页', icon: 'HomeFilled' },
  {
    id: 'base', title: '基础资料', icon: 'Files',
    children: [
      { path: '/materials', title: '物料档案' },
      { path: '/color-size', title: '颜色尺码' },
      { path: '/warehouses', title: '仓库' },
    ],
  },
  { path: '/suppliers', title: '供应商', icon: 'Shop' },
  { id: 'style', title: '款式', icon: 'Brush', children: [{ path: '/styles', title: '款式档案' }] },
  { id: 'sample', title: '研发打样', icon: 'Scissor', children: [{ path: '/samples', title: '样衣与打样', phase: '二期' }] },
  { path: '/orders', title: '订单', icon: 'Tickets' },
  {
    id: 'material', title: '物料', icon: 'Box',
    children: [
      { path: '/mrp', title: '算料' },
      { path: '/purchases', title: '采购单' },
      { path: '/arrivals', title: '到料跟踪' },
      { path: '/stock-in', title: '入库单' },
      { path: '/stock-out', title: '出库领料单' },
      { path: '/stock', title: '库存' },
      { path: '/stock-log', title: '库存日志' },
    ],
  },
  {
    id: 'prod', title: '生产', icon: 'Van',
    children: [
      { path: '/production', title: '外发加工单' },
      { path: '/board', title: '生产看板' },
      { path: '/mobile', title: '手机报工' },
    ],
  },
]
const MENU_ITEMS = MENUS.flatMap((m) => m.children || [m])

const NotFoundPage = page({
  props: ['params', 'query'],
  template: `<div class="page"><BlankState title="原型里没有这个页面" description="链接可能写错了，或者这个页面在 PRD 里但原型没画。"><el-button @click="go('/home')">回首页</el-button></BlankState></div>`,
})

const ROUTES = [
  ['/home', HomePage],
  ['/materials', MaterialsPage],
  ['/color-size', ColorSizePage],
  ['/warehouses', WarehousePage],
  ['/suppliers', SupplierPage],
  ['/styles', StyleListPage],
  ['/styles/:id', StyleDetailPage, (p) => '款式 ' + p.id],
  ['/samples', SampleListPage],
  ['/samples/:id', SampleDetailPage, (p) => '样衣 ' + p.id],
  ['/orders', OrderListPage],
  ['/orders/:id', OrderDetailPage, (p) => '订单 ' + p.id],
  ['/mrp', MrpPage],
  ['/purchases', PurchaseListPage],
  ['/purchases/:id', PurchaseDetailPage, (p) => '采购单 ' + p.id],
  ['/arrivals', ArrivalsPage],
  ['/stock-in', StockInPage],
  ['/stock-out', StockOutPage],
  ['/stock', StockPage],
  ['/stock-log', StockLogPage],
  ['/production', ProductionListPage],
  ['/production/:id', ProductionDetailPage, (p) => '加工单 ' + p.id],
  ['/board', BoardPage],
  ['/mobile', MobilePage],
]

function parseHash() {
  const raw = decodeURI(location.hash.replace(/^#/, '')) || '/home'
  const [path, qs = ''] = raw.split('?')
  const query = Object.fromEntries(new URLSearchParams(qs))
  for (const [pattern, comp, titleOf] of ROUTES) {
    const keys = []
    const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '/?$')
    const m = path.match(re)
    if (!m) continue
    const params = Object.fromEntries(keys.map((k, i) => [k, m[i + 1]]))
    const menu = MENU_ITEMS.find((x) => x.path === pattern) || MENU_ITEMS.find((x) => pattern.startsWith(x.path + '/'))
    return { path, full: raw, query, params, comp: Vue.markRaw(comp), title: titleOf ? titleOf(params) : menu?.title || pattern, menu: menu?.path || '' }
  }
  return { path, full: raw, query, params: {}, comp: Vue.markRaw(NotFoundPage), title: '找不到页面', menu: '' }
}

const App = {
  data: () => ({ db, route: parseHash(), tabs: [{ key: '/home', full: '/home', title: '首页' }], kw: '', MENUS }),
  computed: {
    menus() {
      const k = this.kw.trim()
      if (!k) return MENUS
      return MENUS.map((m) => (m.children ? { ...m, children: m.children.filter((c) => c.title.includes(k)) } : m))
        .filter((m) => (m.children ? m.children.length : m.title.includes(k)))
    },
    openeds() {
      return MENUS.filter((m) => m.children).map((m) => m.id)
    },
  },
  created() {
    this.syncTab()
    window.addEventListener('hashchange', () => {
      this.route = parseHash()
      this.syncTab()
      document.querySelector('.shell-content')?.scrollTo(0, 0)
    })
  },
  methods: {
    go,
    syncTab() {
      const r = this.route
      const t = this.tabs.find((x) => x.key === r.path)
      if (t) Object.assign(t, { full: r.full, title: r.title })
      else {
        this.tabs.push({ key: r.path, full: r.full, title: r.title })
        if (this.tabs.length > 10) this.tabs.splice(1, 1)
      }
      document.title = r.title + ' · SCM 原型'
      nextTick(() => document.querySelector('.tab.active')?.scrollIntoView({ inline: 'nearest', block: 'nearest' }))
    },
    closeTab(t) {
      const i = this.tabs.indexOf(t)
      this.tabs.splice(i, 1)
      if (t.key === this.route.path) go((this.tabs[i] || this.tabs[i - 1]).full)
    },
    searchGo() {
      const k = this.kw.trim().toUpperCase()
      const doc = [
        [/^DD\d{12}$/, '/orders/'], [/^CG\d{12}$/, '/purchases/'], [/^JG\d{12}$/, '/production/'], [/^YY\d{12}$/, '/samples/'],
      ].find(([re]) => re.test(k))
      if (doc) return go(doc[1] + k)
      if (styleOf(k)) return go('/styles/' + k)
      const first = this.menus.flatMap((m) => m.children || [m])[0]
      if (first) go(first.path)
      else proto('全局搜单号、款号、物料编码')
    },
  },
  template: `
  <div class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-en">SCM</span><span class="brand-cn">服装供应链</span></div>
      <div class="side-search"><el-input v-model="kw" size="small" clearable prefix-icon="Search" placeholder="搜菜单、单号、款号" @keyup.enter="searchGo" /></div>
      <nav class="nav" aria-label="主菜单">
        <el-menu :default-active="route.menu" :default-openeds="openeds" @select="go">
          <template v-for="m in menus" :key="m.id || m.path">
            <el-sub-menu v-if="m.children" :index="m.id">
              <template #title><el-icon><component :is="m.icon" /></el-icon><span>{{ m.title }}</span></template>
              <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path">{{ c.title }}<span v-if="c.phase" class="phase">{{ c.phase }}</span></el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="m.path"><el-icon><component :is="m.icon" /></el-icon><span>{{ m.title }}</span></el-menu-item>
          </template>
        </el-menu>
      </nav>
    </aside>
    <div class="shell-main">
      <header class="app-header">
        <div class="tabs" role="tablist" aria-label="已打开的页面">
          <div v-for="t in tabs" :key="t.key" class="tab" :class="{ active: t.key === route.path, closable: t.key !== '/home' }">
            <a role="tab" :aria-selected="t.key === route.path" @click="go(t.full)">{{ t.title }}</a>
            <button v-if="t.key !== '/home'" type="button" class="close" :aria-label="'关闭 ' + t.title" @click.stop="closeTab(t)"><el-icon><Close /></el-icon></button>
          </div>
        </div>
        <div class="right">
          <span class="proto-badge" title="数据都是虚构的，只存在浏览器内存里；刷新页面恢复初始数据">原型 · 假数据，刷新即恢复</span>
          <el-icon><User /></el-icon><span>{{ db.me.name }} · {{ db.me.role }}</span>
        </div>
      </header>
      <main class="shell-content">
        <component :is="route.comp" :key="route.full" :params="route.params" :query="route.query" />
      </main>
    </div>
  </div>`,
}

const app = Vue.createApp(App)
app.config.errorHandler = (err, vm, info) => {
  console.error('[原型出错]', info, err)
  document.body.setAttribute('data-vue-error', String(err && err.message))
}
app.config.warnHandler = (msg, vm, trace) => {
  console.warn('[Vue warn]', msg, trace)
  const prev = document.body.getAttribute('data-vue-warn') || ''
  document.body.setAttribute('data-vue-warn', (prev ? prev + ' || ' : '') + msg)
}
app.use(ElementPlus, { locale: ElementPlusLocaleZhCn })
for (const [name, comp] of Object.entries(ElementPlusIconsVue)) app.component(name, comp)
app.mount('#app')
