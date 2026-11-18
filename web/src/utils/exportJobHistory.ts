import { readStorage, writeStorage } from './safeStorage'

const PREFIX = 'lingmao-export-job-v1:'
const MAX_AGE = 7 * 24 * 60 * 60 * 1000
const MAX_JOBS = 20
export interface ExportJobReceipt { id: string; owner: string; createdAt: string; savedAt: number }

/** 只存任务编号，不存筛选内容、价格、文件和凭据。 */
export function readExportJobHistory(owner: string): ExportJobReceipt[] {
  const jobs: ExportJobReceipt[] = []
  try {
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((key): key is string => !!key?.startsWith(PREFIX))
    for (const key of keys) {
      let value: Partial<ExportJobReceipt>
      try { value = JSON.parse(readStorage(key) ?? 'null') as Partial<ExportJobReceipt> } catch { continue }
      if (!value || typeof value.id !== 'string' || !/^[1-9]\d{0,18}$/.test(value.id) || key !== `${PREFIX}${value.id}` || typeof value.owner !== 'string' || typeof value.createdAt !== 'string' || typeof value.savedAt !== 'number' || !Number.isFinite(value.savedAt)) continue
      if (Date.now() - value.savedAt > MAX_AGE) { writeStorage(key, null); continue }
      if (value.owner === owner) jobs.push(value as ExportJobReceipt)
    }
  } catch { /* Private browsing/storage quota cannot prevent querying a known job. */ }
  return jobs.sort((a, b) => b.savedAt - a.savedAt).slice(0, MAX_JOBS)
}

/** 一个任务一个键，两个页签不会互相覆盖历史。权限仍由服务端判断。 */
export function rememberExportJob(job: { id: string; createdAt: string }, owner: string): boolean {
  const receipt: ExportJobReceipt = { id: job.id, createdAt: job.createdAt, owner, savedAt: Date.now() }
  const key = `${PREFIX}${job.id}`
  const serialized = JSON.stringify(receipt)
  const stored = writeStorage(key, serialized) && readStorage(key) === serialized
  if (stored) {
    const retained = readExportJobHistory(owner)
    // 这个账号最多留 20 条；每次只删一个不会变的任务编号。
    const oldest = retained.at(-1)?.savedAt
    if (retained.length === MAX_JOBS && oldest !== undefined) {
      try {
        const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index)).filter((item): item is string => !!item?.startsWith(PREFIX))
        for (const item of keys) {
          try {
            const value = JSON.parse(readStorage(item) ?? 'null') as Partial<ExportJobReceipt> | null
            if (value?.owner === owner && typeof value.savedAt === 'number' && value.savedAt < oldest) writeStorage(item, null)
          } catch { /* Ignore malformed optional history entries. */ }
        }
      } catch { /* The successful reference remains usable in this window. */ }
    }
  }
  return stored
}
