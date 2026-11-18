import type { ExportJobView } from '@/types/exportJob'
import type { StockLogQuery } from '@/types/inventory'
import { routeId } from '@/utils/entityId'
import { stockLogExportFilters } from '@/utils/exportJobRecovery'
import { download, http, type RequestOptions } from '@/utils/request'

const base = '/api/export-jobs'
const jobPath = (id: string) => `${base}/${routeId(id)}`

export const exportJobApi = {
  createStockLogs: (query: StockLogQuery, options?: RequestOptions) => http.post<ExportJobView>(`${base}/stock-logs`, stockLogExportFilters(query), options),
  get: (id: string, options?: RequestOptions) => http.get<ExportJobView>(jobPath(id), undefined, options),
  cancel: (id: string, options?: RequestOptions) => http.post<ExportJobView>(`${jobPath(id)}/cancel`, {}, options),
  retry: (id: string, options?: RequestOptions) => http.post<ExportJobView>(`${jobPath(id)}/retry`, {}, options),
  // 不跳转服务端给的 downloadUrl：走固定的、要登录的同源地址。
  download: (id: string, options?: RequestOptions) => download(`${jobPath(id)}/file`, undefined, '库存日志.xlsx', options),
}
