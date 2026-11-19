package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 保存草稿时工艺单页签的一个文件（前端 TechPackFileForm，docs/api/01 §3.5）：先传到 /api/files 拿到 FileVO，再带上分组发回来。
 * category：CRAFT_SHEET 工艺单、PATTERN 纸样和其他、BOM_SOURCE 导入的 BOM 表（TechPackFiles）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PackFileForm extends AttachmentReq {

    @NotBlank(message = "文件要说是哪一组的")
    private String category;
}
