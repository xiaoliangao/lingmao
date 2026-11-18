package com.lingmao.scm.module.system.dto;

/** 只在服务端内部传；JSON 里只返回 accessToken。 */
public record AuthTokens(String accessToken, String refreshToken, String browserToken, long maxAgeSeconds) { }
