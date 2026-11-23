package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record SampleImagesReq(@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer version,@NotNull(message="样衣图片列表不能为空") @Size(max=20,message="最多20张样衣图片") List<@NotNull(message="样衣图片不能为空") @Valid AttachmentReq> files) { }
