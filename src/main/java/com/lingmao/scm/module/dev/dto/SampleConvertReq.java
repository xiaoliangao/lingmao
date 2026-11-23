package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.style.dto.StyleCreateReq.CodeMode;
import jakarta.validation.constraints.*;
public record SampleConvertReq(@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer version,@NotNull(message="请选择款号生成方式") CodeMode codeMode,
    @Size(max=32,message="款号最长32个字") String code,@NotBlank(message="请选择款式分类") @Size(max=64,message="款式分类最长64个字") String styleClass,
    @NotBlank(message="请填写款式名称") @Size(max=100,message="款式名称最长100个字") String name,@Size(max=100,message="款式别名最长100个字") String aliasName,@Positive(message="默认工厂编号不合法") Long defaultFactoryId) { }
