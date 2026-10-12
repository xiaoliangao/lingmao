// token 存在 localStorage：刷新页面不用重新登录；关浏览器再开也还在，直到过期或退出
const KEY = 'lingmao-token'

export function getToken(): string {
  return localStorage.getItem(KEY) ?? ''
}

export function setToken(token: string) {
  localStorage.setItem(KEY, token)
}

export function clearToken() {
  localStorage.removeItem(KEY)
}
