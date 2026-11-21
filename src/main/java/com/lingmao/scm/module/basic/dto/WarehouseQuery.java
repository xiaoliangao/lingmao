package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 仓库列表：关键字查编码、名称；类型、状态为空不筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WarehouseQuery extends PageQuery {
    private String keyword;
    /** 仓库类型：MATERIAL 物料仓 / PRODUCT 成品仓 / FACTORY 工厂仓；不传是全部 */
    private String type;
    private Integer status;
}
