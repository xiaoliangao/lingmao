package com.lingmao.scm.module.style.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 款式详情（GET /api/styles/{id}）：款式的列 + 显示用的名字（品类路径、人名、尺码组、主力面料、默认加工厂）
 * + 颜色 + 图片、设计附件 + 领猫附加属性 + 当前用户能做的动作。SKU 另取（GET /skus）
 */
@Data
public class StyleDetailVO {
    private Long id;
    private String code;
    private String name;
    private String aliasName;
    private String styleClass;
    /** 品类线，字典 product_line */
    private String productLine;
    private String brand;
    private Long categoryId;
    /** 品类名，和全路径"女装/上衣/衬衫" */
    private String categoryName;
    private String categoryPath;
    private Integer year;
    private String season;
    private String wave;
    private String styleTag;
    private String series;
    private String gender;
    private Long designerId;
    private String designerName;
    private Long patternMakerId;
    private String patternMakerName;
    private String source;
    private Long customerId;
    private String customerStyleNo;
    private String sampleNo;
    private Long sizeGroupId;
    private String sizeGroupName;
    /** 尺码组里的尺码，按顺序 */
    private List<String> sizes = List.of();
    private Long mainMaterialId;
    private String mainMaterialCode;
    private String mainMaterialName;
    private String productName;
    private String composition;
    private String execStandard;
    private String safetyCategory;
    private BigDecimal tagPrice;
    private BigDecimal refCost;
    private Long defaultFactoryId;
    private String defaultFactoryCode;
    /** 默认加工厂的简称 */
    private String defaultFactoryName;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate launchDate;

    private StyleStatus status;
    private TechPackStatus techPackStatus;
    private String remark;
    private Map<String, String> legacyAttrs;

    private Long createBy;
    private String createByName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    private List<StyleColorVO> colors = List.of();
    private int skuCount;
    private List<AttachmentVO> images = List.of();
    /** 设计附件（图稿、设计说明） */
    private List<AttachmentVO> files = List.of();

    /**
     * 当前用户能做的：enable、disable（转换表允许 ∩ 有 edit 权限）、edit、remove（有权限）。按钮照它显示（架构 §4.2）。
     * 没有启用的 SKU 时 enable 照样给：点了后端说清"先生成 SKU"，比按钮不见了让人猜好
     */
    private List<String> actions = List.of();
}
