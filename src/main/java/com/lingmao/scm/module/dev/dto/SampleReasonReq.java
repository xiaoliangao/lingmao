package com.lingmao.scm.module.dev.dto;
import jakarta.validation.constraints.*;
public record SampleReasonReq(@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer version,
    @NotBlank(message="请填写原因") @Size(max=500,message="原因最长500个字") String reason) { }
