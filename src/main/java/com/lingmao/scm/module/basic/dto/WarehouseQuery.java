package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 仓库列表：关键字查编码、名称；类型、状态为空不筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WarehouseQuery extends PageQuery {
    private String keyword;
    private String type;
    private Integer status;
}
