package com.lingmao.scm.module.purchase.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.purchase.dto.FollowReq;
import com.lingmao.scm.module.purchase.dto.FromMrpReq;
import com.lingmao.scm.module.purchase.dto.FromReorderReq;
import com.lingmao.scm.module.purchase.dto.OrderCutRisk;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseCreateReq;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseDefaultsVO;
import com.lingmao.scm.module.purchase.dto.PurchaseDetailVO;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseSaveReq;

import java.util.List;
import java.util.Map;

/** 采购单（docs/api/03 §3.3）、待到料（§3.4） */
public interface PurchaseOrderService {

    PageResult<PurchaseRowVO> page(PurchaseQuery query);

    PurchaseDetailVO detail(long id);

    /** 不在数据范围里 404（单据公共接口用） */
    void checkVisible(long id);

    /** 原成功操作的来源对象仍须在当前可见范围内；不重验可生成状态。 */
    void checkSourceMrpVisible(long id);

    PurchaseDefaultsVO defaults(PurchaseDefaultsQuery query);

    DocSaved create(PurchaseCreateReq req);

    /** 保存草稿，返回新的 version */
    int update(long id, PurchaseSaveReq req);

    void delete(long id);

    /** 状态动作：submit、withdraw、reject、audit、unaudit、close、reopen、void */
    void act(long id, String action, DocActionReq req);

    List<DocSaved> fromMrp(FromMrpReq req);

    List<DocSaved> fromReorder(FromReorderReq req);

    void follow(long id, long itemId, FollowReq req);

    byte[] export(PurchaseQuery query);

    PageResult<PendingArrivalRowVO> pendingItems(PendingArrivalQuery query);

    PendingArrivalCounts pendingCounts(PendingArrivalQuery query);

    /**
     * 料晚于开裁的需求订单（首页跟单的"晚于开裁"，docs/api/06 §2.3）：待到料里 CUT 的行，订单号 → 这张订单晚得最多的一行。
     * 跟单看的是自己的生产单，那些采购行的采购员是别人，所以不按本人筛、只按品类线；只给物料名和日期，
     * 调用方再用自己看得见的生产单去对
     */
    Map<String, OrderCutRisk> orderCutRisks();
}
