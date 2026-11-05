package com.lingmao.scm.common.result;

import lombok.Getter;

/**
 * 统一返回体：所有接口都返回这个形状 {code, msg, data}。
 * 为什么：前端只需要写一处判断 code == 0；错误信息有固定位置；分页、单条、空返回长得一样。
 * code 约定：0 成功；400 参数错误；401 未登录；403 无权限；404 找不到；405 请求方法不对；500 服务器错误；
 * 1xxx 业务错误（common.exception.ErrorCodes）。HTTP 状态码一律 200，前端只看 code。
 */
@Getter
public class R<T> {
    private final int code;
    private final String msg;
    private final T data;

    private R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return fail(code, msg, null);
    }

    /** 出错时还要给前端一份数据：导入的错误清单、缺料清单……哪个码带什么见 ErrorCodes */
    public static <T> R<T> fail(int code, String msg, T data) {
        return new R<>(code, msg, data);
    }
}
