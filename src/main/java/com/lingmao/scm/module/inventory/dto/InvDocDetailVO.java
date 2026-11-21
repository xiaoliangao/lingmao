package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 入库单、出库单详情（前端 InvDocDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocDetailVO extends DocHeaderVO {
    private InvDocKind kind;
    private InvDocType docType;
    private DocRef source;
    private Long fromWarehouseId;
    private String fromWarehouseName;
    private String fromWarehouseType;
    private Long toWarehouseId;
    private String toWarehouseName;
    private String toWarehouseType;
    private Long partnerId;
    private String partnerName;
    private LocalDate docDate;
    private String reason;
    private String supplierDeliveryNo;
    private String logisticsNo;
    private BigDecimal totalQty;
    private BigDecimal totalAmount;
    /** 过过账（审核过又反审核回来的草稿）：不能删，只能作废；过过账的行不能删、不能换物料 */
    private boolean posted;
    /** 附件能不能改：在本人数据范围里、有 :edit 权限（只靠"审核不受数据范围限制"看到的单不能改），不看状态 */
    private boolean filesEditable;
    private List<InvDocItemVO> items;
}
