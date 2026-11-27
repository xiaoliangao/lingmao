import { download, http } from '@/utils/request'
import type { PageResult } from '@/types/system'
import type {
  FactoryPerfRow, LineWipRow, OrderMarginPage, OrderMarginQuery, PerfQuery, PerfResult, PurchasePerfRow, StaleMaterialQuery,
  StaleMaterialRow,
} from '@/types/report'
import type { PageParams } from './system'

/** 报表（RFC 019 §3）：后端在 board 域，现算 */
export const reportApi = {
  purchasePerf: (params: PerfQuery) => http.get<PerfResult<PurchasePerfRow>>('/api/reports/supplier-performance/materials', params),
  factoryPerf: (params: PerfQuery) => http.get<PerfResult<FactoryPerfRow>>('/api/reports/supplier-performance/factories', params),
  lineWip: (month?: string) => http.get<LineWipRow[]>('/api/reports/line-wip', { month }),
  staleMaterials: (params: PageParams & StaleMaterialQuery) => http.get<PageResult<StaleMaterialRow>>('/api/reports/stale-materials', params),
  exportStaleMaterials: (params: StaleMaterialQuery) => download('/api/reports/stale-materials/export', params, '呆滞料.xlsx'),
  staleDays: () => http.get<number>('/api/reports/stale-materials/days'),
  setStaleDays: (days: number) => http.put<void>('/api/reports/stale-materials/days', { days }),
  orderMargins: (params: PageParams & OrderMarginQuery) => http.get<OrderMarginPage>('/api/reports/order-margins', params),
}
