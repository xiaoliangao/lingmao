package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 颜色列表：关键字查编码、名称；状态、色系为空不筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ColorQuery extends PageQuery {
    private String keyword;
    /** 1 启用 0 停用；不传是全部 */
    private Integer status;
    /** 色系（字典 color_family 的值） */
    private String family;
}
