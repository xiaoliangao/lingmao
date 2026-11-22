package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** GET /api/production-orders/{id}（前端 ProductionDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProductionDetailVO extends DocHeaderVO {
    private Integer planRevision;
    private String kind;
    /** 品类线，字典 product_line */
    private String productLine;
    private Long factoryId;
    private String factoryCode;
    private String factoryName;
    private String factoryShortName;
    private String factoryStatus;
    private String factoryContact;
    private String factoryPhone;
    private String processMode;
    private Long orderId;
    private String orderNo;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private String aliasName;
    private String brand;
    private AttachmentVO image;
    private Long techPackId;
    private Integer techPackVersion;
    private LocalDate orderDate;
    private LocalDate dueDate;
    private LocalDate expectDate;
    private Integer daysLeft;
    private BigDecimal unitPrice;
    private Long priceSourceId;
    /** 价格档案里这家工厂 × 这个款 × 这种加工方式按下单日期的现价；没有是 null */
    private BigDecimal archivePrice;
    private String priceChangeReason;
    private BigDecimal taxPct;
    private boolean taxIncluded;
    private int totalQty;
    private BigDecimal totalAmount;
    private Long merchId;
    private String merchName;
    private String settleMethod;
    private String terms;
    private String issueStatus;
    /** 已回货，含次品；良品 = returnedQty − defectQty（上限、回齐、未回都按良品，甲方 11-19） */
    private int returnedQty;
    /** 其中次品（退回工厂返修，修好再登记一次回货） */
    private int defectQty;
    private String currentNodeName;
    private RiskVO risk = RiskVO.NONE;
    private int nearDays;
    private List<String> sizes = List.of();
    private List<ProductionItemVO> items = List.of();
    private List<ProductionNodeVO> nodes = List.of();
    private List<OpenIssueBrief> openIssues = List.of();
    private int issueCount;
    private int receiptCount;
    private int stockDocCount;
}
