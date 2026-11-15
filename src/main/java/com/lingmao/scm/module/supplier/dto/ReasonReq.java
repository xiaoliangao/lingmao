package com.lingmao.scm.module.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 驳回、拉黑、解除黑名单、驳回改动：原因必填，写进操作记录的备注 */
@Data
public class ReasonReq {

    @NotBlank(message = "请填写原因")
    @Size(max = 500, message = "原因最多 500 个字")
    private String reason;
}
