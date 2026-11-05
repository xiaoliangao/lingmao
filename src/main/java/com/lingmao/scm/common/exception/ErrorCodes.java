package com.lingmao.scm.common.exception;

/**
 * 业务错误码（1xxx）。前端按 code 决定怎么提示：1001、1003、1004、1005 在 R 的 data 里带一张清单，弹表格；其他的弹消息。
 * 0、400、401、403、404、405、500 是通用的，意思和同号的 HTTP 状态码一样，见 R 的注释。
 * 为什么集中写在这里：前端要跟着认的码只在一处，不在代码里散写数字。
 */
public final class ErrorCodes {

    /** 一般业务错误：编码重复、状态不允许……msg 就是给用户看的提示 */
    public static final int BIZ_ERROR = 1000;

    /** 库存不足。data：缺料清单（哪一行、库存多少、差多少） */
    public static final int STOCK_SHORTAGE = 1001;

    /** 状态已经变了，或者单据被别人改过：页面上的按钮是按打开时的状态显示的，刷新再试 */
    public static final int STATE_CHANGED = 1002;

    /** 导入校验没通过，整批没导。data：[{row, column, message}]，row 是 Excel 里看到的行号，表头是第 1 行 */
    public static final int IMPORT_INVALID = 1003;

    /** 超出上限（超排、超收、超回货）。data：哪几行、上限多少 */
    public static final int OVER_LIMIT = 1004;

    /** 有下游单据，不能反审核、取消或删除。data：下游单据列表 */
    public static final int HAS_DOWNSTREAM = 1005;

    private ErrorCodes() {
    }
}
