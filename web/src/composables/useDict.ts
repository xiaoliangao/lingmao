import { computed, reactive, ref, toValue, watch, type MaybeRefOrGetter } from 'vue'
import { dictApi } from '@/api/system'
import type { DictOption } from '@/types/system'

/**
 * 同一个字典类型 5 分钟内只取一次：一个页面上好几个下拉框用同一个字典，不会重复请求；
 * 管理员改了选项，开着的页面最多 5 分钟后也能看到，不用让大家刷新
 */
const TTL = 5 * 60 * 1000
const cache = new Map<string, { at: number; options: Promise<DictOption[]> }>()
/** 每个类型的版本号：reloadDict 加一，开着的页面里用这个类型的 useDict 都重新取 */
const versions = reactive<Record<string, number>>({})

/**
 * 刚加了选项（如新建物料时当场加的物料类型）：扔掉缓存重新取，开着的下拉、列表里的 labelOf 跟着换，不用等 5 分钟。
 * 返回新取的选项，等它回来再选新值，下拉里不会先闪一下值本身
 */
export function reloadDict(type: string): Promise<DictOption[]> {
  cache.delete(type)
  const options = loadDict(type)
  versions[type] = (versions[type] ?? 0) + 1
  return options
}

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

/**
 * 按字典类型编码取选项。type 可以是 ref 或 getter，变了会重新取。
 * options 是全部选项，停用的也在（status 0）：旧数据里存着停用的值，labelOf 要能翻成文字。
 * 让人选的地方只列启用的：用 DictSelect，或者自己列的时候用 enabled
 */
export function useDict(type: MaybeRefOrGetter<string>) {
  const options = ref<DictOption[]>([])
  const loading = ref(false)

  watch(
    () => [toValue(type), versions[toValue(type)] ?? 0] as const,
    async ([code], old, onCleanup) => {
      // 缓存的请求属于所有订阅者，只把这一个订阅者标成过期。
      let current = true
      onCleanup(() => { current = false })
      // 只是重新取（版本号变了）时先留着旧的选项，下拉不闪成空
      if (code !== old?.[0]) options.value = []
      loading.value = !!code
      if (!code) return
      try {
        const result = await loadDict(code)
        // 等结果的时候类型又换了，这个结果作废
        if (current && toValue(type) === code) options.value = result
      } catch {
        // 消息拦截器已经弹过了，下拉框就是空的
      } finally {
        if (current && toValue(type) === code) loading.value = false
      }
    },
    { immediate: true },
  )

  /** 启用的选项：分段控件、筛选这些自己列选项的地方用 */
  const enabled = computed(() => options.value.filter((option) => option.status === 1))

  /** 值 → 显示的文字，列表里显示字典项用。停用的选项照样翻；字典里没有的（选项被删了）显示值本身 */
  function labelOf(value: string | null | undefined) {
    if (value == null || value === '') return ''
    return options.value.find((option) => option.value === value)?.label ?? value
  }

  return { options, enabled, loading, labelOf }
}
