package com.lingmao.scm.common.log;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给这次请求的操作日志补几个值，比如开关"从什么改成了什么"。贴了 @OperLog 的方法里调 put，切面写日志时把它们放进 params 的 detail。
 * 只放不敏感的短值：操作日志页所有超级管理员都看得到，库里只增不改。不在请求里（定时任务）调用时什么都不做
 */
public final class OperLogDetail {

    static final String ATTRIBUTE = OperLogDetail.class.getName();

    private OperLogDetail() {
    }

    public static void put(String key, Object value) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (detail == null) {
            detail = new LinkedHashMap<>();
            attrs.setAttribute(ATTRIBUTE, detail, RequestAttributes.SCOPE_REQUEST);
        }
        detail.put(key, value);
    }

    /** 这次请求补的值，没有是空表 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object detail = attrs == null ? null : attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return detail instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
