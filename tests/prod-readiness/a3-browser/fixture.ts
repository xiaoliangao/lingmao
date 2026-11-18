import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/tokens.css'
import '@/styles/base.css'
import '@/styles/element.css'
import Fixture from './Fixture.vue'
import SecurityFixture from './SecurityFixture.vue'
import ExportFixture from './ExportFixture.vue'
import { vPerm } from '@/directives/perm'
import { installImeGuard } from '@/utils/ime'
import { installChunkRecovery } from '@/utils/chunkRecovery'
import { hasUnsavedForms, updateAvailable } from '@/utils/uiHealth'
import { getToken, setToken, startSessionSync } from '@/utils/token'
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Fixture }, { path: '/orders/:id/edit', component: SecurityFixture }, { path: '/purchases/:id/edit', component: SecurityFixture }, { path: '/:pathMatch(.*)*', component: Fixture }] })
if (!getToken()) setToken('synthetic-access')
startSessionSync()
let removeImeGuard = installImeGuard(document)
if (location.search.includes('exports=1')) {
  // Isolate the production input's own guard, then restore the app-wide guard for the other flows.
  ;(window as unknown as { __exportImeGuard: (enabled: boolean) => void }).__exportImeGuard = (enabled) => {
    removeImeGuard()
    removeImeGuard = enabled ? installImeGuard(document) : () => {}
  }
}
installChunkRecovery({ onUpdate: () => { updateAvailable.value = true }, hasUnsavedChanges: hasUnsavedForms, onRouterError: (handler) => router.onError(handler) })
const app = createApp(location.search.includes('exports=1') ? ExportFixture : location.search.includes('security=1') ? SecurityFixture : Fixture).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })
app.directive('perm', vPerm)
const profiling = window as unknown as { __a3Updates: Record<string, number> }
profiling.__a3Updates = {}
app.mixin({ beforeUpdate() {
  const name = (this.$options as { __name?: string }).__name
  if (name === 'SkuMatrix' || name === 'SkuMatrixRow') profiling.__a3Updates[name] = (profiling.__a3Updates[name] ?? 0) + 1
} })
await router.isReady()
const mode = new URLSearchParams(location.search).get('mode')
if (mode === 'order-edit') await router.push('/orders/704/edit')
if (mode === 'purchase-edit' || mode === 'purchase-retry') await router.push('/purchases/705/edit')
if (location.search.includes('exports=1')) await router.push('/stock?view=log')
app.mount('#app')
const missingChunk = '/probe/missing-lazy.js'
router.addRoute({ path: '/missing-chunk', component: () => import(/* @vite-ignore */ missingChunk) })
;(window as unknown as { __a3LazyFailure: () => Promise<unknown> }).__a3LazyFailure = () => { updateAvailable.value = false; return router.push('/missing-chunk').catch(() => null) }
