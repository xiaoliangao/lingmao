package com.lingmao.scm.module.system.dto;

import java.time.OffsetDateTime;

public record OutboxState(long id, String kind, String status, int attempts, boolean cancelRequested,
                          String errorCode, String requestId, OffsetDateTime createdAt) {}
