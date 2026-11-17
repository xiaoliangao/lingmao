package com.lingmao.scm.module.production.dto;

/** 登记成功：status 是登记完以后生产单的状态，DONE 时页面说"外发加工单完成" */
public record ReceiptCreated(Long id, String no, String status) {
}
