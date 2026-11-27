package com.lingmao.scm.module.settle.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 登记、改一条扣款
 *
 * @param version   改的时候带打开时拿到的版本号；新建不用
 * @param companyId 主体
 * @param partnerId 扣谁的：供应商或加工厂
 * @param bizDate   日期，按它算进哪个月的对账单；不能晚于今天
 * @param amount    金额，大于 0
 * @param reason    原因：延期几天、什么质量问题
 * @param refNo     相关单号（选填，写字就行）
 */
public record DeductionReq(Integer version,
                           @NotNull(message = "主体必填") Long companyId,
                           @NotNull(message = "供应商必填") Long partnerId,
                           @NotNull(message = "日期必填") LocalDate bizDate,
                           @NotNull(message = "金额必填") @DecimalMin(value = "0.01", message = "金额要大于 0")
                           @Digits(integer = 12, fraction = 2, message = "金额最多两位小数") BigDecimal amount,
                           @NotBlank(message = "原因必填") @Size(max = 500, message = "原因最多 500 个字") String reason,
                           @Size(max = 64, message = "相关单号最多 64 个字") String refNo) {
}
