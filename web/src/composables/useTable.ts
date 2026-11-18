import { onMounted, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { PageParams } from '@/api/system'
import type { PageResult } from '@/types/system'
import { ApiError, download } from '@/utils/request'
import { cleanQuery } from '@/utils/query'

/** 每页条数的选项（PRD §5.4） */
export const PAGE_SIZES = [20, 50, 100]

export interface UseTableOptions<Row, Q extends object, P extends object = Partial<Q>> {
  /** 列表接口：收"去掉空值的查询条件 + page、size"，返回 { list, total } */
  fetch: (params: P & PageParams) => Promise<PageResult<Row>>
  /** 查询条件的初始值，重置时回到它。数组条件（多选、日期范围）发出去是 key=a&key=b，后端用 List 接 */
  query: Q
  /** 把页面自己用的字段（如状态分段）换成接口的条件；列表和导出都走它，导出才和看到的一样 */
  normalize?: (query: Partial<Q>) => P
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
export function useTable<Row, Q extends object, P extends object = Partial<Q>>(options: UseTableOptions<Row, Q, P>) {
  const initial = structuredClone(toRaw(options.query))
  const query = reactive(structuredClone(initial)) as Q
  const page = ref(1)
  const size = ref(PAGE_SIZES[0]!)
  const list = shallowRef<Row[]>([])
  const total = ref(0)
  const loading = ref(false)
  const exporting = ref(false)
  const error = ref<string | null>(null)

  /** 表格里现在这批数据是按什么条件查的。导出用它：筛选框改了还没点查询时，导出的仍是看到的这批 */
  let shownParams: P | null = null
  /** 查询的序号：连着查了两次，先发的那次后回来时，不能盖掉后发的结果 */
  let seq = 0

  async function load() {
    const current = ++seq
    const clean = cleanQuery(toRaw(query))
    const params = cleanQuery(options.normalize ? options.normalize(clean) : clean) as P
    loading.value = true
    try {
      const result = await options.fetch({ ...params, page: page.value, size: size.value })
      if (current !== seq) return
      list.value = result.list
      total.value = result.total
      shownParams = structuredClone(params)
      error.value = null
    } catch (e) {
      if (current === seq) error.value = e instanceof Error ? e.message : '查询失败，请重试'
      if (!(e instanceof ApiError)) throw e
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

  async function exportRows(ids?: readonly number[]) {
    if (!options.exportUrl) return
    if (!shownParams) {
      ElMessage.info('请先成功查询，再导出当前结果')
      return
    }
    if (total.value === 0) {
      ElMessage.info('没有符合条件的数据，不用导出')
      return
    }
    exporting.value = true
    try {
      const params = { ...structuredClone(shownParams), ...(ids ? { ids: [...ids] } : {}) }
      await download(options.exportUrl, params, options.exportName ?? '导出.xlsx')
    } catch (e) {
      if (!(e instanceof ApiError)) throw e
    } finally {
      exporting.value = false
    }
  }
  const exportFile = () => exportRows()
  const exportSelected = (ids: readonly number[]) => exportRows(ids)

  if (options.immediate ?? true) {
    onMounted(load)
  }

  return { query, page, size, pageSizes: PAGE_SIZES, list, total, loading, error, load, search, reset, exporting, exportFile, exportSelected }
}
