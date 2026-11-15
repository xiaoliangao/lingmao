package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 选物料 SKU 的弹窗（DataPick）：关键字查 SKU 码、物料编码、名称、货号、颜色名；分类含下级 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialPickQuery extends PageQuery {
    private String keyword;
    private Long categoryId;
}
