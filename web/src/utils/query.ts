/** Copy only effective filters; a successful query snapshot cannot follow later form edits. */
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
