/// <reference types="vite/client" />

import type { vPerm } from './directives/perm'

// 让模板里的 v-perm 有类型提示
declare module 'vue' {
  interface ComponentCustomProperties {
    vPerm: typeof vPerm
  }
}
