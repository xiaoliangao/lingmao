package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 成品单据详情（前端 FgDocDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocDetailVO extends DocHeaderVO {
    private FgDocKind kind;
    private FgDocType docType;
    private Long warehouseId;
    private String warehouseName;
    private Receipt receipt;
    private Order order;
    private LocalDate docDate;
    private String reason;
    private String shipTo;
    private String logisticsNo;
    private Integer totalQty;
    private BigDecimal totalAmount;
    /** 过过账：草稿时 actions 给 void 不给 remove */
    private boolean posted;
    private List<FgStyleOptionVO> styles;
    private List<FgDocItemVO> items;

    public record Receipt(Long id, String no, Long prdOrderId, String prdOrderNo, String prdOrderKind, String factoryName,
                          LocalDate receiptDate) {
    }

    public record Order(Long id, String no, String deliveryReq) {
    }
}
