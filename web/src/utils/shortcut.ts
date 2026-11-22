/**
 * 快捷键怎么显示：苹果电脑（Mac、iPad）上是 ⌘K，别的（Windows、Linux）是 Ctrl+K。按键本身两样都认（AppLayout 的 onKey）。
 *
 *   shortcut('K')   // '⌘K' 或 'Ctrl+K'
 */
export const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad|iPod/.test(navigator.userAgent)

export function shortcut(key: string): string {
  return IS_MAC ? `⌘${key}` : `Ctrl+${key}`
}
