import type { InjectionKey } from 'vue'

/** 筛选弹层里的一个条件（FilterItem 登记给 FilterBar）：有 text 就在外面显示成小标签 */
export interface FilterEntry {
  readonly label: string
  /** 选中的值怎么念，如"停用"、"11-01 – 11-14"。空串 = 没选，不出小标签 */
  readonly text: string
  /** 点小标签上的 × ：把这个条件清掉 */
  clear: () => void
}

export interface FilterBarContext {
  register: (entry: FilterEntry) => void
  unregister: (entry: FilterEntry) => void
}

export const FILTER_BAR: InjectionKey<FilterBarContext> = Symbol('FilterBar')
