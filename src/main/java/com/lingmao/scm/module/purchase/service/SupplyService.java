package com.lingmao.scm.module.purchase.service;

import com.lingmao.scm.module.purchase.dto.ReorderRowVO;
import com.lingmao.scm.module.purchase.dto.SkuSupplyVO;

import java.util.Collection;
import java.util.List;

/** 补货提醒（PRD §6.6.7）、库存页的在途（docs/api/03 §3.6）：只读，按 SupplyBook 的口径算 */
public interface SupplyService {

    /** 低于最小库存的 SKU，差得多的在前 */
    List<ReorderRowVO> reorderItems();

    /** 几个 SKU 的在途（按交货仓库）、已被占用、是否低于最小库存，每个 SKU 一条 */
    List<SkuSupplyVO> materialSupply(Collection<Long> skuIds);
}
