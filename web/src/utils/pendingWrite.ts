import { readStorage, writeStorage } from './safeStorage'
import { sha256 } from './sha256'

const STORAGE_PREFIX = 'lingmao-write-v1:'
let receiptsReadable = true
export interface WriteReceipt {
  scope: string
  identity: string
  key: string
  fingerprint: string
  url: string
  companyId?: string
  createdAt: number
}
function isReceipt(row: unknown): row is WriteReceipt {
  if (!row || typeof row !== 'object') return false
  const value = row as Partial<WriteReceipt>
  return typeof value.scope === 'string' && value.scope.length < 2048
    && typeof value.identity === 'string' && value.identity.length < 100
    && typeof value.key === 'string' && /^[\da-f-]{36}$/i.test(value.key)
    && typeof value.fingerprint === 'string' && /^[\da-f]{64}$/.test(value.fingerprint)
    && typeof value.url === 'string' && value.url.length < 1024
    && (value.companyId === undefined || typeof value.companyId === 'string')
    && typeof value.createdAt === 'number' && Number.isFinite(value.createdAt)
}
/** 一张收据一个 localStorage 键，页签之间不会互相覆盖。 */
export function readWriteReceipts(): WriteReceipt[] {
  receiptsReadable = true
  const receipts: WriteReceipt[] = []
  try {
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((key): key is string => !!key && key.startsWith(STORAGE_PREFIX))
    for (const key of keys) {
      const raw = readStorage(key)
      if (raw === null) { receiptsReadable = false; continue }
      const value: unknown = JSON.parse(raw)
      if (!isReceipt(value) || key !== `${STORAGE_PREFIX}${value.key}`) { receiptsReadable = false; continue }
      receipts.push(value)
    }
  } catch { receiptsReadable = false }
  return receipts
}
/** 不存请求内容、access token、价格和联系方式。 */
export function storeWriteReceipt(receipt: WriteReceipt): boolean {
  const receipts = readWriteReceipts()
  if (!receiptsReadable || (receipts.length >= 100 && !receipts.some((row) => row.key === receipt.key))) return false
  const key = `${STORAGE_PREFIX}${receipt.key}`
  const serialized = JSON.stringify(receipt)
  return writeStorage(key, serialized) && readStorage(key) === serialized
}
export function removeWriteReceipt(key: string): boolean {
  return writeStorage(`${STORAGE_PREFIX}${key}`, null)
}
export async function requestFingerprint(body: string, companyId: string | undefined, url: string): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify({ body, companyId: companyId ?? null, url }))
  if (!crypto.subtle) return sha256(bytes)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('')
}
