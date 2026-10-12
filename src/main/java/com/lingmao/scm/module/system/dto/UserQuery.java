package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户列表的查询条件：用户名模糊搜，状态精确筛。空字段表示不筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    private String username;

    /** 1 启用 0 停用 */
    private Integer status;
}
