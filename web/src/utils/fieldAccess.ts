export const FIELD_PERMISSIONS = {
  cost: 'techpack:cost:query',
  orderPrice: 'order:price:query',
  productionPrice: 'production:price:query',
  supplierContact: 'supplier:supplier:contact',
  supplierBank: 'supplier:supplier:bank',
} as const

type PermissionCheck = (permission: string) => boolean
let permissionCheck: PermissionCheck = () => false
/** 当前用户的 store 提供实时检查；没登录时的写请求不带任何受保护字段。 */
export function setFieldPermissionCheck(check: PermissionCheck) { permissionCheck = check }
export function priceReadPermission(type: 'MATERIAL' | 'PROCESS'): string {
  return type === 'PROCESS' ? FIELD_PERMISSIONS.productionPrice : FIELD_PERMISSIONS.cost
}

const COST_FIELDS = new Set(['refPrice', 'refCost', 'skuPrice', 'buyPrice', 'cost', 'estCost', 'costMultiplier', 'unitCost', 'laborPrice', 'laborPriceDefault', 'priceTaxIncluded', 'priceFreightIncluded'])
const PRICE_FIELDS = new Set(['price', 'prices', 'unitPrice', 'archivePrice', 'fromPrice', 'toPrice', 'amount', 'totalAmount', 'estAmount', 'convertedAmount', 'priceSourceId', 'priceChangeReason'])
const CONTACT_FIELDS = new Set(['contact', 'phone', 'wechat', 'address'])
const BANK_FIELDS = new Set(['bankName', 'bankAccountName', 'bankAccount'])

/**
 * 请求这边去掉字段，配合服务端的权限检查。先判断字段属于哪个实体的价格域：
 * 订单销售价、生产和加工价都和全局看成本权限无关。
 * 公开的 tagPrice、estTagPrice 和所有 id、数量、版本号都保留。
 */
export function visibleWriteBody<T>(url: string, body: T, can: PermissionCheck = permissionCheck): T {
  if (body === undefined || body === null || typeof body !== 'object') return body
  const path = url.split('?')[0] ?? ''
  let domain: string | undefined
  if (/^\/api\/(orders|order-changes)(\/|$)/.test(path)) domain = FIELD_PERMISSIONS.orderPrice
  else if (/^\/api\/production-orders(\/|$)/.test(path)) domain = FIELD_PERMISSIONS.productionPrice
  else if (/^\/api\/(materials|styles|tech-packs|purchase-orders|mrp-calcs|inventory-docs|inventory-counts)(\/|$)/.test(path)) domain = FIELD_PERMISSIONS.cost
  else if (path === '/api/prices' && 'type' in body && (body.type === 'MATERIAL' || body.type === 'PROCESS')) domain = priceReadPermission(body.type)
  const supplier = /^\/api\/suppliers(\/|$)/.test(path)
  if (!domain && !supplier) return body
  const costVisible = can(FIELD_PERMISSIONS.cost)
  const priceVisible = domain ? can(domain) : true
  const contactVisible = can(FIELD_PERMISSIONS.supplierContact)
  const bankVisible = can(FIELD_PERMISSIONS.supplierBank)
  function visit(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(visit)
    if (!value || typeof value !== 'object') return value
    const entries = Object.entries(value).filter(([key]) => {
      if (domain && !costVisible && COST_FIELDS.has(key)) return false
      if (domain && !priceVisible && PRICE_FIELDS.has(key)) return false
      if (domain === FIELD_PERMISSIONS.orderPrice && !priceVisible && key === 'feeNote') return false
      if (supplier && !contactVisible && CONTACT_FIELDS.has(key)) return false
      if (supplier && !bankVisible && BANK_FIELDS.has(key)) return false
      return true
    })
    return Object.fromEntries(entries.map(([key, field]) => [key, visit(field)]))
  }
  return visit(body) as T
}
