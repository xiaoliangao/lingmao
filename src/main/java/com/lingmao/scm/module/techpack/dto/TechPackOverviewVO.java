package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.dto.ColorOption;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 一个款的资料包概况（GET /api/tech-packs?styleId=，前端 TechPackOverview，docs/api/01 §3.2） */
@Data
public class TechPackOverviewVO {
    private Long styleId;
    /** 新版本在前 */
    private List<TechPackBriefVO> packs;
    /** 草稿 > 已生效 > 最新的一版；一个都没有是 null */
    private Long defaultPackId;
    /** 能不能新建：有新建权限、款式没停用、还没有草稿 */
    private boolean creatable;
    /** 已生效的生产 BOM 里随款色的行还没配料的款色（款式后来加的颜色） */
    private List<ColorOption> unmatchedColors;
    /** 已生效版的单件成本；没看成本权限、还没有生效的版本是 null */
    private BigDecimal unitCost;
    private Long unitCostPackId;
}
