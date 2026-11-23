package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.dev.entity.MakeWay;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record SampleDispatchReq(@NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="请选择打样方式") MakeWay makeWay,@Positive(message="打样工厂编号不合法") Long factoryId,@Positive(message="打样员编号不合法") Long makerId,
    @NotNull(message="请选择要样日期") LocalDate needDate) { }
