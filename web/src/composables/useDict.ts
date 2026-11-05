import { ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { dictApi } from '@/api/system'
import type { DictOption } from '@/types/system'

/**
 * 同一个字典类型 5 分钟内只取一次：一个页面上好几个下拉框用同一个字典，不会重复请求；
 * 管理员改了选项，开着的页面最多 5 分钟后也能看到，不用让大家刷新
 */
const TTL = 5 * 60 * 1000
const cache = new Map<string, { at: number; options: Promise<DictOption[]> }>()

export function loadDict(type: string): Promise<DictOption[]> {
  const hit = cache.get(type)
  if (hit && Date.now() - hit.at < TTL) return hit.options
  const options = dictApi.options(type)
  cache.set(type, { at: Date.now(), options })
  // 取失败的不留在缓存里，下次再取
  options.catch(() => {
    if (cache.get(type)?.options === options) cache.delete(type)
  })
  return options
}

/** 按字典类型编码取启用的选项。type 可以是 ref 或 getter，变了会重新取 */
export function useDict(type: MaybeRefOrGetter<string>) {
  const options = ref<DictOption[]>([])
  const loading = ref(false)

  watch(
    () => toValue(type),
    async (code) => {
      options.value = []
      loading.value = !!code
      if (!code) return
      try {
        const result = await loadDict(code)
        // 等结果的时候类型又换了，这个结果作废
        if (toValue(type) === code) options.value = result
      } catch {
        // 消息拦截器已经弹过了，下拉框就是空的
      } finally {
        if (toValue(type) === code) loading.value = false
      }
    },
    { immediate: true },
  )

  /** 值 → 显示的文字，列表里显示字典项用。字典里没有的（比如已经停用的选项）显示值本身 */
  function labelOf(value: string | null | undefined) {
    if (value == null || value === '') return ''
    return options.value.find((option) => option.value === value)?.label ?? value
  }

  return { options, loading, labelOf }
}
