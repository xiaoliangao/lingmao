package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 列表查询条件：继承分页参数，再加本模块自己的筛选字段。空字段表示不筛。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeQuery extends PageQuery {
    private String code;
    private String name;
}
