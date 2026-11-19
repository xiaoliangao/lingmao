package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 选物料 SKU 的弹窗（DataPick）：关键字查 SKU 码、物料编码、名称、货号、颜色名；分类含下级。
 * 物料类型只列 / 不列哪几种：物料清单只能填一块的人只看自己那块的类型（RFC 012 §3.2）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialPickQuery extends PageQuery {
    private String keyword;
    private Long categoryId;
    @Size(max = 50, message = "物料类型最多 50 个")
    private List<String> materialTypes;
    @Size(max = 50, message = "物料类型最多 50 个")
    private List<String> excludeMaterialTypes;
}
