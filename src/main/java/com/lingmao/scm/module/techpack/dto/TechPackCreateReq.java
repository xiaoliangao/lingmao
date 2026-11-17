package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.PackStage;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 建一个阶段的下一版草稿（POST /api/tech-packs） */
@Data
public class TechPackCreateReq {
    @NotNull(message = "要选款式")
    private Long styleId;
    @NotNull(message = "要选研发 BOM 还是生产 BOM")
    private PackStage stage;
}
