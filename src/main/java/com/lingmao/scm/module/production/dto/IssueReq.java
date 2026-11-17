package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 上报异常（电脑、手机共用，前端 IssueForm）。描述、照片必填（PRD §6.7.4） */
@Data
public class IssueReq {
    @NotBlank(message = "请选异常类型")
    private String issueType;
    @NotBlank(message = "请写描述")
    @Size(max = 1000, message = "描述最长 1000 个字")
    private String description;
    @NotEmpty(message = "至少传一张照片：问题部位、到货短缺或现场")
    @Size(max = 9, message = "照片最多 9 张")
    @Valid
    private List<AttachmentReq> photos;
    private boolean impactDelivery;
    private LocalDate newExpectDate;
    /** null = 待定 */
    private ResponsibleParty responsibleParty;
}
