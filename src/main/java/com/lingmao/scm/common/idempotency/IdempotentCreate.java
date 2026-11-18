package com.lingmao.scm.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 只标 JSON 建单入口；解析文件、渲染文件、外部 HTTP 不得包进此事务。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentCreate {
    /** 服务端固定的动作名；不得取用户输入。 */
    String value();
}
