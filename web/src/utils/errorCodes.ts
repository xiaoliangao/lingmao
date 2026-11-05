/**
 * 业务错误码，和后端 common.exception.ErrorCodes 一一对应。页面里按码判断时用这里的名字，不写数字。
 * 0、400、401、403、404、405、500、503 是通用的，意思和同号的 HTTP 状态码一样
 */
export const ErrorCode = {
  /** 一般业务错误：msg 就是给用户看的提示 */
  BIZ: 1000,
  /** 库存不足。data：缺料清单 */
  STOCK_SHORTAGE: 1001,
  /** 状态已经变了，或者单据被别人改过 */
  STATE_CHANGED: 1002,
  /** 导入校验没通过，整批没导。data：[{row, column, message}]，row 是 Excel 里看到的行号 */
  IMPORT_INVALID: 1003,
  /** 超出上限（超排、超收、超回货）。data：哪几行、上限多少 */
  OVER_LIMIT: 1004,
  /** 有下游单据，不能反审核、取消或删除。data：下游单据列表 */
  HAS_DOWNSTREAM: 1005,
} as const

/** 这几个码的 data 是一张清单，弹表格显示（BizErrorDialog），不弹一行消息 */
export const LIST_ERROR_CODES: readonly number[] = [
  ErrorCode.STOCK_SHORTAGE,
  ErrorCode.IMPORT_INVALID,
  ErrorCode.OVER_LIMIT,
  ErrorCode.HAS_DOWNSTREAM,
]
