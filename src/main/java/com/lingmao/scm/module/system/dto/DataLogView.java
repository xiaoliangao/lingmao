package com.lingmao.scm.module.system.dto;

import tools.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;

/** 新投影出来的审计结果。字段权限按库里存的表和价格域判断，不按 controller 所在的包。 */
public record DataLogView(String id, String table, String rowId, String rootTable, String rootId,
                          String action, String previousRowId, ObjectNode diff, String actorUserId,
                          String clientIp, String requestId, String operation, String transactionId,
                          OffsetDateTime createdAt) { }
