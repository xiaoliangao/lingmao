import { STOCK_CHANGE_TYPE_LABEL, type StockLogQuery } from '@/types/inventory'
import { clientActionUuid } from './clientAction'
import { routeId } from './entityId'
import { validateKeywords } from './keywords'
import { cleanQuery } from './query'
import { readStorage, writeStorage } from './safeStorage'

const PREFIX = 'lingmao-export-create-v1:'
export interface ExportCreateDraft { identity: string; key: string; filters: StockLogQuery; createdAt: number }

/** 只有这些不含价格的筛选条件能进导出和本页签的恢复快照。 */
export function stockLogExportFilters(query: StockLogQuery): StockLogQuery {
  const value = JSON.parse(JSON.stringify(query)) as StockLogQuery
  const { warehouseId, materialSkuId, changeType, bizDateFrom, bizDateTo, keyword } = value
  if (warehouseId != null) routeId(warehouseId)
  if (materialSkuId != null) routeId(materialSkuId)
  if (keyword != null && typeof keyword !== 'string') throw new Error('导出关键字格式无效，请重新查询')
  validateKeywords(keyword)
  for (const day of [bizDateFrom, bizDateTo]) if (day != null && (typeof day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(day))) throw new Error('导出日期格式无效，请重新查询')
  if (changeType != null && (!Array.isArray(changeType) || changeType.length > Object.keys(STOCK_CHANGE_TYPE_LABEL).length || changeType.some((item) => !Object.hasOwn(STOCK_CHANGE_TYPE_LABEL, item)))) throw new Error('库存变动类型无效，请重新查询')
  return cleanQuery({ warehouseId, materialSkuId, changeType, bizDateFrom, bizDateTo, keyword })
}

export function newExportCreate(identity: string, filters: StockLogQuery): ExportCreateDraft {
  return { identity, key: clientActionUuid(), filters: stockLogExportFilters(filters), createdAt: Date.now() }
}
export function readExportCreate(identity: string): ExportCreateDraft | null {
  const raw = readStorage(`${PREFIX}${identity}`, true)
  if (raw === null) return null
  try {
    const value = JSON.parse(raw) as Partial<ExportCreateDraft>
    if (!value || value.identity !== identity || typeof value.key !== 'string' || !/^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i.test(value.key) || typeof value.createdAt !== 'number' || !Number.isFinite(value.createdAt) || !value.filters || typeof value.filters !== 'object') throw new Error()
    return { identity, key: value.key, createdAt: value.createdAt, filters: stockLogExportFilters(value.filters) }
  } catch { throw new Error('待确认的导出记录无法读取，请先核对前次任务，勿重复创建') }
}
/** 这份短期快照存在本页签的 sessionStorage，结果确定后删掉。 */
export function storeExportCreate(draft: ExportCreateDraft): boolean {
  const key = `${PREFIX}${draft.identity}`
  const raw = JSON.stringify(draft)
  return writeStorage(key, raw, true) && readStorage(key, true) === raw
}
export function clearExportCreate(identity: string): boolean {
  return writeStorage(`${PREFIX}${identity}`, null, true)
}
