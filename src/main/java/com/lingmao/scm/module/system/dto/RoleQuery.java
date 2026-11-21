package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 角色列表的查询条件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQuery extends PageQuery {
    /** 角色编码，模糊查 */
    private String code;
    /** 角色名称，模糊查 */
    private String name;
}
