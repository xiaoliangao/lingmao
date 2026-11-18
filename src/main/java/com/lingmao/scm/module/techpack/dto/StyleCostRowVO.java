package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.entity.PackStage;
import lombok.Data;

import java.math.BigDecimal;

/** 按款看成本的一行（前端 StyleCostRow）：取已生效的生产 BOM；数和核价页签按第一个颜色算的一样 */
@Data
public class StyleCostRowVO {
    private Long styleId;
    private String styleCode;
    private String styleName;
    private String aliasName;
    private AttachmentVO image;
    private Long techPackId;
    private PackStage stage;
    private Integer version;
    private BigDecimal fabric;
    private BigDecimal trim;
    private BigDecimal labor;
    private BigDecimal other;
    private BigDecimal total;
    private BigDecimal tagPrice;
    private BigDecimal ratio;
}
