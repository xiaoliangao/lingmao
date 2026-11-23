package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.PackStage;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 建一个阶段的下一版草稿（POST /api/tech-packs） */
@Data
public class TechPackCreateReq {
    @jakarta.validation.constraints.Positive(message="编号须为正整数")
    private Long styleId;
    @jakarta.validation.constraints.Positive(message="编号须为正整数")
    private Long sampleId;
    @jakarta.validation.constraints.Positive(message="编号须为正整数")
    private Long companyId;
    @NotNull(message = "要选研发 BOM 还是生产 BOM")
    private PackStage stage;
}
