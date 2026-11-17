package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptReq;

import java.util.List;

/** 回货（docs/api/05 §3.6）："登记"一步 = 建 + 审核；作废一步完成。一期不记成品库存（Q01、Q44） */
public interface ProductionReceiptService {

    List<ProductionReceiptVO> list(long prdOrderId);

    ReceiptCreated receive(long prdOrderId, ReceiptReq req);

    void voidReceipt(long prdOrderId, long receiptId, String reason);
}
