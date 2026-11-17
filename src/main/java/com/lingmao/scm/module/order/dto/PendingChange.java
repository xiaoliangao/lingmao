package com.lingmao.scm.module.order.dto;

/** 订单上没审完的变更单（草稿或待审核）：详情顶上的黄条 */
public record PendingChange(long id, String no, String status, String summary) {
}
