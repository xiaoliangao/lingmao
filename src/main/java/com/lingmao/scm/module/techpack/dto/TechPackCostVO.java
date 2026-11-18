package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.CostMode;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 核价页签（GET /api/tech-packs/{id}/cost，前端 TechPackCost，docs/api/01 §3.10）。都是打开时现算的 */
@Data
public class TechPackCostVO {
    private Long techPackId;
    private String baseSize;
    /** 这次按哪个颜色算的；款式没有颜色是 null */
    private Long colorId;
    private CostMode costMode;
    /** 存着的成品单价；没填是 null */
    private BigDecimal buyPrice;
    /** 价格档案里默认加工厂做这个款"包工包料"的现价；没有是 null */
    private BigDecimal buyPriceDefault;
    private Long defaultFactoryId;
    private String defaultFactoryName;
    private List<CostRowVO> fabricRows;
    private List<CostRowVO> trimRows;
    /** 工序工价合计（不管按什么算都给） */
    private BigDecimal laborTotal;
    private Integer processCount;
    private List<CostItemVO> otherItems;
    private CostSummary summary;
    private BigDecimal tagPrice;
    /** 吊牌价 ÷ 单件成本，两位小数；算不了是 null */
    private BigDecimal ratio;
    /** 计入的行单价口径不一（含税、含运有的有有的没有） */
    private boolean mixedBasis;
}
