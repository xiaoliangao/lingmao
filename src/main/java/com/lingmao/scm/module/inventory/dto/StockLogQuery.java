package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 库存日志（docs/api/04 §3.6.4） */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockLogQuery extends PageQuery {
    private Long warehouseId;
    /** 点库存一行看它的流水：带上仓库 + SKU */
    private Long materialSkuId;
    private List<StockChangeType> changeType;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate bizDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate bizDateTo;
    /** 单号、SKU 码、物料名、缸号 */
    private String keyword;
    private List<Long> ids;
    /** 流水游标接口；不透明、有签名，不能跨筛选或用户复用。 */
    private String cursor;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate createdDateTo;
}
