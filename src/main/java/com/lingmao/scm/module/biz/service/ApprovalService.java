package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.entity.BizType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多级审批（架构 §4.8、PRD §5.1）：需求订单、采购单、生产单（外发加工单、成品采购单共用 PRODUCTION_ORDER）按金额加级。
 * 第 1 级是有这种单据审核权限的人（审的时候现查），金额超过规则门槛的再加一级，审批人是规则上的角色或人（提交时抄下来）。
 * 别的单据还是单级，不走这里。
 * <p>
 * 单据只管自己的状态，审批流的状态全在这里。各单据的提交、审核、驳回、撤回、作废照这个样子调（都在单据动作的事务里）：
 * <pre>
 * submit:  CAS DRAFT → PENDING；approvalService.start(BizType.PURCHASE_ORDER, id, po.getTotalAmount());
 * audit:   checkPrices(po);                                   // 审核的检查每一级都跑
 *          if (!approvalService.pass(BizType.PURCHASE_ORDER, id)) {   // 还有下一级：单据还是待审核
 *              bumpVersion(id, req.getVersion());              // 中间级也让版本 +1，开着旧页面的人点不成
 *              docLogService.add(BizType.PURCHASE_ORDER, id, "audit", "这一级审过了，等下一级审");
 *              return;
 *          }
 *          CAS PENDING → APPROVED，过账、回写……             // 最后一级：往下和单级时一样
 * reject / withdraw / void:  CAS 回草稿或作废；approvalService.cancel(BizType.PURCHASE_ORDER, id);
 * 详情:    vo.setApproval(approvalService.view(BizType.PURCHASE_ORDER, id));
 * </pre>
 * 这三种单据的 audit、reject 接口 @PreAuthorize 只要求登录（规则加的审批人不一定有审核权限码），谁能审由 pass 判断；
 * reject 前先问 view(...).canPass()，不是当前级的人 → 403。
 */
public interface ApprovalService {

    /** 有多级审批的单据类型：ORDER、PURCHASE_ORDER、PRODUCTION_ORDER */
    boolean supports(BizType type);

    /**
     * 提交时调：按金额和现行规则生成新的一轮（round + 1），第 1 级 CURRENT，其余 WAITING。
     * 金额超过门槛（不含等于）才加那一级，门槛从低到高排。上一轮还有没审完的级（没调 cancel）先作废。
     * 规则上的审批角色、人已经停用或删除了 → 1000，提交不了（不然这一级没人能审，单子卡住）
     */
    void start(BizType type, long docId, BigDecimal amount);

    /**
     * 审核时调：当前级不是我能审的 → 403"现在是第 N 级，等××审"；没在审（被撤回、别人刚审过）→ 1002。
     * 当前级改成 PASSED（比较并交换，同一级两个人同时点只有一个成功），下一级还是我能审的就一起过。
     * 全部过完返回 true，单据接着改成已审核；还有下一级返回 false
     */
    boolean pass(BizType type, long docId);

    /** 驳回、撤回、作废时调：这一轮没审的级（WAITING、CURRENT）改成 CANCELLED。没有在审的级不报错 */
    void cancel(BizType type, long docId);

    /** 详情的"审批"块：最近一轮，每一级谁审、门槛、谁什么时候审的、现在等谁、我能不能审。没提交过返回 null */
    ApprovalView view(BizType type, long docId);

    /** SQL EXISTS 使用的可信参数；与普通数据范围无关，不授予 data:all。 */
    com.lingmao.scm.module.biz.dto.ApprovalScope waitingScope(BizType type);

    /** 仅查已授权当前页的动作状态；内部按 500 个候选 ID 分批。 */
    List<Long> waitingForMe(BizType type, java.util.Collection<Long> candidates);
}
