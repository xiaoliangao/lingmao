package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.purchase.dto.OrderKittingVO;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 订单进度总览的一行（RFC 016 §1.3，前端 OrderProgressRow）：一张在手订单里的一个款 */
@Data
public class OrderProgressRowVO {

    private Long orderId;
    private String orderNo;
    private String orderType;
    private String brand;
    private String status;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private AttachmentVO image;
    private int qty;
    private int scheduledQty;
    private int returnedQty;
    /** 承诺交期 ?? 要求交期；行交期更早的用行交期 */
    private LocalDate dueDate;
    /** 交期 − 今天，负数是逾期几天 */
    private Integer daysLeft;
    /** 订单的齐料（03）；还没算过料是 null */
    private OrderKittingVO kitting;
    private Long merchId;
    private String merchName;
    private OrderProgressRiskVO risk;
    /** 这个款的生产单（没作废的），排单早的在前 */
    private List<ProductionBriefVO> productions = List.of();

    /** SQL 算好的风险，Service 装进 risk */
    @JsonIgnore
    private boolean riskLate;
    @JsonIgnore
    private boolean riskNear;
    @JsonIgnore
    private boolean riskNodeLate;
    @JsonIgnore
    private boolean riskIssue;
    @JsonIgnore
    private boolean riskProduction;
}
