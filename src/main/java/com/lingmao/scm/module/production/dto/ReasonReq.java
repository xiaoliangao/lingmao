package com.lingmao.scm.module.production.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 作废回货单：原因必填 */
@Data
public class ReasonReq {
    @NotBlank(message = "请写作废原因")
    @Size(max = 255, message = "原因最长 255 个字")
    private String reason;
}
