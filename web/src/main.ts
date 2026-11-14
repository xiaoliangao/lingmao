import { createApp } from 'vue'
import { createPinia } from 'pinia'
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
import { ApiError } from './utils/request'

// 发新版以后，旧版带哈希的 js、css 就从服务器上删掉了。开着旧页面的人点一个还没打开过的菜单（路由是懒加载的），
// 去取旧文件会 404，页面就白了。Vite 加载失败时发 vite:preloadError，这时刷新一次，换成新版的页面。
// 刷新后 10 秒内又失败（比如这一版本身就缺文件），就不再刷，让错误照常抛出，免得一直刷
const RELOADED_AT_KEY = 'lingmao-reloaded-at'

/** 记下这次刷新的时间。10 秒内刚刷过，或者存不了（浏览器禁用了存储，记不住刷没刷过，宁可不刷）返回 false */
function markReload() {
  try {
    if (Date.now() - Number(sessionStorage.getItem(RELOADED_AT_KEY)) < 10_000) return false
    sessionStorage.setItem(RELOADED_AT_KEY, String(Date.now()))
    return true
  } catch {
    return false
  }
}

window.addEventListener('vite:preloadError', (event) => {
  if (!markReload()) return
  event.preventDefault()
  location.reload()
})

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 图标全部注册成全局组件：菜单表的 icon 字段存的就是图标名，如 Setting、User
for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}
app.directive('perm', vPerm)

// 两种"错误"是正常流程，不打红：接口错误（消息已经弹过）、确认框点了取消或关闭（抛出的值是 'cancel' / 'close'）
function isExpected(err: unknown) {
  return err instanceof ApiError || err === 'cancel' || err === 'close'
}
// 组件的事件处理、生命周期里没接住的错误
app.config.errorHandler = (err, _instance, info) => {
  if (!isExpected(err)) console.error(`[${info}]`, err)
}
// 组件之外没接住的 Promise 错误
window.addEventListener('unhandledrejection', (event) => {
  if (isExpected(event.reason)) event.preventDefault()
})

app.mount('#app')
