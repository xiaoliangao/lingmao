import { computed, onMounted, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useLatestRequest } from './useLatestRequest'
import { cleanQuery } from '@/utils/query'
import { download } from '@/utils/request'

export interface SeekResult<Row> { list: Row[]; nextCursor: string | null; hasMore: boolean }
/** 游标历史只属于一份筛选快照。翻页从不做 COUNT/OFFSET。 */
export function useSeekTable<Row, Q extends object, Result extends SeekResult<Row> = SeekResult<Row>>(options: {
  query: Q
  fetch: (params: Partial<Q> & { cursor?: string; size: number }) => Promise<SeekResult<Row> & Result>
  /** 只在当前请求成功后接受统计/权限等元数据，过期响应不能回写它们。 */
  onLoaded?: (response: Result) => void
  exportUrl?: string
  exportName?: string
  exporter?: (filters: Partial<Q>) => Promise<void> | void
  immediate?: boolean
}) {
  const query = reactive(structuredClone(options.query)) as Q
  const list = shallowRef<Row[]>([])
  const page = ref(1)
  const size = ref(50)
  const loading = ref(false)
  const exporting = ref(false)
  const error = ref<string | null>(null)
  const hasMore = ref(false)
  const latest = useLatestRequest()
  let cursors: (string | undefined)[] = [undefined]
  let nextCursor: string | null = null
  let filters: Partial<Q> = cleanQuery(toRaw(query))
  let shown: Partial<Q> | null = null
  async function load() {
    const read = latest.begin()
    loading.value = true
    try {
      const response = await read.run(() => options.fetch({ ...filters, cursor: cursors[page.value - 1], size: size.value }))
      if (!read.current()) return
      if (!response || !Array.isArray(response.list) || typeof response.hasMore !== 'boolean' || (response.hasMore && typeof response.nextCursor !== 'string')) throw new Error('列表格式异常，请重试')
      list.value = response.list
      hasMore.value = response.hasMore
      nextCursor = response.nextCursor
      options.onLoaded?.(response)
      shown = structuredClone(filters)
      error.value = null
    } catch (cause) {
      if (read.current()) error.value = cause instanceof Error ? cause.message : '查询失败，请重试'
    } finally { if (read.current()) loading.value = false }
  }
  function search() { filters = cleanQuery(toRaw(query)); cursors = [undefined]; page.value = 1; void load() }
  function next() { if (!loading.value && hasMore.value && nextCursor) { cursors[page.value] = nextCursor; page.value++; void load() } }
  function previous() { if (!loading.value && page.value > 1) { page.value--; void load() } }
  async function exportFile() {
    if ((!options.exportUrl && !options.exporter) || !shown || error.value) { ElMessage.info('请先成功查询，再导出当前结果'); return }
    if (exporting.value) return
    exporting.value = true
    const snapshot = structuredClone(shown)
    try {
      if (options.exporter) await options.exporter(snapshot)
      else await download(options.exportUrl!, snapshot, options.exportName ?? '导出.xlsx')
    } finally { exporting.value = false }
  }
  watch(size, search)
  if (options.immediate ?? true) onMounted(search)
  return { cancel: () => { latest.cancel(); loading.value = false }, query, list, page, size, loading, exporting, error, hasMore, canPrevious: computed(() => page.value > 1), search, load, next, previous, exportFile }
}
