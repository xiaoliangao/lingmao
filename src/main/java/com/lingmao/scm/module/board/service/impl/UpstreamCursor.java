package com.lingmao.scm.module.board.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.idempotency.RequestFingerprint;
import com.lingmao.scm.module.biz.dto.PendingNoticePosition;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.board.dto.UpstreamQuery;
import com.lingmao.scm.security.DataScope;
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
import java.util.List;
import java.util.TreeMap;

/** 游标只携带位置，绑定当前用户、部署租户和授权筛选；每页仍重新执行 SQL 权限校验。 */
@Component
final class UpstreamCursor {
    private record Payload(int version, String filter, String time, String type, String id) { }
    private final JsonMapper json;
    private final byte[] secret;
    private final String tenant;

    UpstreamCursor(JsonMapper json, @Value("${lingmao.jwt.secret}") String secret,
                   @Value("${lingmao.tenant-id:single}") String tenant) {
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tenant = tenant;
    }

    String encode(UpstreamQuery query, Long owner, PendingNoticePosition position) {
        String payload = json.writeValueAsString(new Payload(1, fingerprint(query, owner), position.latest().toString(),
                position.docType().name(), Long.toString(position.docId())));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }

    PendingNoticePosition decode(UpstreamQuery query, Long owner) {
        if (query.getCursor() == null || query.getCursor().isBlank()) return null;
        try {
            if (query.getCursor().length() > 1024) throw new IllegalArgumentException();
            String[] parts = query.getCursor().split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            Payload payload = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Payload.class);
            if (payload.version() != 1 || !fingerprint(query, owner).equals(payload.filter())) throw new IllegalArgumentException();
            long id = Long.parseLong(payload.id());
            if (id <= 0) throw new IllegalArgumentException();
            return new PendingNoticePosition(OffsetDateTime.parse(payload.time()), BizType.valueOf(payload.type()), id);
        } catch (RuntimeException invalid) {
            throw new BizException(400, "游标无效或筛选、权限已变化，请从第一页重新查询");
        }
    }

    private String fingerprint(UpstreamQuery query, Long owner) {
        var fields = new TreeMap<String, Object>();
        fields.put("tenant", tenant);
        fields.put("user", LoginUser.currentUserId());
        fields.put("all", DataScope.current(false).isAll());
        fields.put("type", query.getType());
        fields.put("owner", owner);
        fields.put("size", query.getSize());
        fields.put("modules", List.of(HomeServiceImpl.PRODUCTION_LIST, HomeServiceImpl.PURCHASE_LIST, HomeServiceImpl.MRP_LIST)
                .stream().filter(LoginUser::hasAuthority).toList());
        return RequestFingerprint.of(json, fields);
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(("upstream-v1\n" + body).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("无法签名提醒游标", impossible);
        }
    }
}
