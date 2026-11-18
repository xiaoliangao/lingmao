<script setup lang="ts">
import { computed, defineComponent, h, KeepAlive, nextTick, reactive, ref } from 'vue'
import VirtualDataTable, { type VirtualColumn } from '@/components/VirtualDataTable.vue'
import SkuMatrix from '@/components/SkuMatrix.vue'
import FilterBar from '@/components/FilterBar.vue'
import ModuleBoundary from '@/components/ModuleBoundary.vue'
import ThumbImage from '@/components/ThumbImage.vue'
import PendingActions from '@/components/PendingActions.vue'
import RecoveryBanner from '@/components/RecoveryBanner.vue'
import { useTable } from '@/composables/useTable'
import { useColumns } from '@/composables/useColumns'
import { useUnsavedChanges } from '@/composables/useUnsavedChanges'
import { useUserStore } from '@/stores/user'
import { http, configureSessionRefresh, enableCookieSessionRefresh, fetchFile, pendingWriteNotices, checkPendingWrite, restorePendingWrites } from '@/utils/request'
import { getToken, sessionEpoch, setToken, setSessionSubject, onSessionChanged } from '@/utils/token'
import { bindCompanyApi } from '@/utils/companyScope'
import { techPackApi } from '@/api/techpack'
import { orderApi, scheduleApi } from '@/api/order'
import type { ScheduleBlock } from '@/types/order'
import type { RequestOptions } from '@/utils/request'
import { clientActionUuid } from '@/utils/clientAction'
import { routeId } from '@/utils/entityId'
import { claimChunkReload } from '@/utils/chunkRecovery'
import { businessToday, pickerDay, pickerDate } from '@/utils/businessDate'

const user = useUserStore()
user.info = { id: '9007199254740993', username: 'synthetic', nickname: '回归测试', roles: [], perms: ['techpack:cost:query', 'order:price:query', 'production:price:query', 'supplier:supplier:contact', 'supplier:supplier:bank'], menus: [], defaultCompany: { id: '1', name: '测试主体' } }
setSessionSubject(user.info.id)
restorePendingWrites()
const columns: VirtualColumn[] = Array.from({ length: 55 }, (_, i) => ({ key: `c${i}`, label: `字段 ${i}`, width: i === 0 ? 160 : 120, fixed: i === 0 ? 'left' : i === 54 ? 'right' : undefined }))
const rows = Array.from({ length: 1000 }, (_, index) => ({ id: (9007199254740993n + BigInt(index)).toString(), ...Object.fromEntries(columns.map((column, i) => [column.key, `${index}-${i}`])) }))
const sizes = Array.from({ length: 20 }, (_, i) => `S${i}`)
const colors = Array.from({ length: 50 }, (_, i) => ({ id: String(i + 1), name: `颜色${i + 1}` }))
const skus = colors.flatMap((color, row) => sizes.map((sizeCode, col) => ({ id: (9007199254740993n + BigInt(row * 20 + col)).toString(), colorId: color.id, sizeCode })))
const quantities = ref<Record<string, number>>({})
const sum = computed(() => Object.values(quantities.value).reduce((total, value) => total + value, 0))
const table = useTable<{ id: string; keyword: string }, { keyword: string }>({ immediate: false, query: { keyword: '' }, fetch: (params) => http.get('/probe/search', params) })
const searches = ref(0)
const nativeSubmits = ref(0)
function search() { searches.value++; table.search() }
const display = useColumns('a3-test', [{ key: 'date', label: '日期' }, { key: 'note', label: '备注', hidden: true }])
const form = reactive({ remark: '' })
const guard = useUnsavedChanges(form)
const showCached = ref(false)
const CachedRead = defineComponent({ name: 'CachedRead', setup() {
  const cached = useTable<{ id: string; keyword: string }, { keyword: string }>({ query: { keyword: 'cached' }, fetch: (params) => http.get('/probe/search', params) })
  return () => h('output', { id: 'cached-result' }, cached.list.value[0]?.keyword ?? 'loading')
} })
const broken = ref(false)
const BadChild = defineComponent({ setup: () => () => { if (broken.value) throw new Error('synthetic render fault'); return h('span', { id: 'healthy-module' }, '模块可用') } })
const sessionChange = ref(0)
onSessionChanged(() => sessionChange.value++)
configureSessionRefresh(async () => {
  const result = await (await fetch('/probe/refresh', { method: 'POST' })).json() as { code: number; data: string }
  if (result.code !== 0) throw new Error('refresh rejected')
  return result.data
})
const testApi = {
  refresh: () => Promise.all(Array.from({ length: 8 }, (_, i) => http.get<string>(`/probe/auth/${i}`, undefined, { silent: true }))),
  cookieRefresh: enableCookieSessionRefresh,
  logout: user.logout,
  session: () => ({ token: getToken(), epoch: sessionEpoch() }),
  search: (keyword: string) => { table.query.keyword = keyword; search() },
  state: () => ({ searches: searches.value, rows: table.list.value, sum: sum.value, sessionChange: sessionChange.value, pending: pendingWriteNotices.value.map(({ scope, state }) => ({ scope, state })) }),
  write: (amount: number) => http.post<{ id: string; no: string }>('/api/orders', { companyId: '1', amount }, { silent: true }),
  otherWrite: (amount: number) => http.post('/api/materials', { companyId: '1', amount }, { silent: true }),
  parallelWrite: () => Promise.all(Array.from({ length: 20 }, () => http.post<{ id: string; no: string }>('/api/orders', { companyId: '1', amount: 1 }, { silent: true }))),
  checkPending: () => checkPendingWrite(pendingWriteNotices.value[0]!.key),
  setToken: (token: string) => { setToken(token); setSessionSubject(token === 'synthetic-other-account' ? '9007199254740995' : '9007199254740993') },
  columns: () => ({ date: display.show.date, note: display.show.note }),
  nativeSubmits: () => nativeSubmits.value,
  hideColumn: () => display.set('date', false),
  discardDraft: guard.discardDraft,
  cached: (visible: boolean) => { showCached.value = visible },
  businessAfterRefresh: (code: number, transport: string) => http.get(`/probe/auth-replay/${code}?transport=${transport}`, undefined, { silent: true }).then(() => 0, (error) => error.code),
  writeWith401: () => http.post('/api/orders', { companyId: '1', amount: 401 }, { silent: true }),
  changedCompany: () => http.post('/api/orders', { companyId: '2', amount: 2 }, { silent: true }).then(() => 0, (error) => error.code),
  frozenCompany: () => {
    let company = '1'
    const api = bindCompanyApi({ create: (body: { companyId: string; amount: number }) => http.post('/api/orders', body, { silent: true }) }, () => company)
    const body = { companyId: '1', amount: 7 }
    const result = api.create(body)
    body.companyId = '2'; body.amount = 999; company = '2'
    return result
  },
  frozenWrapper: (knownCompany: boolean) => {
    let company = knownCompany ? '1' : undefined
    const api = bindCompanyApi(orderApi, () => company)
    const schedule = bindCompanyApi(scheduleApi, () => company)
    const action = reactive({ version: 3, reason: 'original' })
    const blocks = reactive<ScheduleBlock[]>([{ styleId: '11', factoryId: '12', processMode: 'CUT', unitPrice: 7,
      priceSourceId: null, priceChangeReason: null, dueDate: '2026-12-01', merchId: '13', settleMethod: null,
      terms: null, remark: 'original', items: [{ orderItemId: '14', qty: 7 }] }])
    const actionOptions = reactive<RequestOptions>({ silent: true, idempotencyKey: clientActionUuid() })
    const scheduleOptions = reactive<RequestOptions>({ silent: true, idempotencyKey: clientActionUuid() })
    const keys = { action: actionOptions.idempotencyKey, schedule: scheduleOptions.idempotencyKey }
    const sent = [api.act('9007199254740993', 'submit', action, actionOptions), schedule.schedule('9007199254740993', blocks, scheduleOptions)]
    company = '2'; action.version = 999; action.reason = 'modified'
    blocks[0]!.factoryId = '99'; blocks[0]!.items[0]!.qty = 999; blocks[0]!.remark = 'modified'
    actionOptions.companyId = '2'; scheduleOptions.companyId = '2'
    actionOptions.idempotencyKey = clientActionUuid(); scheduleOptions.idempotencyKey = clientActionUuid()
    return Promise.all(sent).then(() => keys)
  },
  wrapperIdentitySwitch: () => {
    const pending = orderApi.act('9007199254740993', 'submit', { version: 3 }, { silent: true, companyId: '1' })
    setToken('synthetic-other-account')
    return pending.then(() => 0, (error) => error.code)
  },
  readOnlyPost: () => Promise.all([techPackApi.bomDefaults('9007199254740993', []), techPackApi.bomDefaults('9007199254740993', [{ materialId: '1', supplierId: null, skuIds: [] }])]),
  file: (slow = false) => fetchFile(`/probe/file${slow ? '?slow=1' : ''}`, undefined, 'synthetic.txt', { companyId: '1', silent: true }).then((file) => file.text()),
  id: () => http.get(`/probe/id/${routeId('9007199254740993')}`),
  chunk: claimChunkReload,
  dates: () => [businessToday(new Date('2026-11-17T16:05:00Z')), pickerDay(pickerDate('2026-11-17'))],
  break: (value: boolean) => { broken.value = value },
  tick: nextTick,
}
;(window as unknown as { __a3: typeof testApi }).__a3 = testApi
</script>
<template>
  <main class="fixture">
    <header id="navigation"><strong>生产防护组件回归</strong><button id="nav-button" type="button">导航仍可使用</button></header>
    <h2>55 列 × 1000 行 · 实际库存流水组件</h2>
    <VirtualDataTable :rows="rows" :columns="columns" :height="360" />
    <h2>颜色 × 尺码 · 实际录单矩阵</h2>
    <div class="matrix-scroll"><SkuMatrix v-model="quantities" :colors="colors" :sizes="sizes" :skus="skus" /></div>
    <output id="matrix-total">{{ sum }}</output>
    <FilterBar v-model:keyword="table.query.keyword" :columns="display" @search="search" />
    <output id="search-count">{{ searches }}</output>
    <output id="search-result">{{ table.list.value[0]?.keyword ?? '' }}</output>
    <label>草稿备注 <input id="draft-remark" v-model="form.remark" /></label>
    <ThumbImage id="broken-thumbnail" src="/probe/broken-image.png" />
    <ModuleBoundary><BadChild /></ModuleBoundary>
    <PendingActions />
    <RecoveryBanner />
    <form id="native-ime-form" @submit.prevent="nativeSubmits++"><input id="native-ime-input" /><button type="submit">原生表单提交</button></form>
    <KeepAlive><CachedRead v-if="showCached" /></KeepAlive>
  </main>
</template>
<style scoped>
.fixture { padding: 20px; background: var(--lm-paper); color: var(--lm-ink); }
header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 14px; border-bottom: 1px solid var(--lm-line); }
h2 { font-size: 15px; margin: 16px 0 10px; }
.matrix-scroll { max-height: 280px; overflow: auto; }
#draft-remark { margin: 14px; padding: 8px; border: 1px solid var(--lm-line); }
</style>
