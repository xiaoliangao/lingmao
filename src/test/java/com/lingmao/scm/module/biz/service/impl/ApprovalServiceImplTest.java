package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.ApprovalRule;
import com.lingmao.scm.module.biz.entity.ApprovalStep;
import com.lingmao.scm.module.biz.entity.ApprovalStepStatus;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.ApprovalRuleMapper;
import com.lingmao.scm.module.biz.mapper.ApprovalStepMapper;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.module.system.service.RoleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 多级审批（架构 §4.8、PRD §5.1）：哪几级、谁能审、一个人连过几级、驳回后从头审 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceImplTest {

    private static final String PO_AUDIT = "purchase:order:audit";

    @Mock
    private ApprovalRuleMapper ruleMapper;
    @Mock
    private ApprovalStepMapper stepMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RoleService roleService;
    @Mock
    private UserService userService;

    private ApprovalServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ApprovalRule.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ApprovalStep.class);
    }

    @BeforeEach
    void setUp() {
        service = new ApprovalServiceImpl(ruleMapper, stepMapper, permissionService, roleService, userService,
                Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 规则：金额超过门槛才加级（等于不加），没超过的不加；第 1 级是审核权限、CURRENT，加的级 WAITING */
    @Test
    void start_amountOverThreshold_addsLevel() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule(1L, "50000", 5L, null), rule(2L, "100000", null, 9L)));
        when(roleService.isEnabled(5L)).thenReturn(true);

        service.start(BizType.PURCHASE_ORDER, 30L, new BigDecimal("100000.00"));

        List<ApprovalStep> steps = insertedSteps();
        assertThat(steps).extracting(ApprovalStep::getLevelNo, ApprovalStep::getPerm, ApprovalStep::getRoleId,
                        ApprovalStep::getUserId, ApprovalStep::getStatus)
                .containsExactly(
                        tuple(1, PO_AUDIT, null, null, ApprovalStepStatus.CURRENT),
                        tuple(2, null, 5L, null, ApprovalStepStatus.WAITING));
        assertThat(steps).allSatisfy(s -> assertThat(s.getRound()).isEqualTo(1));
    }

    /** 规则：门槛从低到高排（规则表里谁先建的不要紧），门槛一样的先建的先审 */
    @Test
    void start_levelsOrderedByThreshold() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
                rule(3L, "100000", 6L, null), rule(1L, "20000", null, 9L), rule(2L, "100000", 5L, null)));
        when(roleService.isEnabled(any(Long.class))).thenReturn(true);
        when(userService.isEnabled(9L)).thenReturn(true);

        service.start(BizType.PURCHASE_ORDER, 30L, new BigDecimal("150000"));

        assertThat(insertedSteps()).extracting(ApprovalStep::getLevelNo, ApprovalStep::getOverAmount, ApprovalStep::getRoleId, ApprovalStep::getUserId)
                .containsExactly(
                        tuple(1, null, null, null),
                        tuple(2, new BigDecimal("20000"), null, 9L),
                        tuple(3, new BigDecimal("100000"), 5L, null),
                        tuple(4, new BigDecimal("100000"), 6L, null));
    }

    /** 规则：不是当前这一级的审批人 → 403"现在是第 2 级，等总经理审"，一行都不改（有审核权限也不行：第 1 级已经过了） */
    @Test
    void pass_notCurrentApprover_403() {
        login(7L, PO_AUDIT);
        givenSteps(step(1, PO_AUDIT, null, null, ApprovalStepStatus.PASSED),
                step(2, null, 5L, null, ApprovalStepStatus.CURRENT));
        when(permissionService.loadRoleIds(7L)).thenReturn(List.of(6L));
        when(roleService.names(anyCollection())).thenReturn(Map.of(5L, "总经理"));

        assertThatThrownBy(() -> service.pass(BizType.PURCHASE_ORDER, 30L))
                .isInstanceOf(BizException.class)
                .hasMessage("现在是第 2 级，等总经理审")
                .extracting("code").isEqualTo(403);
        verify(stepMapper, never()).update(any(Wrapper.class));
    }

    /**
     * 规则：一个人连着两级都能审（第 1 级有审核权限，第 2 级是他的角色），点一次一起过；第 3 级指定了别人，停在那里、返回 false。
     * 改了 4 行：1 级过、2 级轮到、2 级过、3 级轮到
     */
    @Test
    void pass_sameUserConsecutiveLevels_passTogether() {
        login(7L, PO_AUDIT);
        givenSteps(step(1, PO_AUDIT, null, null, ApprovalStepStatus.CURRENT),
                step(2, null, 5L, null, ApprovalStepStatus.WAITING),
                step(3, null, null, 9L, ApprovalStepStatus.WAITING));
        when(permissionService.loadRoleIds(7L)).thenReturn(List.of(5L));
        when(stepMapper.update(any(Wrapper.class))).thenReturn(1);

        boolean done = service.pass(BizType.PURCHASE_ORDER, 30L);

        assertThat(done).isFalse();
        verify(stepMapper, times(4)).update(any(Wrapper.class));
    }

    /** 规则：最后一级审过返回 true（单据接着改成已审核）；那一级被别人抢先审了（改 0 行）→ 1002 */
    @Test
    void pass_lastLevel_returnsTrue_raceLost_1002() {
        login(9L);
        givenSteps(step(1, PO_AUDIT, null, null, ApprovalStepStatus.PASSED),
                step(2, null, null, 9L, ApprovalStepStatus.CURRENT));
        when(stepMapper.update(any(Wrapper.class))).thenReturn(1);

        assertThat(service.pass(BizType.PURCHASE_ORDER, 30L)).isTrue();

        when(stepMapper.update(any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.pass(BizType.PURCHASE_ORDER, 30L))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(1002);
    }

    /** 规则：驳回时没审的级作废（CANCELLED）；重新提交是新的一轮（round + 1），从第 1 级审起 */
    @Test
    void cancelThenResubmit_newRoundFromLevel1() {
        service.cancel(BizType.PURCHASE_ORDER, 30L);

        ArgumentCaptor<LambdaUpdateWrapper<ApprovalStep>> cancel = ArgumentCaptor.captor();
        verify(stepMapper).update(cancel.capture());
        assertThat(cancel.getValue().getSqlSet()).startsWith("status=");
        assertThat(cancel.getValue().getParamNameValuePairs()).containsValue(ApprovalStepStatus.CANCELLED);

        ApprovalStep last = new ApprovalStep();
        last.setRound(2);
        when(stepMapper.selectOne(any())).thenReturn(last);
        when(ruleMapper.selectList(any())).thenReturn(List.of());

        service.start(BizType.PURCHASE_ORDER, 30L, new BigDecimal("10"));

        List<ApprovalStep> steps = insertedSteps();
        assertThat(steps).extracting(ApprovalStep::getRound, ApprovalStep::getLevelNo, ApprovalStep::getStatus)
                .containsExactly(tuple(3, 1, ApprovalStepStatus.CURRENT));
    }

    /** 规则：规则上的审批人停用了，提交时就拦下（不然那一级没人能审，单子卡在待审核） */
    @Test
    void start_approverDisabled_rejected() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(1L, "50000", 5L, null)));
        when(roleService.isEnabled(5L)).thenReturn(false);
        when(roleService.names(anyCollection())).thenReturn(Map.of(5L, "总经理"));

        assertThatThrownBy(() -> service.start(BizType.PURCHASE_ORDER, 30L, new BigDecimal("60000")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色「总经理」")
                .hasMessageContaining("停用或删除");
        verify(stepMapper, never()).insert(anyCollection());
    }

    private List<ApprovalStep> insertedSteps() {
        ArgumentCaptor<List<ApprovalStep>> captor = ArgumentCaptor.captor();
        verify(stepMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 最近一轮（round 1）的各级 */
    private void givenSteps(ApprovalStep... steps) {
        when(stepMapper.selectOne(any())).thenReturn(steps[0]);
        when(stepMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(steps)));
    }

    private static ApprovalStep step(int level, String perm, Long roleId, Long userId, ApprovalStepStatus status) {
        ApprovalStep s = new ApprovalStep();
        s.setId(100L + level);
        s.setBizType(BizType.PURCHASE_ORDER);
        s.setDocId(30L);
        s.setRound(1);
        s.setLevelNo(level);
        s.setPerm(perm);
        s.setRoleId(roleId);
        s.setUserId(userId);
        s.setStatus(status);
        return s;
    }

    private static ApprovalRule rule(long id, String over, Long roleId, Long userId) {
        ApprovalRule r = new ApprovalRule();
        r.setId(id);
        r.setBizType(BizType.PURCHASE_ORDER);
        r.setOverAmount(new BigDecimal(over));
        r.setRoleId(roleId);
        r.setUserId(userId);
        return r;
    }

    private static void login(long userId, String... perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti"), null, Arrays.stream(perms).map(SimpleGrantedAuthority::new).toList()));
    }
}
