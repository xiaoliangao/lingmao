package com.lingmao.scm.common.result;

import lombok.Getter;

/**
 * 统一返回体：所有接口都返回这个形状 {code, msg, data}。
 * 为什么：前端只需要写一处判断 code == 0；错误信息有固定位置；分页、单条、空返回长得一样。
 * code 约定：0 成功；400 参数错误；401 未登录；403 无权限；404 找不到；500 服务器错误；1xxx 业务错误。
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
        return new R<>(code, msg, null);
    }
}
