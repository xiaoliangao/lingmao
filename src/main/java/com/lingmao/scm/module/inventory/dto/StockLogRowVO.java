package com.lingmao.scm.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 库存日志的一行（前端 StockLogRow） */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockLogRowVO extends MaterialSkuBrief {
    private Long id;
    private LocalDate bizDate;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private StockChangeType changeType;
    /** INV_DOC / INV_COUNT：点单号用 */
    private String docType;
    private InvDocKind docKind;
    private Long docId;
    private String docNo;
    private Long warehouseId;
    private String warehouseName;
    private BigDecimal changeQty;
    private BigDecimal balanceQty;
    private String dyeLot;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String currency;
    private String valuationStatus;
    private Long reversalOfLogId;

    /** 物料名称/颜色仍取当前值，stockUnit必须保留事件快照（历史未知NULL），不能被主档填充覆盖。 */
    @Override
    public void fillFrom(MaterialSkuBrief brief) {
        String frozenUnit = getStockUnit();
        super.fillFrom(brief);
        setStockUnit(frozenUnit);
    }
    private String operatorName;
    /** 操作人 id，填 operatorName 用，不返回 */
    @JsonIgnore
    private Long createBy;
}
