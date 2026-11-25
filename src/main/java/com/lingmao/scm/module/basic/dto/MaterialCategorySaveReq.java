package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建、修改物料分类。编码是 2 位字母或数字：二级必填，一级选填（编码规则里用了"一级分类编码"才要），
 * 同一个上级下不重复（RFC 018 §8.3）。上级建好不改；编码能改，只影响以后新编的物料编码
 */
@Data
public class MaterialCategorySaveReq {

    /** 上级；一级为空 */
    private Long parentId;

    /** 分类编码：2 位字母或数字，二级必填、一级选填；编码规则里的一级、二级分类编码取它 */
    @Pattern(regexp = "^\\s*$|^\\s*[A-Za-z0-9]{2}\\s*$", message = "分类编码是 2 位字母或数字")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最长 50 个字")
    private String name;

    @NotNull(message = "排序不能为空")
    private Integer sort = 0;
}
