import type { SkuSpec } from '@/api/basic'

/** 规格码的规则，和后端 SkuCodes 一样（RFC 003 §7.1） */
const UNIT_SUFFIX = /(cm|mm|厘米|毫米|码)$/i
const SPEC_CODE = /^[A-Z0-9.]{1,16}$/

/** 手改过的规格码：规格 → 规格码 */
export type SpecCodeOverrides = Record<string, string>

export interface ParsedSpec {
  spec: string
  /** 手改过的用手改的，没改过的由规格算（去掉末尾的单位、转大写）；算完是空的为 null */
  specCode: string | null
  /** 手改过 */
  edited: boolean
  /** 规格码只能是字母、数字、小数点，最长 16 位，不合格的后端不收 */
  valid: boolean
}

/** 规格算出来的规格码 */
export function specCodeOf(spec: string): string | null {
  return spec.replace(UNIT_SUFFIX, '').toUpperCase() || null
}

/** 规格框里的字 → 一个个规格（空格、逗号、顿号隔开，规格码一样的只留第一个） */
export function parseSpecs(text: string, overrides: SpecCodeOverrides = {}): ParsedSpec[] {
  const seen = new Set<string>()
  const result: ParsedSpec[] = []
  for (const spec of text.split(/[\s,，、]+/).filter(Boolean)) {
    const manual = overrides[spec]?.trim().toUpperCase()
    const specCode = manual || specCodeOf(spec)
    if (specCode !== null && seen.has(specCode)) continue
    if (specCode !== null) seen.add(specCode)
    result.push({ spec, specCode, edited: !!manual, valid: specCode !== null && SPEC_CODE.test(specCode) })
  }
  return result
}

/** 发给后端的规格：手改过的带上规格码，没改过的后端按同一个规则算 */
export function specsOf(text: string, overrides: SpecCodeOverrides = {}): SkuSpec[] {
  return parseSpecs(text, overrides).map((s) => (s.edited ? { spec: s.spec, specCode: s.specCode } : { spec: s.spec }))
}

/** 规格码不合格的规格：提交前拦下来 */
export function invalidSpecs(text: string, overrides: SpecCodeOverrides = {}): string[] {
  return parseSpecs(text, overrides).filter((s) => !s.valid).map((s) => s.spec)
}
