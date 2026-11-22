package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.ApprovalLevel;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.entity.ApprovalRule;
import com.lingmao.scm.module.biz.entity.ApprovalStep;
import com.lingmao.scm.module.biz.entity.ApprovalStepStatus;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.mapper.ApprovalRuleMapper;
import com.lingmao.scm.module.biz.mapper.ApprovalStepMapper;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.module.system.service.RoleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    /** 第 1 级：这种单据的审核权限码，和单级审核时一样，审的时候现查（收回权限马上生效） */
    static final Map<BizType, String> FIRST_LEVEL_PERM = Map.of(
            BizType.ORDER, "order:order:audit",
            BizType.PURCHASE_ORDER, "purchase:order:audit",
            BizType.PRODUCTION_ORDER, "production:order:audit");

    static final String FIRST_LEVEL_LABEL = "有审核权限的人";

    private final ApprovalRuleMapper ruleMapper;
    private final ApprovalStepMapper stepMapper;
    private final PermissionService permissionService;
    private final RoleService roleService;
    private final UserService userService;
    private final Clock clock;

    @Override
    public boolean supports(BizType type) {
        return FIRST_LEVEL_PERM.containsKey(type);
    }

    @Override
    @Transactional
    public void start(BizType type, long docId, BigDecimal amount) {
        String perm = firstLevelPerm(type);
        cancel(type, docId);
        int round = latestRound(type, docId) + 1;
        List<ApprovalStep> steps = new ArrayList<>();
        steps.add(step(type, docId, round, 1, ApprovalStepStatus.CURRENT));
        steps.getFirst().setPerm(perm);
        for (ApprovalRule rule : matchedRules(type, amount)) {
            ensureUsable(rule);
            ApprovalAccess.check(permissionService, type, rule.getRoleId(), rule.getUserId());
            ApprovalStep step = step(type, docId, round, steps.size() + 1, ApprovalStepStatus.WAITING);
            step.setRoleId(rule.getRoleId());
            step.setUserId(rule.getUserId());
            step.setOverAmount(rule.getOverAmount());
            steps.add(step);
        }
        stepMapper.insert(steps);
    }

    @Override
    @Transactional
    public boolean pass(BizType type, long docId) {
        firstLevelPerm(type);
        Long me = LoginUser.currentUserId();
        Supplier<Set<Long>> myRoles = myRoles(me);
        List<ApprovalStep> steps = latestSteps(type, docId);
        ApprovalStep current = steps.stream().filter(s -> s.getStatus() == ApprovalStepStatus.CURRENT).findFirst()
                // 没有在审的级：被撤回、驳回了，或者最后一级刚被别人审完
                .orElseThrow(() -> new BizException(ErrorCodes.STATE_CHANGED, "状态已变化，请刷新后再试"));
        if (!canPass(current, me, myRoles)) {
            throw new BizException(403, "现在是第 " + current.getLevelNo() + " 级，等" + approverLabels(List.of(current)).get(current.getLevelNo()) + "审");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        while (true) {
            casStatus(current.getId(), ApprovalStepStatus.CURRENT, ApprovalStepStatus.PASSED, me, now);
            ApprovalStep next = levelAfter(steps, current);
            if (next == null) {
                return true;
            }
            casStatus(next.getId(), ApprovalStepStatus.WAITING, ApprovalStepStatus.CURRENT, null, null);
            // 下一级还是我能审的（比如第 1 级有审核权限、第 2 级又是我这个角色），一起过，不用再点一次
            if (!canPass(next, me, myRoles)) {
                return false;
            }
            current = next;
        }
    }

    @Override
    @Transactional
    public void cancel(BizType type, long docId) {
        stepMapper.update(new LambdaUpdateWrapper<ApprovalStep>()
                .set(ApprovalStep::getStatus, ApprovalStepStatus.CANCELLED)
                .eq(ApprovalStep::getBizType, type)
                .eq(ApprovalStep::getDocId, docId)
                .in(ApprovalStep::getStatus, ApprovalStepStatus.WAITING, ApprovalStepStatus.CURRENT));
    }

    @Override
    public ApprovalView view(BizType type, long docId) {
        List<ApprovalStep> steps = latestSteps(type, docId);
        if (steps.isEmpty()) {
            return null;
        }
        Map<Integer, String> labels = approverLabels(steps);
        Set<Long> passers = new HashSet<>();
        steps.stream().map(ApprovalStep::getPassBy).filter(Objects::nonNull).forEach(passers::add);
        Map<Long, String> names = userService.nicknames(passers);
        List<ApprovalLevel> levels = steps.stream()
                .map(s -> new ApprovalLevel(s.getLevelNo(), labels.get(s.getLevelNo()), s.getOverAmount(), s.getStatus(),
                        s.getPassBy() == null ? null : names.get(s.getPassBy()), s.getPassTime()))
                .toList();
        ApprovalStep current = steps.stream().filter(s -> s.getStatus() == ApprovalStepStatus.CURRENT).findFirst().orElse(null);
        if (current == null) {
            return new ApprovalView(steps.getFirst().getRound(), levels, null, false);
        }
        Long me = LoginUser.currentUserId();
        return new ApprovalView(current.getRound(), levels,
                "第 " + current.getLevelNo() + " 级 · " + labels.get(current.getLevelNo()),
                canPass(current, me, myRoles(me)));
    }

    @Override
    public com.lingmao.scm.module.biz.dto.ApprovalScope waitingScope(BizType type) {
        String permission = firstLevelPerm(type);
        Long user = LoginUser.currentUserId();
        return new com.lingmao.scm.module.biz.dto.ApprovalScope(user,
                user == null ? List.of() : permissionService.loadRoleIds(user), LoginUser.hasAuthority(permission), permission);
    }

    @Override
    public List<Long> waitingForMe(BizType type, java.util.Collection<Long> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        var scope = waitingScope(type);
        if (scope.userId() == null) return List.of();
        List<Long> ids = candidates.stream().filter(Objects::nonNull).distinct().toList();
        List<Long> out = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 500) {
            var page = ids.subList(from, Math.min(from + 500, ids.size()));
            out.addAll(stepMapper.selectList(new LambdaQueryWrapper<ApprovalStep>()
                    .select(ApprovalStep::getDocId).eq(ApprovalStep::getBizType, type)
                    .eq(ApprovalStep::getStatus, ApprovalStepStatus.CURRENT).in(ApprovalStep::getDocId, page)
                    .and(w -> w.eq(ApprovalStep::getUserId, scope.userId())
                            .or(scope.firstLevel(), x -> x.eq(ApprovalStep::getPerm, scope.permission()))
                            .or(!scope.roleIds().isEmpty(), x -> x.in(ApprovalStep::getRoleId, scope.roleIds())))
                    .orderByAsc(ApprovalStep::getDocId)).stream().map(ApprovalStep::getDocId).toList());
        }
        return out;
    }

    /** 不是这三种单据的（变更单、入库单……还是单级审核）调进来是程序的错 */
    private static String firstLevelPerm(BizType type) {
        String perm = FIRST_LEVEL_PERM.get(type);
        if (perm == null) {
            throw new IllegalArgumentException(type + " 没有多级审批，只有 " + FIRST_LEVEL_PERM.keySet());
        }
        return perm;
    }

    /**
     * 金额超过门槛（不含等于）的规则，门槛从低到高、同样的门槛先建的先审。规则一种单据就几条，取全了在这里筛、排，
     * 排序规则不靠 SQL，单测能钉住。金额是 null（单价都没填）当 0
     */
    private List<ApprovalRule> matchedRules(BizType type, BigDecimal amount) {
        BigDecimal total = amount == null ? BigDecimal.ZERO : amount;
        return ruleMapper.selectList(new LambdaQueryWrapper<ApprovalRule>().eq(ApprovalRule::getBizType, type)).stream()
                .filter(r -> total.compareTo(r.getOverAmount()) > 0)
                .sorted(Comparator.comparing(ApprovalRule::getOverAmount).thenComparing(ApprovalRule::getId))
                .toList();
    }

    /** 规则上的审批人停用、删除了，这一级就没人能审，单子会卡在待审核：提交时就拦下，告诉他找谁改 */
    private void ensureUsable(ApprovalRule rule) {
        boolean usable = rule.getRoleId() != null ? roleService.isEnabled(rule.getRoleId()) : userService.isEnabled(rule.getUserId());
        if (!usable) {
            String who = rule.getRoleId() != null
                    ? "角色「" + roleService.names(List.of(rule.getRoleId())).getOrDefault(rule.getRoleId(), "已删除") + "」"
                    : "「" + userService.nicknames(List.of(rule.getUserId())).getOrDefault(rule.getUserId(), "已删除") + "」";
            throw new BizException("金额超过 " + rule.getOverAmount().toPlainString() + " 要" + who
                    + "再审一级，但这个审批人已经停用或删除了，提交不了：请超级管理员到设置 › 审批改一下");
        }
    }

    private static ApprovalStep step(BizType type, long docId, int round, int levelNo, ApprovalStepStatus status) {
        ApprovalStep step = new ApprovalStep();
        step.setBizType(type);
        step.setDocId(docId);
        step.setRound(round);
        step.setLevelNo(levelNo);
        step.setStatus(status);
        return step;
    }

    private int latestRound(BizType type, long docId) {
        ApprovalStep last = stepMapper.selectOne(new LambdaQueryWrapper<ApprovalStep>()
                .select(ApprovalStep::getRound)
                .eq(ApprovalStep::getBizType, type)
                .eq(ApprovalStep::getDocId, docId)
                .orderByDesc(ApprovalStep::getRound)
                .last("LIMIT 1"));
        return last == null ? 0 : last.getRound();
    }

    /** 最近一轮的各级，按级排；没提交过是空的 */
    private List<ApprovalStep> latestSteps(BizType type, long docId) {
        int round = latestRound(type, docId);
        if (round == 0) {
            return List.of();
        }
        return stepMapper.selectList(new LambdaQueryWrapper<ApprovalStep>()
                .eq(ApprovalStep::getBizType, type)
                .eq(ApprovalStep::getDocId, docId)
                .eq(ApprovalStep::getRound, round)
                .orderByAsc(ApprovalStep::getLevelNo));
    }

    private static ApprovalStep levelAfter(List<ApprovalStep> steps, ApprovalStep step) {
        return steps.stream().filter(s -> s.getLevelNo() == step.getLevelNo() + 1).findFirst().orElse(null);
    }

    /**
     * 比较并交换：还是 from 才改成 to。0 行说明这期间被别人改了——同一级两个人同时点，后一个等前一个提交后再比，
     * 已经不是 CURRENT 了；撤回和审核撞上也一样 → 1002，整个审核回滚
     */
    private void casStatus(long stepId, ApprovalStepStatus from, ApprovalStepStatus to, Long passBy, OffsetDateTime passTime) {
        LambdaUpdateWrapper<ApprovalStep> cas = new LambdaUpdateWrapper<ApprovalStep>()
                .set(ApprovalStep::getStatus, to)
                .eq(ApprovalStep::getId, stepId)
                .eq(ApprovalStep::getStatus, from);
        if (to == ApprovalStepStatus.PASSED) {
            cas.set(ApprovalStep::getPassBy, passBy).set(ApprovalStep::getPassTime, passTime);
        }
        if (stepMapper.update(cas) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "状态已变化，请刷新后再试");
        }
    }

    /** 我的角色用到再查（第 1 级只看权限，不用查角色），一次请求里只查一遍 */
    private Supplier<Set<Long>> myRoles(Long me) {
        return new Supplier<>() {
            private Set<Long> roles;

            @Override
            public Set<Long> get() {
                if (roles == null) {
                    roles = me == null ? Set.of() : new HashSet<>(permissionService.loadRoleIds(me));
                }
                return roles;
            }
        };
    }

    /** 第 1 级看审核权限；规则加的级看指定的人是不是我、审批角色在不在我的启用角色里 */
    private static boolean canPass(ApprovalStep step, Long me, Supplier<Set<Long>> myRoles) {
        if (me == null) {
            return false;
        }
        if (step.getPerm() != null) {
            return LoginUser.hasAuthority(step.getPerm());
        }
        if (step.getUserId() != null) {
            return step.getUserId().equals(me);
        }
        return step.getRoleId() != null && myRoles.get().contains(step.getRoleId());
    }

    /** 每一级"谁审"的说法，第几级 → 文字（同一轮里级数不重复）：第 1 级"有审核权限的人"，规则加的是角色名或昵称（删了的也照样显示原来的名字） */
    private Map<Integer, String> approverLabels(List<ApprovalStep> steps) {
        Set<Long> roleIds = new HashSet<>();
        Set<Long> userIds = new HashSet<>();
        for (ApprovalStep s : steps) {
            if (s.getRoleId() != null) {
                roleIds.add(s.getRoleId());
            }
            if (s.getUserId() != null) {
                userIds.add(s.getUserId());
            }
        }
        Map<Long, String> roleNames = roleService.names(roleIds);
        Map<Long, String> userNames = userService.nicknames(userIds);
        Map<Integer, String> labels = new HashMap<>();
        for (ApprovalStep s : steps) {
            String label;
            if (s.getPerm() != null) {
                label = FIRST_LEVEL_LABEL;
            } else if (s.getRoleId() != null) {
                label = roleNames.getOrDefault(s.getRoleId(), "角色 " + s.getRoleId());
            } else {
                label = userNames.getOrDefault(s.getUserId(), "用户 " + s.getUserId());
            }
            labels.put(s.getLevelNo(), label);
        }
        return labels;
    }
}
