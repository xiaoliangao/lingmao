package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改尺码组：只能改名称（尺码只能追加、基码不能换，RFC 003 §5.3） */
@Data
public class SizeGroupRenameReq {

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最长 50 个字")
    private String name;
}
