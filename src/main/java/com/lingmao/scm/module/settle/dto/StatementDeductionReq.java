package com.lingmao.scm.module.settle.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 在草稿上加一笔扣款：登记一条扣款（主体、供应商取对账单的），同时加进这张对账单
 *
 * @param version 打开时拿到的版本号
 * @param bizDate 扣款日期，要在对账期间里；不传是期间止
 * @param amount  金额，大于 0
 * @param reason  原因
 * @param refNo   相关单号（选填）
 */
public record StatementDeductionReq(@NotNull(message = "缺少 version，请刷新后再试") Integer version,
                                    LocalDate bizDate,
                                    @NotNull(message = "金额必填") @DecimalMin(value = "0.01", message = "金额要大于 0")
                                    @Digits(integer = 12, fraction = 2, message = "金额最多两位小数") BigDecimal amount,
                                    @NotBlank(message = "原因必填") @Size(max = 500, message = "原因最多 500 个字") String reason,
                                    @Size(max = 64, message = "相关单号最多 64 个字") String refNo) {
}
