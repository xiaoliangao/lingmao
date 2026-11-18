package com.lingmao.scm.module.inventory.dto;

import java.time.OffsetDateTime;

public record ExportJobView(Long id, String kind, String status, long rows, int attempts, String requestId,
                            OffsetDateTime createdAt, OffsetDateTime expiresAt, String errorCode, String downloadUrl) {}
