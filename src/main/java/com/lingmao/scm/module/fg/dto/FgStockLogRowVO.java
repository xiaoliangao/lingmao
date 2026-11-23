package com.lingmao.scm.module.fg.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 库存日志一行（前端 FgStockLogRow） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockLogRowVO extends FgSkuFields {
    private Long id;
    private Long companyId;
    private Long locationId;
    private Long reversalOfLogId;
    private String requestId;
    private LocalDate bizDate;
    private FgDocType changeType;
    private Long docId;
    private FgDocKind docKind;
    private String docNo;
    private Long warehouseId;
    private String warehouseName;
    private Integer changeQty;
    private Integer balanceQty;
    @JsonIgnore
    private Long createBy;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
