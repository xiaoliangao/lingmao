package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新建、修改品类。上级建好不改（不挪节点）：修改时传的上级要和原来一样 */
@Data
public class CategorySaveReq {

    /** 上级；顶层为空 */
    private Long parentId;

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最长 50 个字")
    private String name;

    /** 同级顺序，越小越靠前 */
    @NotNull(message = "排序不能为空")
    private Integer sort = 0;
}
