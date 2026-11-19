package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.CutQtyReq;
import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptReq;

import java.util.List;

/** 回货（docs/api/05 §3.6）："登记"一步 = 建 + 审核；作废一步完成。一期不记成品库存（Q01、Q44） */
public interface ProductionReceiptService {

    List<ProductionReceiptVO> list(long prdOrderId);

    ReceiptCreated receive(long prdOrderId, ReceiptReq req);

    void voidReceipt(long prdOrderId, long receiptId, String reason);

    /** 登记裁床数（RFC 013 §4）：已下单、生产中能登记、能改；每个 SKU 不超过 ⌊件数 × 1.05⌋，也不能让已回的超出新上限 */
    void registerCut(long prdOrderId, CutQtyReq req);
}
