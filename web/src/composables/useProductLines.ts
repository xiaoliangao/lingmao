import { computed } from 'vue'
import { useDict } from '@/composables/useDict'
import { useUserStore } from '@/stores/user'

/** 字典类型：品类线（服装 APPAREL、鞋类 FOOTWEAR、百货 GENERAL） */
export const PRODUCT_LINE_DICT = 'product_line'

/**
 * 品类线（RFC 013 §2）：当前用户能看哪几条。后端登录时给每条线一个权限 line:XXX，超级管理员给 line:all（看全部）。
 * 只有一条线的人页面上不显示品类线；有几条的，列表能按它筛、多一列，手工建采购单要选一条。
 *
 *   const lines = useProductLines()
 *   lines.multi.value          // 能看不止一条：显示筛选、列
 *   lines.options.value        // 能选的：字典里启用的、我能看的，按字典顺序
 *   lines.only.value           // 只有一条时是它（新建时自动带上），否则 null
 *   lines.labelOf('FOOTWEAR')  // 鞋类
 */
export function useProductLines() {
  const user = useUserStore()
  const dict = useDict(PRODUCT_LINE_DICT)

  /** null = 看全部 */
  const mine = computed<string[] | null>(() => {
    const perms = user.info?.perms ?? []
    if (perms.includes('line:all')) return null
    return perms.filter((p) => p.startsWith('line:')).map((p) => p.slice('line:'.length))
  })

  function canSee(line: string) {
    return mine.value === null || mine.value.includes(line)
  }

  const options = computed(() => dict.enabled.value.filter((o) => canSee(o.value)))
  /** 看全部的（超级管理员）也算几条：字典里内置三条 */
  const multi = computed(() => mine.value === null || mine.value.length > 1)
  const only = computed(() => (mine.value !== null && mine.value.length === 1 ? mine.value[0] : null))

  return { options, multi, only, canSee, labelOf: dict.labelOf, loading: dict.loading }
}
