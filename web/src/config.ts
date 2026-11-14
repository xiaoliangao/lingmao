/**
 * 系统名。改名只改这里：浏览器标题、登录页、侧边栏都从这里取；
 * index.html 的 <title> 打包时也换成它（vite.config.ts），页面刚打开、JS 还没跑起来时标签页上就是对的
 */
export const APP_NAME = '服装 SCM'

/** 侧边栏左上角、登录页上那个小方块里的字：系统名的第一个字 */
export const APP_MARK = APP_NAME.trim().charAt(0)
