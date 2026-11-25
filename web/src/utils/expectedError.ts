/**
 * 正常流程里会"抛出来"、但不算出错的几种值（不依赖 axios、Element Plus，node 测试能直接引）。
 * 完整的判断是 `utils/request.ts` 的 `isExpectedError`，再加上接口错误和请求取消。
 *
 * 事件处理里 `await formRef.value?.validate()`、`await ElMessageBox.confirm(...)` 没有 catch 是常见写法：
 * 校验没过、点了取消，后面的代码本来就不该跑。这些值不能让 ModuleBoundary 把整页换成"暂时无法显示"，
 * 控制台也不打红。
 */

/** 确认框点了取消、关闭（ElMessageBox 抛 'cancel' / 'close'） */
export function isDismissed(err: unknown): boolean {
  return err === 'cancel' || err === 'close'
}

/**
 * 表单校验没过：el-form 的 `validate()` 不传回调时 reject 一个 `{ 字段: [{ message, field }] }`，
 * 错误已经显示在字段下面。自定义校验器 `callback(new Error('…'))` 时数组里是 Error，也带 message
 */
export function isFormValidationError(err: unknown): boolean {
  if (err === null || typeof err !== 'object' || err instanceof Error) return false
  if (Object.getPrototypeOf(err) !== Object.prototype) return false
  const values = Object.values(err)
  return (
    values.length > 0 &&
    values.every(
      (v) =>
        Array.isArray(v) &&
        v.length > 0 &&
        v.every((e) => e !== null && typeof e === 'object' && typeof (e as { message?: unknown }).message === 'string'),
    )
  )
}
