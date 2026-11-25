package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户列表的查询条件。空字段表示不筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    /** 用户名（登录账号），模糊查 */
    private String username;

    /** 关键字：登录名、昵称、手机号里含它的 */
    private String keyword;

    /** 1 启用 0 停用 */
    private Integer status;

    /** 部门 id：这个部门和它所有下级部门的人（RFC 018 §1.3） */
    private Long deptId;

    /** 角色 id：分了这个角色的人 */
    private Long roleId;
}
