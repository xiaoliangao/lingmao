package com.lingmao.scm.module.supplier.service;

import com.lingmao.scm.module.supplier.dto.ChangeVO;

import java.util.List;

/**
 * 关键字段改动的审批（RFC 004 §4）。改动由保存供应商时挂起（SupplierService.update），这里只管看、批、驳、撤。
 * 三个动作都是比较并交换 PENDING → APPROVED / REJECTED / WITHDRAWN，做不了返回 1002
 */
public interface SupplierChangeService {

    /** 一家的全部改动，新的在前。账号有 bank 权限才给全号 */
    List<ChangeVO> list(long supplierId);

    /** 等审批的那一份（一家最多一份），没有返回 null。详情页顶上的横条用 */
    ChangeVO pending(long supplierId);

    /**
     * 批准：不能是申请人；逐项核对 before 还等于当前值，不等 → 1002"资料已经变了，请重新提交"；写回供应商；
     * 信用代码和别家撞了 → 1000。准入审批中的供应商先撤回再批（1000）
     */
    void approve(long supplierId, long changeId);

    /** 驳回：原因必填，不能是申请人 */
    void reject(long supplierId, long changeId, String reason);

    /** 撤回：只有申请人能撤 */
    void withdraw(long supplierId, long changeId);
}
