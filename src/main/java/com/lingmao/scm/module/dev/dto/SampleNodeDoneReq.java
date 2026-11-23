package com.lingmao.scm.module.dev.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record SampleNodeDoneReq(@NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="请选择完成日期") LocalDate doneDate) { }
