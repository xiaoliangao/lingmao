package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 保存时一行工序。工序名称必填在 Service 里按行号报 */
@Data
public class ProcessForm {
    private Long id;
    @Size(max = 64, message = "工序类别最长 64 个字")
    private String category;
    @Size(max = 100, message = "工序名称最长 100 个字")
    private String name;
    @Size(max = 1000, message = "工艺要求最长 1000 个字")
    private String requirement;
    private Integer stdSeconds;
    private BigDecimal price;
    @Size(max = 255, message = "工序的备注最长 255 个字")
    private String remark;
    @Valid
    private List<AttachmentReq> images;
}
