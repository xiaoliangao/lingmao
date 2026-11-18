import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { isCancel } from 'axios'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 日期面板的星期从周一开始：Element Plus 按 dayjs 的语言包排星期，要把中文语言包加载进来
import 'dayjs/locale/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
// 设计令牌、全局样式、Element Plus 的外观放在 Element Plus 后面，才能覆盖它的变量和样式
import './styles/tokens.css'
import './styles/base.css'
import './styles/element.css'
import App from './App.vue'
import router from './router'
import { vPerm } from './directives/perm'
import { ApiError, enableCookieSessionRefresh } from './utils/request'

import { installChunkRecovery } from './utils/chunkRecovery'
import { accountChanged, hasUnsavedForms, updateAvailable } from './utils/uiHealth'
import { onSessionChanged, startSessionSync } from './utils/token'
import { installImeGuard } from './utils/ime'

startSessionSync()
enableCookieSessionRefresh()
onSessionChanged(() => { accountChanged.value = true })
installImeGuard(document)
installChunkRecovery({ onUpdate: () => { updateAvailable.value = true }, hasUnsavedChanges: hasUnsavedForms, onRouterError: (handler) => router.onError(handler) })

const app = createApp(App)
app.use(createPinia())
app.use(router)
// 所有表格：格子里放不下的字不换行，末尾显示省略号，鼠标移上去看全文。要换行的列自己写 :show-overflow-tooltip="false"
app.use(ElementPlus, { locale: zhCn, table: { showOverflowTooltip: true, tooltipOptions: { popperClass: 'table-tip' } } })
// 图标全部注册成全局组件：菜单表的 icon 字段存的就是图标名，如 Setting、User
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}
app.directive('perm', vPerm)

// 两种"错误"是正常流程，不打红：接口错误（消息已经弹过）、确认框点了取消或关闭（抛出的值是 'cancel' / 'close'）
function isExpected(err: unknown) {
  return err instanceof ApiError || isCancel(err) || err === 'cancel' || err === 'close'
}
// 组件的事件处理、生命周期里没接住的错误
app.config.errorHandler = (err, _instance, info) => {
  if (!isExpected(err)) console.error(`[${info}]`, err)
}
// 组件之外没接住的 Promise 错误
window.addEventListener('unhandledrejection', (event) => {
  if (isExpected(event.reason)) event.preventDefault()
})

// pnpm dev:mock：后端还没写的接口先用前端的假数据（web/src/mock，docs/api/README.md §6）。import.meta.env.DEV 在正式打包时是 false，这段连同 mock 都不进包
async function start() {
  if (import.meta.env.DEV && import.meta.env.VITE_MOCK === 'true') await (await import('./mock')).loadMocks()
  app.mount('#app')
}
void start()
