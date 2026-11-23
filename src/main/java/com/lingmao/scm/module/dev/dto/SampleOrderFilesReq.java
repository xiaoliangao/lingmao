package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record SampleOrderFilesReq(@NotNull(message="请携带打样单版本") @Min(value=0,message="打样单版本不合法") Integer version,@NotNull(message="请携带样衣版本") @Min(value=0,message="样衣版本不合法") Integer sampleVersionLock,
    @NotNull(message="打样单附件列表不能为空") @Size(max=20,message="最多20个打样单附件") List<@NotNull(message="打样单附件不能为空") @Valid AttachmentReq> files) { }
