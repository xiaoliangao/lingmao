package com.lingmao.scm.security;

import com.lingmao.scm.common.result.R;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 在 ID 转字符串之前执行，这时还拿得到真实的 DTO 类型。 */
@RestControllerAdvice
@Order(-200)
public class FieldProjectionAdvice implements ResponseBodyAdvice<Object> {
    private final FieldProjection projection;
    public FieldProjectionAdvice(tools.jackson.databind.json.JsonMapper json) { this.projection = new FieldProjection(json); }
    @Override public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter type, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof R<?> envelope) || envelope.getData() == null) return body;
        Object value = envelope.getData();
        // Long、List<Long> 这类 id 留给后面的 ID 响应处理。
        if (value instanceof Number || value instanceof String || value instanceof Boolean
                || value instanceof java.util.Collection<?> c && c.stream().allMatch(v -> v == null || v instanceof Long)) return body;
        var data = projection.project(value, type.getDeclaringClass().getName());
        return envelope.getCode() == 0 ? R.ok(data) : R.fail(envelope.getCode(), envelope.getMsg(), data);
    }
}
