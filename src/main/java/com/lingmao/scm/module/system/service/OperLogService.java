package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.OperLogQuery;
import com.lingmao.scm.module.system.entity.SysOperLog;

/** 操作日志：切面负责写，这里只负责查 */
public interface OperLogService {

    PageResult<SysOperLog> page(OperLogQuery query);
}
