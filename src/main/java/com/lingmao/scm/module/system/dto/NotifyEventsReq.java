package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 改推哪几种消息：勾上的种类，空列表 = 都不推 */
public record NotifyEventsReq(@NotNull(message = "缺少要推的消息种类") List<NotifyEvent> events) {
}
