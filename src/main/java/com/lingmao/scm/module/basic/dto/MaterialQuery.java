package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 物料列表、导出的条件（RFC 003 §8）。空的不筛。
 * keyword：有空格时当几个编码一起查（PRD §5.4），名称包含整个关键字的也算；没空格查编码、名称、货号、成分。
 * categoryId：一级分类连同它下面的二级一起查。ids：导出选中的
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaterialQuery extends TaggedPageQuery {
    private String keyword;
    private Long categoryId;
    private String materialType;
    private Integer status;
    private List<Long> ids;
}
