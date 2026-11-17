package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 保存时工艺指示的一段：文字 + 整组替换的插图 */
@Data
public class CraftSectionForm {
    @NotNull(message = "工艺指示的段代码不能为空")
    private CraftSection section;
    private String content;
    @Valid
    private List<AttachmentReq> images;
}
