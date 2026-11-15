package com.lingmao.scm.module.price.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 作废一条价格：原因必填（PRD §6.10 规则 1） */
@Data
public class PriceVoidReq {

    @NotBlank(message = "请填写作废原因")
    @Size(max = 255, message = "作废原因最多 255 个字")
    private String reason;
}
