package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.fg.entity.FgDocType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 成品库存日志（docs/api/07 §3.6） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockLogQuery extends PageQuery {
    private Long warehouseId;
    private Long styleSkuId;
    private Long styleId;
    private List<FgDocType> changeType;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate bizDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate bizDateTo;
    /** 单号、款号、SKU 码 */
    private String keyword;
}
