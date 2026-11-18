package com.lingmao.scm.common.json;

import com.lingmao.scm.common.result.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.Collection;

/**
 * HTTP 的 ID 边界：在 Jackson 仍保留精确 Long 的树上转字符串，绝不先经过 double。
 * 只影响 API 返回；数据库 JSON 快照、金额、数量、版本、分页计数保持原类型。
 */
@RestControllerAdvice
public final class IdJsonResponseAdvice implements ResponseBodyAdvice<Object> {
    private final JsonMapper mapper;

    public IdJsonResponseAdvice(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof R<?> envelope) || envelope.getData() == null) return body;
        Object value = envelope.getData();
        JsonNode data = mapper.valueToTree(value);
        // 仓库的 R<Long> / R<List<Long>> 均为创建 ID 或角色/菜单 ID；状态计数是 Map，保持数值。
        boolean rootIds = value instanceof Long || value instanceof Collection<?> list
                && !list.isEmpty() && list.stream().allMatch(v -> v == null || v instanceof Long);
        data = normalize(data, rootIds);
        return envelope.getCode() == 0 ? R.ok(data) : R.fail(envelope.getCode(), envelope.getMsg(), data);
    }

    static JsonNode normalize(JsonNode node, boolean ids) {
        if (node == null || node.isNull()) return node;
        if (ids && node.isIntegralNumber()) return StringNode.valueOf(node.asText());
        if (node instanceof ObjectNode object) {
            var fields = new ArrayList<>(object.propertyNames());
            for (String name : fields) object.set(name, normalize(object.get(name), isId(name)));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) array.set(i, normalize(array.get(i), ids));
        }
        return node;
    }

    private static boolean isId(String name) {
        return name.equals("id") || name.equals("ids") || name.endsWith("Id") || name.endsWith("Ids")
                || name.endsWith("_id") || name.endsWith("_ids") || name.endsWith("By") || name.endsWith("_by");
    }
}
