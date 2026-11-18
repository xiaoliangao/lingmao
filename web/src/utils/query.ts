/** 去掉没填的条件（空串、null、undefined、空数组），字符串去掉首尾空格；值都复制一份，存下来以后不会跟着筛选框变 */
export function cleanQuery<Q extends object>(query: Q): Partial<Q> {
  const result: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(query)) {
    const value: unknown = typeof raw === 'string' ? raw.trim() : raw
    if (value === '' || value == null || (Array.isArray(value) && !value.length)) continue
    result[key] = structuredClone(value)
  }
  return result as Partial<Q>
}

export function statusQuery<Q extends { statusTab?: string }>(query: Q) {
  const { statusTab, ...rest } = query
  return { ...rest, status: statusTab ? [statusTab as Exclude<Q['statusTab'], '' | undefined>] : undefined }
}
