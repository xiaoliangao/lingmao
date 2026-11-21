package com.lingmao.scm.common.log;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 给这次请求的操作日志补几个值，比如开关"从什么改成了什么"。贴了 @OperLog 的方法里调 put，切面写日志时把它们放进 params 的 detail。
 * 只放不敏感的短值：操作日志页所有超级管理员都看得到，库里只增不改。不在请求里（定时任务）调用时什么都不做。
 * 每个 @OperLog 方法各记各的：切面进方法前 take 走外层的，写完日志再 restore 回去；方法抛了异常不写 detail（没改成）
 */
public final class OperLogDetail {

    static final String ATTRIBUTE = OperLogDetail.class.getName();

    private OperLogDetail() {
    }

    public static void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
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

    /** 切面用：拿走现在记着的（进一个 @OperLog 方法前拿走外层的，方法结束后拿走它自己的），没有是空表 */
    public static Map<String, Object> take() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return Map.of();
        }
        Map<String, Object> taken = current();
        attrs.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return taken;
    }

    /** 切面用：方法结束后把 take 走的外层的放回去 */
    public static void restore(Map<String, Object> outer) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        if (outer.isEmpty()) {
            attrs.removeAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        } else {
            attrs.setAttribute(ATTRIBUTE, new LinkedHashMap<>(outer), RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** 这次请求补的值，没有是空表 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object detail = attrs == null ? null : attrs.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return detail instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
