package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.ApprovalRuleSaveReq;
import com.lingmao.scm.module.biz.entity.ApprovalRule;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.ApprovalRuleMapper;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.system.service.RoleService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 设置 › 审批的校验（架构 §4.8）：只有三种单据、审批人二选一、要启用的 */
@ExtendWith(MockitoExtension.class)
class ApprovalRuleServiceImplTest {

    @Mock
    private ApprovalRuleMapper ruleMapper;
    @Mock
    private ApprovalService approvalService;
    @Mock
    private RoleService roleService;
    @Mock
    private UserService userService;
    @Mock private com.lingmao.scm.module.system.service.PermissionService permissionService;

    @InjectMocks
    private ApprovalRuleServiceImpl service;

    /** 规则：单级审核的单据（变更单）不能加审批级；角色、人都选或都不选 → 400，不插 */
    @Test
    void create_unsupportedTypeOrBothApprovers_400() {
        when(approvalService.supports(BizType.ORDER_CHANGE)).thenReturn(false);
        assertThatThrownBy(() -> service.create(req(BizType.ORDER_CHANGE, 5L, null)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(400);

        when(approvalService.supports(BizType.PURCHASE_ORDER)).thenReturn(true);
        assertThatThrownBy(() -> service.create(req(BizType.PURCHASE_ORDER, 5L, 9L)))
                .isInstanceOf(BizException.class).hasMessageContaining("只能选一样");
        assertThatThrownBy(() -> service.create(req(BizType.PURCHASE_ORDER, null, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("只能选一样");
        verify(ruleMapper, never()).insert(any(ApprovalRule.class));
    }

    /** 规则：选的角色停用了 → 1000，不插（不然提交时这一级没人能审） */
    @Test
    void create_disabledRole_rejected() {
        when(approvalService.supports(BizType.PURCHASE_ORDER)).thenReturn(true);
        when(roleService.isEnabled(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(req(BizType.PURCHASE_ORDER, 5L, null)))
                .isInstanceOf(BizException.class).hasMessageContaining("不存在或已停用");
        verify(ruleMapper, never()).insert(any(ApprovalRule.class));
    }

    @Test void create_userWithoutModuleListCannotBeAssigned() {
        when(approvalService.supports(BizType.PURCHASE_ORDER)).thenReturn(true);
        when(userService.isEnabled(9L)).thenReturn(true);
        when(permissionService.loadPermissions(9L)).thenReturn(java.util.Set.of("purchase:order:audit"));
        assertThatThrownBy(() -> service.create(req(BizType.PURCHASE_ORDER, null, 9L)))
                .isInstanceOf(BizException.class).hasMessageContaining("purchase:order:list");
        verify(ruleMapper, never()).insert(any(ApprovalRule.class));
    }

    @Test void create_assignedUserNeedsListButNotAuditOrAllData() {
        when(approvalService.supports(BizType.PURCHASE_ORDER)).thenReturn(true);
        when(userService.isEnabled(9L)).thenReturn(true);
        when(permissionService.loadPermissions(9L)).thenReturn(java.util.Set.of("purchase:order:list"));
        service.create(req(BizType.PURCHASE_ORDER, null, 9L));
        verify(ruleMapper).insert(any(ApprovalRule.class));
    }

    private static ApprovalRuleSaveReq req(BizType type, Long roleId, Long userId) {
        ApprovalRuleSaveReq req = new ApprovalRuleSaveReq();
        req.setBizType(type);
        req.setOverAmount(new BigDecimal("50000"));
        req.setRoleId(roleId);
        req.setUserId(userId);
        return req;
    }
}
