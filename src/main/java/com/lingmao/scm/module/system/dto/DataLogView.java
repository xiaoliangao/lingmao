package com.lingmao.scm.module.system.dto;

import tools.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;

/** A freshly projected audit result. Its field policy uses the persisted table/domain, not a controller's package. */
public record DataLogView(String id, String table, String rowId, String rootTable, String rootId,
                          String action, String previousRowId, ObjectNode diff, String actorUserId,
                          String clientIp, String requestId, String operation, String transactionId,
                          OffsetDateTime createdAt) { }
