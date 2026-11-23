package com.lingmao.scm.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 入库单、出库单列表的一行（前端 InvDocRow）。物料只带第一行的，"等 n 种"用 itemCount */
@Data
public class InvDocRowVO {
    private Long id;
    @JsonIgnore
    private Long companyId;
    private String no;
    private InvDocKind kind;
    private InvDocType docType;
    private String status;
    private Integer version;
    private Long fromWarehouseId;
    private String fromWarehouseName;
    private Long toWarehouseId;
    private String toWarehouseName;
    private Long partnerId;
    private String partnerName;
    private String sourceNo;
    private MaterialSkuBrief firstItem;
    /** 第一行的 SKU（查询时取，填 firstItem 用），不返回给前端 */
    @JsonIgnore
    private Long firstSkuId;
    private int itemCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal totalQty;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal totalAmount;
    private LocalDate docDate;
    private String reason;
    /** 制单人 id：取 createByName 用，不返回 */
    @JsonIgnore
    private Long createBy;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private List<String> actions = List.of();
}
