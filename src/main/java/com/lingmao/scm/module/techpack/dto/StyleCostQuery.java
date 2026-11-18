package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 按款看成本的筛选（前端 StyleCostQuery） */
@Data
@EqualsAndHashCode(callSuper = true)
public class StyleCostQuery extends PageQuery {
    /** 款号、名称、花名；有空格 = 按几个款号查 */
    private String keyword;
    /** 品类，含下级 */
    private Long categoryId;
    private String brand;
    private Integer year;
    private String season;
}
