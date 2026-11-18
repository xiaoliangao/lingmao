import { computed, onMounted, reactive, ref, shallowRef, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useLatestRequest } from './useLatestRequest'
import { cleanQuery } from '@/utils/query'
import { download } from '@/utils/request'

export interface SeekResult<Row> { list: Row[]; nextCursor: string | null; hasMore: boolean }
/** Cursor history belongs to one filter snapshot. Turning pages never performs COUNT/OFFSET. */
export function useSeekTable<Row, Q extends object>(options: {
  query: Q
  fetch: (params: Partial<Q> & { cursor?: string; size: number }) => Promise<SeekResult<Row>>
  exportUrl?: string
  exportName?: string
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
      if (!response || !Array.isArray(response.list) || typeof response.hasMore !== 'boolean' || (response.hasMore && typeof response.nextCursor !== 'string')) throw new Error('流水列表格式异常，请重试')
      list.value = response.list
      hasMore.value = response.hasMore
      nextCursor = response.nextCursor
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
    if (!options.exportUrl || !shown || error.value) { ElMessage.info('请先成功查询，再导出当前结果'); return }
    if (exporting.value) return
    exporting.value = true
    try { await download(options.exportUrl, shown, options.exportName ?? '导出.xlsx') } finally { exporting.value = false }
  }
  watch(size, search)
  if (options.immediate ?? true) onMounted(search)
  return { query, list, page, size, loading, exporting, error, hasMore, canPrevious: computed(() => page.value > 1), search, load, next, previous, exportFile }
}
