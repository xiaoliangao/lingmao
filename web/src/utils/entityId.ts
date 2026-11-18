/** 数据库 BIGINT 的 id 一律用十进制字符串，路由、对象键里也是。 */
export function routeId(value: unknown): string {
  if (typeof value !== 'string' || !/^[1-9]\d{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n) {
    throw new Error('单据编号无效，请重新打开列表')
  }
  return value
}
