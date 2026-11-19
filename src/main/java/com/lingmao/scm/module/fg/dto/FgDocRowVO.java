package com.lingmao.scm.module.fg.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 列表的一行（前端 FgDocRow）。仓库、回货单、订单、加工厂在 XML 里只读 JOIN；第一款的名称、图由 Service 补 */
@Data
public class FgDocRowVO {
    private Long id;
    private String no;
    private FgDocKind kind;
    private FgDocType docType;
    private String status;
    private Integer version;
    private Long companyId;
    private Long warehouseId;
    private String warehouseName;
    private Long receiptId;
    private String receiptNo;
    private String prdOrderNo;
    private String prdOrderKind;
    private String factoryName;
    private Long orderId;
    private String orderNo;
    private String reason;
    private String shipTo;
    private String logisticsNo;
    private Style firstStyle;
    private Integer styleCount;
    private Integer totalQty;
    private BigDecimal totalAmount;
    private LocalDate docDate;
    private String remark;
    @JsonIgnore
    private Long firstSkuId;
    @JsonIgnore
    private Long createBy;
    @JsonIgnore
    private Long auditBy;
    private String createByName;
    private String auditByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private List<String> actions;

    /** 第一款 */
    public record Style(Long styleId, String styleCode, String styleName, AttachmentVO image) {
    }
}
