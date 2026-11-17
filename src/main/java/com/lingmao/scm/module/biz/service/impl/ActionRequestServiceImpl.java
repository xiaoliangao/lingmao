package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.mapper.ActionRequestMapper;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/** 唯一键等待与业务在同一事务，不捕获唯一冲突后继续使用已中止的PG事务。 */
@Service
@RequiredArgsConstructor
public class ActionRequestServiceImpl implements ActionRequestService {
    private final ActionRequestMapper mapper;
    private final JsonMapper jsonMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(String operation, long targetId, UUID actionId, Object request, Class<T> resultType, Supplier<T> action) {
        Long userId = LoginUser.currentUserId();
        if (userId == null) throw new BizException(401, "请先登录");
        if (actionId == null) throw new BizException(400, "缺少 clientActionId，请刷新后重新操作");
        String hash = hash(operation, targetId, request);
        int inserted = mapper.claim(userId, operation, actionId, hash);
        ActionRequestMapper.Claim claim = mapper.lock(userId, operation, actionId);
        if (claim == null) throw new BizException(ErrorCodes.STATE_CHANGED, "操作结果暂不可用，请用原请求重试");
        if (!hash.equals(claim.requestHash())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "同一次操作的内容已改变，请先确认前次结果，再开始新的登记");
        }
        if (claim.result() != null) return jsonMapper.readValue(claim.result(), resultType);
        if (inserted == 0) throw new BizException(ErrorCodes.STATE_CHANGED, "前次操作结果尚未确认，请稍后用原请求重试");
        T result = action.get();
        if (mapper.complete(claim.id(), jsonMapper.writeValueAsString(result)) != 1) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "操作结果保存冲突，请用原请求重试");
        }
        return result;
    }

    String hash(String operation, long targetId, Object request) {
        JsonNode body = jsonMapper.valueToTree(request);
        Map<String, Object> normalized = new TreeMap<>();
        body.properties().forEach(e -> {
            if (!"clientActionId".equals(e.getKey())) normalized.put(e.getKey(), canonical(e.getValue()));
        });
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("operation", operation);
        envelope.put("targetId", targetId);
        envelope.put("body", normalized);
        String json = jsonMapper.writeValueAsString(envelope);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256", impossible);
        }
    }

    private Object canonical(JsonNode node) {
        if (node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            node.properties().forEach(e -> out.put(e.getKey(), canonical(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            var out = new ArrayList<>();
            node.forEach(n -> out.add(canonical(n)));
            return out;
        }
        if (node.isNumber()) return new BigDecimal(node.asText()).stripTrailingZeros();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }
}
