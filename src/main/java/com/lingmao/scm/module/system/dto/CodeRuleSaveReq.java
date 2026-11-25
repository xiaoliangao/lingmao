package com.lingmao.scm.module.system.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 新建、修改编码规则（RFC 018 §4.6）。修改时 target 要和原来一样 */
@Data
public class CodeRuleSaveReq {

    /** 哪种编码：STYLE 款号 / MATERIAL 物料编码 / SUPPLIER 供应商编码 */
    @NotNull(message = "编码种类不能为空")
    private CodeTarget target;

    /** 规则名，如"鞋子" */
    @NotBlank(message = "规则名不能为空")
    @Size(max = 32, message = "规则名最长 32 个字")
    private String name;

    /** 按什么分：款号填大类（字典 style_class 的值），物料编码填物料类型（material_type 的值）；通用规则是空的 */
    @Size(max = 50, message = "一条规则最多管 50 个")
    private List<String> scopeValues;

    /** 段，按顺序；必须有且只有一个流水 */
    @NotEmpty(message = "至少要有一段")
    @Size(max = 12, message = "最多 12 段")
    private List<@Valid CodeSegment> segments;
}
