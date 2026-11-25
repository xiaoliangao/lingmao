package com.lingmao.scm.module.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建、修改部门（RFC 018 §1.5） */
@Data
public class DeptSaveReq {

    /** 上级部门 id；顶层为空。修改时可以换上级，不能换到自己或自己的下级下面 */
    private Long parentId;

    /** 部门名称，同一个上级下不重名 */
    @NotBlank(message = "部门名称不能为空")
    @Size(max = 32, message = "部门名称最长 32 个字")
    private String name;

    /** 同级顺序，越小越靠前 */
    @NotNull(message = "排序不能为空")
    private Integer sort = 0;
}
