/**
 * 列表关键字里一次查几个单号、编码（PRD §5.4，docs/api/README §3.2）。分隔符、去重、上限都和后端 common/page/Keywords 一样：
 * 空白（Excel 粘一列是换行）、半角全角逗号、顿号、分号都算分隔；有分隔符才算"几个一起查"
 */

/** 一次最多几个：再多 GET 地址太长 */
export const MAX_CODES = 200

const SEPARATORS = /[\s,，、;；]+/

export function hasSeparator(keyword: string) {
  return SEPARATORS.test(keyword.trim())
}

/** 拆开、去重（不分大小写，后端按大写比），按原来的顺序 */
export function splitCodes(text: string) {
  const seen = new Set<string>()
  return text.split(SEPARATORS).filter((t) => {
    const key = t.toUpperCase()
    if (!t || seen.has(key)) return false
    seen.add(key)
    return true
  })
}
