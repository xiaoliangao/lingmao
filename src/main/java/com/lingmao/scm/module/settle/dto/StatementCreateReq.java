package com.lingmao.scm.module.settle.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 生成对账单：把这段时间里这家还没对过的单据拉进来
 *
 * @param companyId   主体
 * @param partnerId   供应商或加工厂
 * @param periodStart 期间起
 * @param periodEnd   期间止（不能早于起，最长一年）
 * @param remark      备注
 */
public record StatementCreateReq(@NotNull(message = "主体必填") Long companyId,
                                 @NotNull(message = "供应商必填") Long partnerId,
                                 @NotNull(message = "期间起必填") LocalDate periodStart,
                                 @NotNull(message = "期间止必填") LocalDate periodEnd,
                                 @Size(max = 500, message = "备注最多 500 个字") String remark) {
}
