package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 选款式弹窗的一行（DataPick kind="style"，原型 3e-pick.js）：款式（图 + 名称 + 款号）、品类、年份季节、颜色、资料包、吊牌价。
 * 只有启用的款式
 */
@Data
public class StylePickVO {
    private Long id;
    private String code;
    private String name;
    private String aliasName;
    private String brand;
    private Long categoryId;
    private String categoryName;
    private Integer year;
    private String season;
    private BigDecimal tagPrice;
    private TechPackStatus techPackStatus;
    private AttachmentVO image;
    /** 款式上启用的颜色，按顺序 */
    private List<ColorOption> colors = List.of();
}
