package com.lingmao.scm.module.style.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 款式列表的一行（resources/mapper/style/StyleMapper.xml）：品类名、尺码组名是只读 JOIN 出来的（架构 §3.2 规则 4）；
 * 主图、颜色色块、设计师名字查完一页再一次取
 */
@Data
public class StyleListVO {
    private Long id;
    private String code;
    private String name;
    private String aliasName;
    private String styleClass;
    private String brand;
    private Long categoryId;
    private String categoryName;
    private Integer year;
    private String season;
    private String wave;
    private Long designerId;
    private String designerName;
    private Long sizeGroupId;
    private String sizeGroupName;
    private Integer sizeCount;
    private Integer colorCount;
    private Integer skuCount;
    private BigDecimal tagPrice;
    private StyleStatus status;
    private TechPackStatus techPackStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    private AttachmentVO image;

    /** 款式上的颜色（停用的也在），按顺序：列表、图卡显示色块 */
    private List<ColorOption> colors = List.of();
}
