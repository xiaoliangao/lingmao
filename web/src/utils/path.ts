/** 拼菜单的路由地址：上级 /system + 下级 user = /system/user；下级以 / 开头就是绝对地址，直接用 */
export function joinPath(parent: string, path: string) {
  if (path.startsWith('/')) return path
  return `${parent.replace(/\/$/, '')}/${path}`
}
