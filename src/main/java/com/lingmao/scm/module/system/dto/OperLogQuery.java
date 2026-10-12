package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 操作日志查询条件：操作人模糊搜，模块、成功与否精确筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogQuery extends PageQuery {
    private String username;
    private String module;
    private Boolean success;
}
