package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleType;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
/** version is order CAS; sampleVersionLock is parent CAS, never the business sampleVersion. */
public record SampleOrderReq(
    @NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="请选择打样类型") SampleType sampleType,
    @NotNull(message="请填写打样数量") @Min(value=1,message="打样数量至少为1") @Max(value=99999999,message="打样数量不能超过99999999") Integer qty,
    @NotNull(message="请选择打样颜色") @Size(min=1,max=100,message="请选择1–100个打样颜色") List<@NotNull(message="打样颜色编号不能为空") @Positive(message="打样颜色编号不合法") Long> colorIds,
    @NotNull(message="请选择打样尺码") @Size(min=1,max=100,message="请选择1–100个打样尺码") List<@NotBlank(message="打样尺码不能为空") @Size(max=16,message="打样尺码最长16个字") String> sizeCodes,
    @NotNull(message="请选择要样日期") LocalDate needDate,
    @Size(max=500,message="改版原因最长500个字") String reason,@Size(max=1000,message="打样要求最长1000个字") String requirement,
    MakeWay makeWay,@Positive(message="打样工厂编号不合法") Long factoryId,@Positive(message="打样员编号不合法") Long makerId) { }
