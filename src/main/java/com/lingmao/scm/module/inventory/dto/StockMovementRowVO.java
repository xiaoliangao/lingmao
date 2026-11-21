package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 进销存汇总的一行（前端 StockMovementRow，RFC 016 §4）：一个物料 SKU 在选中的仓里，期初 + 入库 − 出库 = 期末。
 * 入库、出库按变动类型拆开（页面上默认藏着）；反审核的反向日志算进原来那一列（采购入库反审核是采购入库那列减），
 * 选中的几个仓之间的调拨同时出现在调入、调出里。不出金额（RFC 016 §4.3）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockMovementRowVO extends MaterialSkuBrief {
    private BigDecimal openingQty;
    private BigDecimal inQty;
    private BigDecimal outQty;
    private BigDecimal closingQty;

    // 入库拆开：采购入库、退料入库、其他入库、调入、盘盈、期初录入
    private BigDecimal purchaseIn;
    private BigDecimal returnIn;
    private BigDecimal otherIn;
    private BigDecimal transferIn;
    private BigDecimal countGain;
    private BigDecimal openingIn;

    // 出库拆开：领料出库、采购退货、其他出库、调出、盘亏
    private BigDecimal issueOut;
    private BigDecimal purchaseReturn;
    private BigDecimal otherOut;
    private BigDecimal transferOut;
    private BigDecimal countLoss;
}
