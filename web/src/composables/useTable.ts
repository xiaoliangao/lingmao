import { onMounted, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { PageParams } from '@/api/system'
import type { PageResult } from '@/types/system'
import { download } from '@/utils/request'

/** 每页条数的选项（PRD §5.4） */
export const PAGE_SIZES = [20, 50, 100]

export interface UseTableOptions<Row, Q extends object> {
  /** 列表接口：收"去掉空值的查询条件 + page、size"，返回 { list, total } */
  fetch: (params: Partial<Q> & PageParams) => Promise<PageResult<Row>>
  /** 查询条件的初始值，重置时回到它。数组条件（多选、日期范围）发出去是 key=a&key=b，后端用 List 接 */
  query: Q
  /** 导出接口，如 /api/suppliers/export。不传就没有导出 */
  exportUrl?: string
  /** 导出的文件名：后端响应头里带了文件名就用后端的，取不到才用它 */
  exportName?: string
  /** 进页面就查一次，默认 true */
  immediate?: boolean
}

/**
 * 列表页的查询、分页、加载状态、导出。页面里这样用：
 *
 *   const { query, page, size, pageSizes, list, total, loading, search, reset, exporting, exportFile } = useTable({
 *     fetch: supplierApi.page,
 *     query: { keyword: '', status: undefined as string | undefined },
 *     exportUrl: '/api/suppliers/export',
 *   })
 *
 *   <FilterBar @search="search" @reset="reset">…v-model="query.keyword"…</FilterBar>
 *   <el-table v-loading="loading" :data="list">…</el-table>
 *   <el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="pageSizes" :total="total"
 *                  class="pagination" layout="total, sizes, prev, pager, next" />
 *
 * el-pagination 上不要再写 @change：翻页、改条数由这里的 watch 去查，再写一个就查两次
 */
export function useTable<Row, Q extends object>(options: UseTableOptions<Row, Q>) {
  const initial = structuredClone(toRaw(options.query))
  const query = reactive(structuredClone(initial)) as Q
  const page = ref(1)
  const size = ref(PAGE_SIZES[0]!)
  const list = shallowRef<Row[]>([])
  const total = ref(0)
  const loading = ref(false)
  const exporting = ref(false)

  /** 表格里现在这批数据是按什么条件查的。导出用它：筛选框改了还没点查询时，导出的仍是看到的这批 */
  let shownParams: Partial<Q> | null = null
  /** 查询的序号：连着查了两次，先发的那次后回来时，不能盖掉后发的结果 */
  let seq = 0

  async function load() {
    const current = ++seq
    const params = clean(query)
    loading.value = true
    try {
      const result = await options.fetch({ ...params, page: page.value, size: size.value })
      if (current !== seq) return
      list.value = result.list
      total.value = result.total
      shownParams = params
    } finally {
      if (current === seq) loading.value = false
    }
  }

  /** 按现在的条件从第一页查。页码本来不是 1 的，改成 1 以后由下面的 watch 去查 */
  function search() {
    if (page.value === 1) {
      void load()
    } else {
      page.value = 1
    }
  }

  function reset() {
    Object.assign(query, structuredClone(initial))
    search()
  }

  // 翻页、改每页条数（el-pagination 改了 page、size）都在这里查。删掉最后一页的最后一条后，
  // el-pagination 会把页码退回上一页，也是走这里
  watch([page, size], () => load())

  async function exportFile() {
    if (!options.exportUrl) return
    if (shownParams && total.value === 0) {
      ElMessage.info('没有符合条件的数据，不用导出')
      return
    }
    exporting.value = true
    try {
      await download(options.exportUrl, shownParams ?? clean(query), options.exportName ?? '导出.xlsx')
    } finally {
      exporting.value = false
    }
  }

  if (options.immediate ?? true) {
    onMounted(load)
  }

  return { query, page, size, pageSizes: PAGE_SIZES, list, total, loading, load, search, reset, exporting, exportFile }
}

/** 去掉没填的条件（空串、null、undefined、空数组），字符串去掉首尾空格。数组复制一份，存下来以后不会跟着筛选框变 */
function clean<Q extends object>(query: Q): Partial<Q> {
  const params: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(query)) {
    const value: unknown = typeof raw === 'string' ? raw.trim() : raw
    if (value === '' || value == null || (Array.isArray(value) && value.length === 0)) continue
    params[key] = Array.isArray(value) ? [...value] : value
  }
  return params as Partial<Q>
}
