package com.lingmao.scm.module.dev.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true)
public class SampleUpdateReq extends SampleFields {
    @NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") private Integer version;
}
