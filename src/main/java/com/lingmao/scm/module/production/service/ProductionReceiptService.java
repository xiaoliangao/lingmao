package com.lingmao.scm.module.production.service;

import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptReq;

import java.util.List;

/** 回货登记、逆向与成品入库来源；登记本身不重复记成品库存。 */
public interface ProductionReceiptService {

    /** 成品来源白名单：在现有事务中先锁回货行，再核对真实公司与普通 DataScope。 */
    com.lingmao.scm.module.production.dto.ReceiptForStock forStock(long receiptId, long companyId);

    /** 最多 100 个来源，SQL 授权后汇总良品；任一不存在/不可见整批 404。 */
    java.util.Map<Long, Long> stockSourceQuantities(java.util.Collection<Long> receiptIds, long companyId);


    List<ProductionReceiptVO> list(long prdOrderId);

    ReceiptCreated receive(long prdOrderId, ReceiptReq req);

    void voidReceipt(long prdOrderId, long receiptId, String reason);
}
