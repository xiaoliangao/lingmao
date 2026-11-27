package com.lingmao.scm.module.system.dto;

import java.time.OffsetDateTime;

/**
 * 一条钉钉消息（sys_outbox 里 kind = DINGTALK_MESSAGE 的一行）。
 *
 * @param status    PENDING 等着发、PROCESSING 正在发、RETRY_WAIT 发失败等重试、SUCCEEDED 钉钉收了、DEAD_LETTER 发不出去、CANCELLED 取消了
 * @param errorCode 最近一次失败的原因：NOT_CONFIGURED 钉钉没配置、NO_RECIPIENT 收件人找不到（没手机号、钉钉里没这个人、停用了）、
 *                  REMOTE_REJECTED 钉钉拒收（errcode 在应用日志里）、TEMPORARY_FAILURE 网络等临时问题
 */
public record NotifyMessageRow(long id, String event, String eventLabel, Long recipientId, String recipientName, String title,
                               String status, String errorCode, int attempts, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
