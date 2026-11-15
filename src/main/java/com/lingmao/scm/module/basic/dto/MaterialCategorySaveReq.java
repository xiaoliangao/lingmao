package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建、修改物料分类。一级只有名称（code 不收）；二级必须有 2 位字母编码，就是物料编码的前缀。
 * 上级和编码建好不改：修改时传的要和原来一样
 */
@Data
public class MaterialCategorySaveReq {

    /** 上级；一级为空 */
    private Long parentId;

    @Pattern(regexp = "^\\s*$|^\\s*[A-Za-z]{2}\\s*$", message = "分类编码是 2 位字母")
    private String code;

    @NotBlank(message = "名称不能为空")
    @Size(max = 50, message = "名称最长 50 个字")
    private String name;

    @NotNull(message = "排序不能为空")
    private Integer sort = 0;
}
