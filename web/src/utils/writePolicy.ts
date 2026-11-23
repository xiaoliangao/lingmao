/** 服务端支持幂等的 POST 接口，和后端的 @IdempotentCreate 对齐。光有 UUID 请求头不够。 */
const CREATED_RESOURCES = new Set([
  '/api/orders', '/api/order-changes', '/api/mrp-calcs', '/api/purchase-orders',
  '/api/purchase-orders/from-mrp', '/api/purchase-orders/from-reorder',
  '/api/inventory-docs', '/api/inventory-counts', '/api/production-orders/schedule',
  '/api/materials', '/api/styles', '/api/suppliers', '/api/tech-packs',
  '/api/export-jobs/stock-logs', '/api/fg-docs',
])
const IDEMPOTENT_CHILDREN = [
  /^\/api\/fg-docs\/from-receipt\/\d+$/,
  /^\/api\/materials\/\d+\/skus$/,
  /^\/api\/styles\/\d+\/skus\/generate$/,
  /^\/api\/suppliers\/\d+\/certs$/,
  /^\/api\/tech-packs\/\d+\/(revise|to-prod)$/,
  /^\/api\/production-orders\/\d+\/nodes\/\d+\/reports$/,
  /^\/api\/production-orders\/\d+\/issues$/,
  /^\/api\/inventory-docs\/from-(purchase|production)\/\d+$/,
  /^\/api\/production-orders\/\d+\/receipts$/,
  /^\/api\/export-jobs\/\d+\/(cancel|retry)$/,
]
export function canReplayWrite(method: string, url: string): boolean {
  if (method.toLowerCase() !== 'post') return false
  const path = url.split('?')[0] ?? ''
  return CREATED_RESOURCES.has(path) || IDEMPOTENT_CHILDREN.some((pattern) => pattern.test(path))
}
