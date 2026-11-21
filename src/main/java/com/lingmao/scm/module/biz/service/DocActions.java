package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.security.LoginUser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 详情接口的 actions（架构 §4.2"按钮由后端算"）= 状态允许的（转换表）∩ 有权限的 ∩ 业务条件。前端照着出按钮，不自己判断；
 * 点了以后后端照样再校验一遍，这里只决定显示。
 * <pre>
 * vo.setActions(DocActions.of(FLOW, po.getStatus(), "purchase:order:")
 *         .when("unaudit", referenceService.referrers(BizType.PURCHASE_ORDER, id).isEmpty())   // 业务条件：有下游就不出反审核
 *         .approval(vo.getApproval())             // 多级审批的单据：audit、reject 只给当前这一级的审批人
 *         .list());
 * </pre>
 * 权限码 = 前缀 + 动作对应的最后一段（架构 §8）：submit、withdraw → submit；audit、reject、unaudit → audit；close、reopen → close；
 * void → void。不在这张表里的动作（start、finish 这种系统做的）不出按钮；某个动作要用别的权限码，用 perm(动作, 权限码) 指定，
 * 这样声明过的动作也会出。顺序按转换表里写的顺序。
 * <p>
 * 不是线程安全的：每次请求 new 一个，用完就扔
 */
public final class DocActions {

    /** 动作 → 权限码的最后一段 */
    private static final Map<String, String> PERM_OF = Map.of(
            "submit", "submit", "withdraw", "submit",
            "audit", "audit", "reject", "audit", "unaudit", "audit",
            "close", "close", "reopen", "close",
            "void", "void");

    private static final Set<String> APPROVAL_ACTIONS = Set.of("audit", "reject");

    /** 审核类动作。只靠"审核不受数据范围限制"看到的单（DataScope.orAuditing）只能做这几个，后端 act 也照这个拦 */
    public static final Set<String> AUDIT_ACTIONS = Set.of("audit", "reject", "unaudit");

    private final List<String> candidates;
    private final String permPrefix;
    private final Map<String, String> customPerms = new HashMap<>();
    private final Set<String> blocked = new HashSet<>();
    private boolean byApproval;
    private ApprovalView approval;

    private DocActions(List<String> candidates, String permPrefix) {
        this.candidates = candidates;
        this.permPrefix = permPrefix;
    }

    /** 从转换表起算：status 下能做的动作；permPrefix 是这种单据权限码的前两段带冒号，如 "purchase:order:" */
    public static <S extends Enum<S>> DocActions of(StatusFlow<S> flow, S status, String permPrefix) {
        return new DocActions(flow.actions(status), permPrefix);
    }

    /** 这个动作看指定的权限码（不按前缀拼），比如物料出入库单的 audit 看 inventory:doc:audit 以外的码 */
    public DocActions perm(String action, String authority) {
        customPerms.put(action, authority);
        return this;
    }

    /** 业务条件：ok 是 false 就不出这个按钮（有下游不出反审核、没收完不出结案……） */
    public DocActions when(String action, boolean ok) {
        if (!ok) {
            blocked.add(action);
        }
        return this;
    }

    /**
     * 单级审核的单据传制单人：是我建的就不出 audit、reject（{@link SelfAudit}，自己的单要撤回用 withdraw）。
     * 多级审批的单据不用调，view.canPass 已经把制单人去掉了
     */
    public DocActions createdBy(Long createBy) {
        if (SelfAudit.isMine(createBy)) {
            blocked.add("audit");
            blocked.add("reject");
        }
        return this;
    }

    /**
     * only 是 true 时只出审核、驳回、反审核（{@link #AUDIT_ACTIONS}）：只靠"审核不受数据范围限制"看到的单（不在本人范围里），
     * 还有"待我审核"视图里的行。改、删、作废、提交这些不给
     */
    public DocActions auditOnly(boolean only) {
        if (only) {
            candidates.stream().filter(a -> !AUDIT_ACTIONS.contains(a)).forEach(blocked::add);
        }
        return this;
    }

    /**
     * 多级审批的单据（需求订单、采购单、生产单）：audit、reject 不看权限码，只给当前这一级的审批人（view.canPass，架构 §4.8）。
     * view 是 null（还没提交过）时这两个都不出
     */
    public DocActions approval(ApprovalView view) {
        this.byApproval = true;
        this.approval = view;
        return this;
    }

    /** 算出来的按钮，按转换表的顺序 */
    public List<String> list() {
        return candidates.stream().filter(this::shown).toList();
    }

    private boolean shown(String action) {
        if (blocked.contains(action)) {
            return false;
        }
        if (byApproval && APPROVAL_ACTIONS.contains(action)) {
            return approval != null && approval.canPass();
        }
        String perm = customPerms.get(action);
        if (perm == null && PERM_OF.containsKey(action)) {
            perm = permPrefix + PERM_OF.get(action);
        }
        return perm != null && LoginUser.hasAuthority(perm);
    }
}
