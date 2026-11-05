/**
 * 系统名。改名只改这里：浏览器标题、登录页、侧边栏都从这里取；
 * index.html 的 <title> 打包时也换成它（vite.config.ts），页面刚打开、JS 还没跑起来时标签页上就是对的
 */
export const APP_NAME = '服装 SCM'

/** 登录页的织唛和侧边栏把名字按空格分两段排：前一段白色中文，后一段金黄色英文；没有空格就只有前一段 */
const [cn, ...rest] = APP_NAME.split(' ')
export const APP_BRAND = { cn, en: rest.join(' ') }
