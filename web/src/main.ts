import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { vPerm } from './directives/perm'
import { ApiError } from './utils/request'
import './style.css'

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
