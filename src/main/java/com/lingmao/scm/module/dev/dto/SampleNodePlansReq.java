package com.lingmao.scm.module.dev.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public record SampleNodePlansReq(@NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="节点计划列表不能为空") @Size(min=1,max=100,message="请选择1–100个节点计划") List<@NotNull(message="节点计划不能为空") @Valid Node> nodes) {
    public record Node(@NotNull(message="请携带节点编号") @Positive(message="节点编号不合法") Long id,@NotNull(message="请选择节点计划日期") LocalDate planDate,@Positive(message="节点负责人编号不合法") Long ownerId,@Size(max=255,message="节点备注最长255个字") String remark) { }
}
