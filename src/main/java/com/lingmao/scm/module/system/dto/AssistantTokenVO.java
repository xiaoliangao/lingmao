package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * 给智能助手用的短期只读令牌（RFC 017 §3.1，POST /api/auth/assistant-token）。
 * 浏览器换来以后交给助手服务，助手服务拿它替这个人调 GET 接口；登录令牌本身不交出去。
 *
 * @param token     放在 Authorization: Bearer 后面。只能调返回数据的 GET 接口，别的回 403；不能刷新，过期了再换
 * @param expiresAt 什么时候过期（30 分钟后）。换它的那次登录退出、改密码、账号停用，或者 AI 功能关掉，都会提前失效
 */
public record AssistantTokenVO(String token,
                               @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai") OffsetDateTime expiresAt) {
}
