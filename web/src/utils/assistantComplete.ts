/**
 * 智能助手输入框的补全（docs/api/08 §1"Tab 补全"）：从推荐的问题和自己问过的话里，挑包含输入的。
 *
 *   completions('库存', ['面料库存还剩多少', '库存流水'])   // ['库存流水', '面料库存还剩多少']：开头对得上的在前
 *
 * 不分大小写、多个空格当一个；输入里有空格的，几段都包含就算（"待审核 订单" 能找到"待审核的需求订单有哪些"）。
 * 和输入一模一样的不要（填了也没变化）；同一句只出一次；最多 max 条
 */
export function completions(draft: string, candidates: readonly string[], max = 5): string[] {
  const q = norm(draft)
  if (!q) return []
  const parts = q.split(' ')
  const head: string[] = []
  const rest: string[] = []
  const seen = new Set<string>()
  for (const c of candidates) {
    const n = norm(c)
    if (!n || n === q || seen.has(n)) continue
    seen.add(n)
    if (n.startsWith(q)) head.push(c.trim())
    else if (parts.every((p) => n.includes(p))) rest.push(c.trim())
  }
  return [...head, ...rest].slice(0, max)
}

/** 补全列表里一句话拆成几段，包含输入的那几段 hit 是 true（加粗显示）；输入有空格的，每段分别找 */
export function highlight(text: string, draft: string): { text: string; hit: boolean }[] {
  const parts = norm(draft).split(' ').filter(Boolean)
  if (parts.length === 0) return [{ text, hit: false }]
  const lower = text.toLowerCase()
  const marks = new Array<boolean>(text.length).fill(false)
  for (const p of parts) {
    for (let i = lower.indexOf(p); i !== -1; i = lower.indexOf(p, i + p.length)) marks.fill(true, i, i + p.length)
  }
  const out: { text: string; hit: boolean }[] = []
  for (let i = 0; i < text.length; i++) {
    const last = out.at(-1)
    if (last && last.hit === marks[i]) last.text += text[i]
    else out.push({ text: text[i]!, hit: marks[i]! })
  }
  return out
}

function norm(s: string): string {
  return s.trim().replace(/\s+/g, ' ').toLowerCase()
}
