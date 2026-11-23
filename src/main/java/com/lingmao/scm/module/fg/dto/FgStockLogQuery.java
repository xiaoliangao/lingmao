package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.fg.entity.FgDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 成品库存日志（docs/api/07 §3.6） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockLogQuery extends PageQuery {
    @NotNull(message = "请选主体")
    private Long companyId;
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
    /** 带签名的游标，绑定当前主体、筛选、用户与权限。 */
    private String cursor;
}
