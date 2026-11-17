package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 详情里一个款的公共信息（前端 OrderStyle）：一个款一块矩阵 */
@Data
public class OrderStyleVO {
    private Long styleId;
    private String styleCode;
    private String styleName;
    private String aliasName;
    private AttachmentVO image;
    private List<String> sizes;
    /** 审核时锁定的生产 BOM；审核前是 null */
    private Long techPackId;
    private Integer techPackVersion;
    /** 现在生效的生产 BOM；没有是 null */
    private Long currentPackId;
    private Integer currentPackVersion;
    private int qty;
    private BigDecimal amount;
}
