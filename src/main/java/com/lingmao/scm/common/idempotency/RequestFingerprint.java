package com.lingmao.scm.common.idempotency;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;

/** 排序对象键、归一精确小数；数组顺序具有业务意义，保持不变。 */
public final class RequestFingerprint {
    private RequestFingerprint() { }

    public static String of(JsonMapper mapper, Object value) {
        return digest(mapper.writeValueAsString(canonical(mapper.valueToTree(value))));
    }

    public static String digest(String value) {
        return HexFormat.of().formatHex(bytes(value));
    }

    public static long lockKey(String scope) {
        return ByteBuffer.wrap(bytes(scope)).getLong();
    }

    private static byte[] bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            node.properties().forEach(e -> fields.put(e.getKey(), canonical(e.getValue())));
            return fields;
        }
        if (node.isArray()) {
            var values = new ArrayList<>();
            node.forEach(v -> values.add(canonical(v)));
            return values;
        }
        if (node.isNumber()) return new BigDecimal(node.asText()).stripTrailingZeros();
        return node.isBoolean() ? node.booleanValue() : node.asText();
    }
}
