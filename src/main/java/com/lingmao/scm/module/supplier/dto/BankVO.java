package com.lingmao.scm.module.supplier.dto;

/** 银行信息全号（GET /api/suppliers/{id}/bank，要 bank 权限，每调一次记一条操作日志） */
public record BankVO(String bankName, String bankAccount, String bankAccountName) {
}
