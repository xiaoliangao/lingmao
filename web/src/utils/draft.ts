import { readStorage, writeStorage } from './safeStorage.ts'
export const DRAFT_TTL = 24 * 60 * 60 * 1000
export const DRAFT_LIMIT = 512 * 1024
export interface Draft { at: number; baseline: string; data: unknown }
const secretKey = /password|token|secret|bank|accountNo|accountNumber/i
export function containsSecrets(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false
  return Object.entries(value).some(([key, entry]) => secretKey.test(key) || containsSecrets(entry))
}
export function readDraft(key: string, now = Date.now()): Draft | null {
  const raw = readStorage(key)
  if (!raw) return null
  try {
    if (raw.length > DRAFT_LIMIT) throw new Error('size')
    const value: unknown = JSON.parse(raw)
    if (!value || typeof value !== 'object' || !('at' in value) || !('baseline' in value) || !('data' in value) || typeof value.at !== 'number' || typeof value.baseline !== 'string' || now - value.at > DRAFT_TTL || value.at > now || containsSecrets(value.data)) throw new Error('shape')
    return { at: value.at, baseline: value.baseline, data: value.data }
  } catch { writeStorage(key, null); return null }
}
export function saveDraft(key: string, baseline: string, data: unknown): boolean {
  if (containsSecrets(data)) return false
  try {
    const raw = JSON.stringify({ at: Date.now(), baseline, data })
    return raw.length <= DRAFT_LIMIT && writeStorage(key, raw)
  } catch { return false }
}
/** Mutates existing reactive references, including nested arrays, without replacing a getter's shell. */
const mapFields = new Set(['qtys', 'defects', 'remarks', 'sizeUsages', 'sizePrices', 'skuMap'])
function safeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(safeValue)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).filter(([key]) => !['__proto__', 'constructor', 'prototype'].includes(key)).map(([key, entry]) => [key, safeValue(entry)]))
}
export function restoreDraftValue(target: unknown, value: unknown, field?: string): void {
  if (Array.isArray(target) && Array.isArray(value)) { target.splice(0, target.length, ...value.map(safeValue)); return }
  if (!target || typeof target !== 'object' || !value || typeof value !== 'object' || Array.isArray(value)) return
  const current = target as Record<string, unknown>
  for (const [key, entry] of Object.entries(value)) {
    if (['__proto__', 'constructor', 'prototype'].includes(key)) continue
    if (!(key in current) && !mapFields.has(field ?? '') && !/^\d{1,19}$/.test(key)) continue
    if (current[key] && typeof current[key] === 'object' && entry && typeof entry === 'object') restoreDraftValue(current[key], entry, key)
    else current[key] = safeValue(entry)
  }
}
