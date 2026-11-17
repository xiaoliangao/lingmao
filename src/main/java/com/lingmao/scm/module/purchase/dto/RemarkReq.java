package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 改备注（算料单没关闭时随时能改） */
@Data
public class RemarkReq {
    @NotNull(message = "缺少版本号，请刷新")
    private Integer version;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
}
