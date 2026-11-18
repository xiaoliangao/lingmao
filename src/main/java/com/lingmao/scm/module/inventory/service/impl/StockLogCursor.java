package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.idempotency.RequestFingerprint;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.security.LoginUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** 游标绑定可信用户/部署租户/规范筛选，HMAC 防篡改。授权仍由每次请求的安全链执行。 */
@Component
final class StockLogCursor {
    record Position(OffsetDateTime createdAt, long id) { }
    private record Payload(int version, String filter, String createdAt, String id) { }

    private final JsonMapper json;
    private final byte[] secret;
    private final String tenant;

    StockLogCursor(JsonMapper json, @Value("${lingmao.jwt.secret}") String secret,
                   @Value("${lingmao.tenant-id:single}") String tenant) {
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tenant = tenant;
    }

    String encode(StockLogQuery query, Position position) {
        String payload = json.writeValueAsString(new Payload(1, fingerprint(query), position.createdAt().toString(),
                Long.toString(position.id())));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }

    Position decode(StockLogQuery query) {
        String cursor = query.getCursor();
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 1024) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            Payload payload = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Payload.class);
            if (payload.version() != 1 || !fingerprint(query).equals(payload.filter())) throw new IllegalArgumentException();
            long id = Long.parseLong(payload.id());
            if (id <= 0) throw new IllegalArgumentException();
            return new Position(OffsetDateTime.parse(payload.createdAt()), id);
        } catch (RuntimeException failure) {
            throw new BizException(400, "游标无效或查询条件已变化，请从第一页重新查询");
        }
    }

    private String fingerprint(StockLogQuery query) {
        var fields = new TreeMap<String, Object>();
        fields.put("tenant", tenant);
        fields.put("user", LoginUser.currentUserId());
        fields.put("warehouseId", query.getWarehouseId());
        fields.put("materialSkuId", query.getMaterialSkuId());
        fields.put("changeType", query.getChangeType() == null ? null : query.getChangeType().stream().sorted().toList());
        fields.put("bizDateFrom", query.getBizDateFrom());
        fields.put("bizDateTo", query.getBizDateTo());
        fields.put("createdDateFrom", query.getCreatedDateFrom());
        fields.put("createdDateTo", query.getCreatedDateTo());
        fields.put("keyword", query.getKeyword() == null ? null : query.getKeyword().strip());
        fields.put("ids", query.getIds() == null ? null : query.getIds().stream().sorted().toList());
        return RequestFingerprint.of(json, fields);
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(("stock-log-v1\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("无法签名流水游标", impossible);
        }
    }
}
