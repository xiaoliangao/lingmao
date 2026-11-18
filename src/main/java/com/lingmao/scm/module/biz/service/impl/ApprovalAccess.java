package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.system.service.PermissionService;

import java.util.Map;
import java.util.Set;

/** 当前路由依赖模块 list 权限；规则不能把审批交给没有入口的人。 */
final class ApprovalAccess {
    private static final Map<BizType, String> LIST = Map.of(
            BizType.ORDER, "order:order:list", BizType.PURCHASE_ORDER, "purchase:order:list",
            BizType.PRODUCTION_ORDER, "production:order:list");
    private ApprovalAccess() { }

    static void check(PermissionService permissions, BizType type, Long roleId, Long userId) {
        String list = LIST.get(type);
        boolean allowed;
        if (roleId != null) {
            allowed = permissions.roleCanApprove(roleId, list);
        } else {
            Set<String> granted = permissions.loadPermissions(userId);
            allowed = granted != null && granted.contains(list);
        }
        if (!allowed) throw new BizException("指定审批人缺少模块查看入口（" + list
                + "），或角色没有启用成员。请管理员补齐入口权限或调整审批规则后再提交；无需授予全部数据权限");
    }
}
