package com.lingmao.scm.module.board.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 设呆滞料的默认天数（甲方 11-27 答 Q67：多久没动算呆滞"待定"，所以做成能改的） */
public record StaleDaysReq(@NotNull(message = "请填天数") @Min(value = 1, message = "天数至少 1") @Max(value = 3650, message = "天数最多 3650") Integer days) {
}
