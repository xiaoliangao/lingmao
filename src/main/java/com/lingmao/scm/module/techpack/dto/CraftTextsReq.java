package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.CraftSection;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/** 工艺指示各段的默认文字、默认包装方式（PUT /api/craft-standards/texts），整份覆盖 */
@Data
public class CraftTextsReq {
    @NotNull(message = "texts 不能为空")
    private Map<CraftSection, String> texts;
    private String packingMode;
}
