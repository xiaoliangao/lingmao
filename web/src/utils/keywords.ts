/**
 * 列表关键字里一次查几个单号、款号、编码（PRD §5.4，docs/api/README §3.2）。分隔符、上限和后端 common/page/Keywords 一样：
 * 空白、半角全角逗号、顿号、分号都算分隔；有分隔符才算"几个一起查"。搜索框就一个，单个、几个、从 Excel 粘一列都在里面查
 */

/** 一次最多几个：再多 GET 地址太长 */
export const MAX_CODES = 100
export const MAX_KEYWORD_LENGTH = 8192

// ECMAScript 空白集合加 NEL，和后端 Unicode 空白集合加 BOM 保持一致。
const SEPARATORS = /[\s\u0085,，、;；]+/
const WHITESPACE = /[\s\u0085]+/g
const EDGE_WHITESPACE = /^[\s\u0085]+|[\s\u0085]+$/g

const trimKeywords = (value: string) => value.replace(EDGE_WHITESPACE, '')

export function hasSeparator(keyword: string) {
  return SEPARATORS.test(trimKeywords(keyword))
}

/**
 * 搜索框的 @paste：粘进来的是多行（Excel 复制一列、一块）时，换行、Tab 换成空格、去掉首尾空白再放进框里，
 * 不靠各浏览器自己处理换行（有的会直接吞掉，几个单号粘成一串）。一行的照常粘。
 *
 *   <el-input v-model="query.keyword" @paste="(e: ClipboardEvent) => pasteCodes(e, (v) => (query.keyword = v))" … />
 */
export function pasteCodes(event: ClipboardEvent, set: (value: string) => void) {
  const text = event.clipboardData?.getData('text/plain') ?? ''
  if (!/[\r\n\t]/.test(trimKeywords(text))) return
  event.preventDefault()
  const input = event.target as HTMLInputElement
  const start = input.selectionStart ?? input.value.length
  const end = input.selectionEnd ?? start
  const pasted = trimKeywords(text).replace(WHITESPACE, ' ')
  set(trimKeywords(`${input.value.slice(0, start)} ${pasted} ${input.value.slice(end)}`.replace(WHITESPACE, ' ')))
}

export function validateKeywords(keyword: unknown): void {
  if (typeof keyword !== 'string') return
  if (keyword.length > MAX_KEYWORD_LENGTH) throw new Error('搜索内容过长，请分批查询')
  const codes = new Set(trimKeywords(keyword).split(SEPARATORS).filter(Boolean).map((code) => code.toUpperCase()))
  if (codes.size > MAX_CODES) throw new Error(`一次最多查 ${MAX_CODES} 个编码，请分批查询`)
}
