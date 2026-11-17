package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.order.entity.ResponsibleParty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 关闭异常：处理措施必填；上报时没定的责任方这时补上 */
@Data
public class IssueCloseReq {
    @NotBlank(message = "关闭前要写处理措施")
    @Size(max = 1000, message = "处理措施最长 1000 个字")
    private String measure;
    private ResponsibleParty responsibleParty;
}
