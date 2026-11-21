package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 操作日志查询条件：操作人模糊搜，模块、成功与否精确筛 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogQuery extends PageQuery {
    /** 操作人账号，模糊查 */
    private String username;
    /** 模块，要写全，如 用户管理、AI 功能 */
    private String module;
    /** true 只看成功的，false 只看失败的；不传是全部 */
    private Boolean success;
}
