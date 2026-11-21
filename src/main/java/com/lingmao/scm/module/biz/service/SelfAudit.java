package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.security.LoginUser;

/**
 * 自己建的单不能自己审（甲方 11-21 答 Q10，PRD §3.1）：所有要审核的单据都一样，多级审批的每一级也一样，"自己"看制单人（create_by）。
 * 驳回也不给制单人：自己的单要退回用撤回（{@link #checkReject}）。反审核不拦：反审核以后还得别人再审一遍。
 * 供应商准入本来就不能自己批（§6.8.2），不走这里。
 * <ul>
 *   <li>需求订单、采购单、生产单走多级审批：ApprovalService 按 {@link DocCreatorProvider} 查制单人，审核时拦、详情的 canPass、
 *       "待我审核"都把自己建的去掉，单据的 Service 不用管（驳回也看 canPass，所以制单人驳回也被拦）</li>
 *   <li>变更单、物料出入库单、盘点单、成品出入库单是单级的：审核时调 {@link #check}，驳回时调 {@link #checkReject}，按钮用 {@link DocActions#createdBy}；
 *       建单时顺手过账（post = true）的也走审核，所以一个人建完马上过账也过不去</li>
 * </ul>
 */
public final class SelfAudit {

    public static final String MESSAGE = "自己建的单不能自己审，请其他有审核权限的人审";
    public static final String REJECT_MESSAGE = "自己建的单不能自己驳回，要退回请用撤回";

    private SelfAudit() {
    }

    /** 制单人是不是当前登录的人；没登录、没有制单人（老数据、系统建的）都算不是 */
    public static boolean isMine(Long createBy) {
        Long me = LoginUser.currentUserId();
        return me != null && me.equals(createBy);
    }

    /** 审核前调：是自己建的 → 1000 */
    public static void check(Long createBy) {
        if (isMine(createBy)) {
            throw new BizException(MESSAGE);
        }
    }

    /** 单级单据驳回前调：是自己建的 → 1000（多级审批的驳回看 canPass，已经拦了） */
    public static void checkReject(Long createBy) {
        if (isMine(createBy)) {
            throw new BizException(REJECT_MESSAGE);
        }
    }
}
