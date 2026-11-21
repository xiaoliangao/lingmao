package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 款式列表、导出的条件（RFC 005 §7）。空的不筛。
 * keyword：有空格时当几个款号一起查（PRD §5.4），名称、花名包含整个关键字的也算；没空格查款号、名称、花名、品名。
 * categoryId：连同下级一起查。ids：导出选中的
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StyleQuery extends PageQuery {
    private String keyword;
    private Long categoryId;
    /** 大类（字典 style_class 的值） */
    private String styleClass;
    /** 品类线（几条线的人才有这个筛选，RFC 013 §2） */
    private String productLine;
    private String brand;
    private Integer year;
    private String season;
    private String wave;
    private Long designerId;
    private StyleStatus status;
    /** 资料包状态，取值见枚举 */
    private TechPackStatus techPackStatus;
    private List<Long> ids;
}
