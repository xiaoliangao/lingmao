package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.CutQtyReq;
import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptForStock;
import com.lingmao.scm.module.production.dto.ReceiptReq;

import java.util.List;

/** 回货（docs/api/05 §3.6）："登记"一步 = 建 + 审核；作废一步完成。良品入成品仓由二期成品模块做（RFC 008），回货这边只记数 */
public interface ProductionReceiptService {

    List<ProductionReceiptVO> list(long prdOrderId);

    ReceiptCreated receive(long prdOrderId, ReceiptReq req);

    void voidReceipt(long prdOrderId, long receiptId, String reason);

    /** 登记裁床数（RFC 013 §4）：已下单、生产中能登记、能改；每个 SKU 不超过 ⌊件数 × 1.05⌋，也不能让已回的超出新上限 */
    void registerCut(long prdOrderId, CutQtyReq req);

    // ---------- 给成品（RFC 008 §6.2） ----------

    /**
     * 成品入库用：锁住回货单（FOR UPDATE）再取。带出、建单、改草稿、审核都调它：两张成品入库单同时入同一张回货单要排队，
     * 和作废回货单也互斥（作废先改状态锁住回货单，再查有没有成品入库单）。
     * 只查存在和品类线，不查本人范围（入库的是仓管）。不存在、不在我的线里 404；已作废 1000
     */
    ReceiptForStock forStock(long receiptId);
}
