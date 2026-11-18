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
import { installImeGuard } from '@/utils/ime'
import { installChunkRecovery } from '@/utils/chunkRecovery'
import { hasUnsavedForms, updateAvailable } from '@/utils/uiHealth'
import { getToken, setToken, startSessionSync } from '@/utils/token'
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Fixture }] })
if (!getToken()) setToken('synthetic-access')
startSessionSync()
installImeGuard(document)
installChunkRecovery({ onUpdate: () => { updateAvailable.value = true }, hasUnsavedChanges: hasUnsavedForms, onRouterError: (handler) => router.onError(handler) })
const app = createApp(Fixture).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })
const profiling = window as unknown as { __a3Updates: Record<string, number> }
profiling.__a3Updates = {}
app.mixin({ beforeUpdate() {
  const name = (this.$options as { __name?: string }).__name
  if (name === 'SkuMatrix' || name === 'SkuMatrixRow') profiling.__a3Updates[name] = (profiling.__a3Updates[name] ?? 0) + 1
} })
await router.isReady()
app.mount('#app')
const missingChunk = '/probe/missing-lazy.js'
router.addRoute({ path: '/missing-chunk', component: () => import(/* @vite-ignore */ missingChunk) })
;(window as unknown as { __a3LazyFailure: () => Promise<unknown> }).__a3LazyFailure = () => { updateAvailable.value = false; return router.push('/missing-chunk').catch(() => null) }
