package com.lingmao.scm.module.biz.dto;
import java.util.List;
/** 由会话与授权服务构造的可信审批条件；不得信任请求绑定值。 */
public record ApprovalScope(Long userId, List<Long> roleIds, boolean firstLevel, String permission) { }
