package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
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
public class StyleQuery extends TaggedPageQuery {
    private String keyword;
    private Long categoryId;
    private String styleClass;
    private String brand;
    private Integer year;
    private String season;
    private String wave;
    private Long designerId;
    private StyleStatus status;
    private TechPackStatus techPackStatus;
    private List<Long> ids;
}
