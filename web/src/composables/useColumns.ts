import { computed, reactive, watch } from 'vue'
import { useUserStore } from '@/stores/user'

/**
 * 列表页"显示列"：哪些列显示由各人自己挑（FilterBar 最右边的"显示列"按钮），记在这台电脑的浏览器里，按人、按页面分开记。
 *
 *   const cols = useColumns('orders', [
 *     { key: 'orderDate', label: '下单日期' },
 *     { key: 'contractNo', label: '合同号', hidden: true },   // 默认不显示，勾上才有
 *   ])
 *
 *   <FilterBar … :columns="cols">…</FilterBar>
 *   <el-table-column v-if="cols.show.orderDate" label="下单日期" … />
 *
 * 单号、勾选列、操作列这种不能藏的不写进来，模板里照常写、不加 v-if。
 * 记的是"跟默认不一样的几列"，以后页面加了新列，新列按它自己的默认显示或不显示
 */
export interface ColumnDef<K extends string = string> {
  key: K
  /** 显示列里的名字，和表头一样 */
  label: string
  /** 默认不显示 */
  hidden?: boolean
}

export interface ColumnsState<K extends string = string> {
  readonly defs: readonly ColumnDef<K>[]
  /** 每列现在显不显示，模板里 v-if 用 */
  readonly show: Record<K, boolean>
  /** 和默认的不一样（"恢复默认"能不能点） */
  readonly changed: boolean
  reset(): void
}

interface Saved {
  /** 默认不显示、勾上了的 */
  on: string[]
  /** 默认显示、去掉了的 */
  off: string[]
}

function read(key: string): Saved | null {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? 'null') as Saved | null
    return saved && Array.isArray(saved.on) && Array.isArray(saved.off) ? saved : null
  } catch {
    // 读不了（隐私模式、存的东西坏了）就用默认的
    return null
  }
}

function write(key: string, saved: Saved) {
  try {
    if (saved.on.length || saved.off.length) localStorage.setItem(key, JSON.stringify(saved))
    else localStorage.removeItem(key)
  } catch {
    // 存不了就只在这次打开里生效
  }
}

export function useColumns<const K extends string>(page: string, defs: readonly ColumnDef<K>[]): ColumnsState<K> {
  const storageKey = `lingmao-cols:${useUserStore().info?.id ?? ''}:${page}`
  const defaults = () => Object.fromEntries(defs.map((def) => [def.key, !def.hidden])) as Record<K, boolean>

  const show = reactive(defaults()) as Record<K, boolean>
  const saved = read(storageKey)
  if (saved) {
    for (const def of defs) {
      if (saved.on.includes(def.key)) show[def.key] = true
      if (saved.off.includes(def.key)) show[def.key] = false
    }
  }

  const diff = computed<Saved>(() => ({
    on: defs.filter((def) => def.hidden && show[def.key]).map((def) => def.key),
    off: defs.filter((def) => !def.hidden && !show[def.key]).map((def) => def.key),
  }))

  watch(diff, (value) => write(storageKey, value))

  return reactive({
    defs,
    show,
    changed: computed(() => diff.value.on.length + diff.value.off.length > 0),
    reset: () => Object.assign(show, defaults()),
  }) as ColumnsState<K>
}
