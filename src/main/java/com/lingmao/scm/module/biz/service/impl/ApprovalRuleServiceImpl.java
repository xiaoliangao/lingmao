package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.biz.dto.ApprovalRuleSaveReq;
import com.lingmao.scm.module.biz.dto.ApprovalRuleVO;
import com.lingmao.scm.module.biz.entity.ApprovalRule;
import com.lingmao.scm.module.biz.mapper.ApprovalRuleMapper;
import com.lingmao.scm.module.biz.service.ApprovalRuleService;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.system.service.RoleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.system.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApprovalRuleServiceImpl implements ApprovalRuleService {

    private final ApprovalRuleMapper ruleMapper;
    private final ApprovalService approvalService;
    private final RoleService roleService;
    private final UserService userService;
    private final PermissionService permissionService;

    @Override
    public List<ApprovalRuleVO> list() {
        List<ApprovalRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<ApprovalRule>()
                .orderByAsc(ApprovalRule::getBizType, ApprovalRule::getOverAmount, ApprovalRule::getId));
        Set<Long> roleIds = rules.stream().map(ApprovalRule::getRoleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = rules.stream().map(ApprovalRule::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> roleNames = roleService.names(roleIds);
        Map<Long, String> userNames = userService.nicknames(userIds);
        return rules.stream()
                .map(r -> new ApprovalRuleVO(r.getId(), r.getBizType(), r.getOverAmount(),
                        r.getRoleId(), r.getRoleId() == null ? null : roleNames.get(r.getRoleId()),
                        r.getUserId(), r.getUserId() == null ? null : userNames.get(r.getUserId())))
                .toList();
    }

    @Override
    @Transactional
    public Long create(ApprovalRuleSaveReq req) {
        ApprovalRule rule = new ApprovalRule();
        fill(rule, req);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    @Override
    @Transactional
    public void update(long id, ApprovalRuleSaveReq req) {
        ApprovalRule rule = getEntity(id);
        fill(rule, req);
        // 整体覆盖：审批人从角色换成人，role_id 要写成 null
        ruleMapper.update(rule, Updates.replaceById(rule));
    }

    @Override
    @Transactional
    public void delete(long id) {
        getEntity(id);
        ruleMapper.deleteById(id);
    }

    private ApprovalRule getEntity(long id) {
        ApprovalRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(404, "审批规则不存在: " + id);
        }
        return rule;
    }

    /**
     * 校验并填进实体。同一种单据门槛一样的两条不拦：两个人都要审（先建的先审），是合理的配法。
     * 审批人不要求有这种单据的审核权限码：规则加的级就是"额外让谁审"，比如让财务审大额采购（架构 §4.8）
     */
    private void fill(ApprovalRule rule, ApprovalRuleSaveReq req) {
        if (!approvalService.supports(req.getBizType())) {
            throw new BizException(400, "只有需求订单、采购单、生产单能加审批级");
        }
        if ((req.getRoleId() == null) == (req.getUserId() == null)) {
            throw new BizException(400, "审批人选一个角色或一个人，只能选一样");
        }
        if (req.getRoleId() != null && !roleService.isEnabled(req.getRoleId())) {
            throw new BizException("这个角色不存在或已停用，换一个");
        }
        if (req.getUserId() != null && !userService.isEnabled(req.getUserId())) {
            throw new BizException("这个人不存在或已停用，换一个");
        }
        ApprovalAccess.check(permissionService, req.getBizType(), req.getRoleId(), req.getUserId());
        rule.setBizType(req.getBizType());
        rule.setOverAmount(req.getOverAmount());
        rule.setRoleId(req.getRoleId());
        rule.setUserId(req.getUserId());
    }
}
