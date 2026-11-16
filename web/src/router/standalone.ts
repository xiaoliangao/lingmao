/**
 * 单独成页的菜单：不放在左边侧边栏、顶上页签的外框里，用自己的外框（layout/MobileLayout.vue）。
 * 侧边栏、快捷跳转点它时在浏览器新标签里打开，不把工作台这一页换掉、开着的页签也不丢。
 * 地址 → 详情页文件（地址/:id）；路由在 router/index.ts 的 addMenuRoutes 里按它挂
 */
export const STANDALONE_PAGES: Record<string, { detail: string }> = {
  // 手机报工（docs/api/05 §1.4）：给跟单员在手机上用，手机上登录后直接进这里（router 的 phoneHome）
  '/mobile': { detail: 'production/mobile/detail' },
}

export function isStandalone(path: string) {
  return path in STANDALONE_PAGES
}
