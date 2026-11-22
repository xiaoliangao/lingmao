package com.lingmao.scm.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 助手令牌不能调这个接口，即使它是返回数据的 GET（AssistantScopeInterceptor 回 403）。
 * 用在：读整份文件内容的（工艺单 Excel 预览，要占解析 Excel 的并发名额）、看了要留痕的敏感数据（供应商银行账号）。
 * 返回文件的接口不用标，拦截器按返回类型就拦了
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoAssistant {
}
