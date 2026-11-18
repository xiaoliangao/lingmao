package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 建下一版草稿（POST /api/tech-packs）：资料包只有生产 BOM 一种（PRD §6.4.1、Q24） */
@Data
public class TechPackCreateReq {
    @NotNull(message = "要选款式")
    private Long styleId;
}
