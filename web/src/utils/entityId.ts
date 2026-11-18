/** Database BIGINT identifiers stay decimal strings, including in URLs and object keys. */
export function routeId(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9]\d{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n) {
    throw new Error('单据编号无效，请重新打开列表')
  }
  return value
}
