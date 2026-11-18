package com.lingmao.scm.module.system.dto;

/** Internal transport: only accessToken is returned in JSON. */
public record AuthTokens(String accessToken, String refreshToken, String browserToken, long maxAgeSeconds) { }
