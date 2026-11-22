/**
 * 把智能助手答完的全文（done.text、历史里 role 是 assistant 的 content）拆成能显示的块（docs/api/08-智能助手.md §3）。
 * 不用 Markdown 库、不出 HTML：只认下面这几种写法，交给 components/assistant/AssistantAnswer.ts 渲染成组件，别的一律当文字。
 *   块：段落（单个换行保留成换行）、# 标题、- / * / 1. 列表（缩进的子项摊平）、| 表格 |、``` 代码块、--- 分隔线
 *   行内：**加粗**、`代码`、[文字](/页面) 链接
 * 链接地址只收 /小写页面 或 /小写页面/数字（助手服务已经只留站内页面，这里再卡一次）；渲染时还要 router 认得这个地址。
 * 模型的原话（delta）、用户的问题不过这里：只当纯文本显示
 */
export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'strong'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'link'; text: string; href: string }

export type Block =
  | { kind: 'p'; lines: Inline[][] }
  | { kind: 'heading'; content: Inline[] }
  | { kind: 'list'; ordered: boolean; start: number; items: Inline[][] }
  | { kind: 'table'; head: Inline[][]; rows: Inline[][][] }
  | { kind: 'pre'; text: string }
  | { kind: 'hr' }

/** 链接能去的地址：/orders、/purchases/12 这样的，不带 ? # 和别的 */
/**
 * 答的过程中显示模型原话前，去掉常见的 HTML 标签（和助手服务 assistant/src/chat.ts 的 stripHtml 同一套）：
 * 答完的全文助手服务已经去过了，这里只为打字的那几秒别闪出 <b>。只管好看，原话照样当纯文本显示
 */
const HTML_TAG =
  /<\/?(?:a|b|i|u|s|em|strong|span|div|p|font|small|big|mark|del|ins|sub|sup|code|pre|kbd|h[1-6]|ul|ol|li|dl|dt|dd|table|thead|tbody|tfoot|tr|td|th|caption|center|img|hr|blockquote|details|summary|iframe|svg|input|button|form)(?:\s+[a-z][^<>]*)?\s*\/?>/gi

export function stripHtml(text: string): string {
  let s = text
  for (let before = ''; before !== s; ) {
    before = s
    s = s
      .replace(/<!--[\s\S]*?-->/g, '')
      .replace(/<(script|style)(?:\s+[a-z][^<>]*)?>[\s\S]*?<\/\1\s*>/gi, '')
      .replace(/<br\s*\/?>/gi, ' ')
      .replace(HTML_TAG, '')
  }
  return s
}

export const PAGE_LINK = /^\/[a-z][a-z-]*(?:\/\d{1,19})?$/

const INLINE = /\[([^[\]\n]{1,100})\]\(([^()\s]{1,200})\)|\*\*([^*\n]+?)\*\*|`([^`\n]+)`/g

export function parseInline(source: string): Inline[] {
  const out: Inline[] = []
  const text = (t: string) => {
    if (!t) return
    const last = out.at(-1)
    if (last?.kind === 'text') last.text += t
    else out.push({ kind: 'text', text: t })
  }
  let at = 0
  for (const m of source.matchAll(INLINE)) {
    text(source.slice(at, m.index))
    at = m.index + m[0].length
    if (m[1] !== undefined) {
      if (PAGE_LINK.test(m[2]!)) out.push({ kind: 'link', text: m[1], href: m[2]! })
      else text(m[1])
    } else if (m[3] !== undefined) out.push({ kind: 'strong', text: m[3] })
    else out.push({ kind: 'code', text: m[4]! })
  }
  text(source.slice(at))
  return out
}

const FENCE = /^\s*```/
const HEADING = /^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$/
const RULE = /^\s{0,3}(?:-\s*){3,}$|^\s{0,3}(?:\*\s*){3,}$|^\s{0,3}(?:_\s*){3,}$/
const BULLET = /^\s*[-*+]\s+(.*)$/
const NUMBERED = /^\s*(\d{1,4})[.)]\s+(.*)$/
const TABLE_ROW = /^\s*\|.*\|\s*$/
const TABLE_RULE = /^\s*\|?(?:\s*:?-{2,}:?\s*\|)+\s*(?::?-{2,}:?\s*)?\|?\s*$/

function cells(row: string): Inline[][] {
  return row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => parseInline(cell.trim()))
}

export function parseAnswer(source: string): Block[] {
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  const blocks: Block[] = []
  let para: Inline[][] = []
  const flush = () => {
    if (para.length) blocks.push({ kind: 'p', lines: para })
    para = []
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!
    if (!line.trim()) {
      flush()
      continue
    }
    if (FENCE.test(line)) {
      flush()
      const code: string[] = []
      while (++i < lines.length && !FENCE.test(lines[i]!)) code.push(lines[i]!)
      blocks.push({ kind: 'pre', text: code.join('\n') })
      continue
    }
    if (TABLE_ROW.test(line) && i + 1 < lines.length && TABLE_RULE.test(lines[i + 1]!)) {
      flush()
      const head = cells(line)
      const rows: Inline[][][] = []
      i++
      while (i + 1 < lines.length && TABLE_ROW.test(lines[i + 1]!)) rows.push(cells(lines[++i]!))
      blocks.push({ kind: 'table', head, rows })
      continue
    }
    const heading = HEADING.exec(line)
    if (heading) {
      flush()
      blocks.push({ kind: 'heading', content: parseInline(heading[1]!) })
      continue
    }
    if (RULE.test(line)) {
      flush()
      blocks.push({ kind: 'hr' })
      continue
    }
    const bullet = BULLET.exec(line)
    const numbered = bullet ? null : NUMBERED.exec(line)
    if (bullet || numbered) {
      flush()
      const ordered = !!numbered
      const items: string[] = [(bullet ?? numbered)![bullet ? 1 : 2]!]
      while (i + 1 < lines.length) {
        const next = lines[i + 1]!
        const item = ordered ? NUMBERED.exec(next) : BULLET.exec(next)
        const other = ordered ? BULLET.exec(next) : NUMBERED.exec(next)
        if (item) items.push(item[ordered ? 2 : 1]!)
        else if (other && /^\s+/.test(next)) items.push(other[ordered ? 1 : 2]!) // 缩进的另一种写法是子项：摊平进同一个列表
        else if (next.trim() && !other && /^\s+/.test(next)) items[items.length - 1] += ' ' + next.trim() // 缩进的续行
        else break
        i++
      }
      blocks.push({ kind: 'list', ordered, start: numbered ? Number(numbered[1]) : 1, items: items.map(parseInline) })
      continue
    }
    para.push(parseInline(line.trim()))
  }
  flush()
  return blocks
}
