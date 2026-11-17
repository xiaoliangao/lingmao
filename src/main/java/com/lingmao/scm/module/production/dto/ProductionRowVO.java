package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 外发加工单、成品采购单列表和看板列表的一行（前端 ProductionRow）。节点、图、人名、风险、按钮由 Service 填 */
@Data
public class ProductionRowVO {
    private Long id;
    private String no;
    private String kind;
    private String status;
    private Integer version;
    private Long factoryId;
    private String factoryShortName;
    private String processMode;
    private Long orderId;
    private String orderNo;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private AttachmentVO image;
    private int totalQty;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private LocalDate dueDate;
    private LocalDate expectDate;
    /** 预计交期 ?? 交期 − 今天；只有已下单、生产中的有 */
    private Integer daysLeft;
    private List<NodeDot> nodes = List.of();
    private String currentNodeName;
    private String issueStatus;
    private int returnedQty;
    private int defectQty;
    private int openIssueCount;
    private String firstOpenIssueType;
    private Long merchId;
    private String merchName;
    private RiskVO risk = RiskVO.NONE;
    private List<String> actions = List.of();

    /** 列表 SQL 现算的货期预警（ProductionOrderMapper.xml 的 risk），Service 拼成 risk；制单人算按钮用。不返回 */
    @JsonIgnore
    private boolean riskLate;
    @JsonIgnore
    private boolean riskNear;
    @JsonIgnore
    private boolean riskNodeLate;
    @JsonIgnore
    private boolean riskIssue;
    @JsonIgnore
    private boolean riskUpstream;
    @JsonIgnore
    private Long createBy;
    @JsonIgnore
    private Long companyId;
}
