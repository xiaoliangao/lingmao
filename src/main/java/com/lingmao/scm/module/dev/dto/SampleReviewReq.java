package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public record SampleReviewReq(@NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="请选择评审结论") SampleReviewResult result,@Size(max=1000,message="评审意见最长1000个字") String opinion,
    @NotNull(message="请选择评审日期") LocalDate reviewDate,
    @NotNull(message="请选择评审人") @Size(min=1,max=100,message="请选择1–100个评审人") List<@NotNull(message="评审人编号不能为空") @Positive(message="评审人编号不合法") Long> reviewerIds,
    @NotNull(message="评审照片列表不能为空") @Size(max=9,message="最多9张评审照片") List<@NotNull(message="评审照片不能为空") @Valid AttachmentReq> photos) { }
