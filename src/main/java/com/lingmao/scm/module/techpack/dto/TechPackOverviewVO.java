package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.techpack.entity.PackStage;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 一个款的资料包概况（GET /api/tech-packs?styleId=，前端 TechPackOverview，docs/api/01 §3.2） */
@Data
public class TechPackOverviewVO {
    private Long styleId;
    /** 研发在前、生产在后，各自新版本在前 */
    private List<TechPackBriefVO> packs;
    /** 生产草稿 > 生产已生效 > 研发草稿 > 研发已生效；一个都没有是 null */
    private Long defaultPackId;
    /** 能新建哪个阶段：有新建权限、款式没停用、这个阶段还没有草稿 */
    private List<PackStage> creatableStages;
    /** 已生效的生产 BOM 里随款色的行还没配料的款色（款式后来加的颜色） */
    private List<ColorOption> unmatchedColors;
    /** 生产已生效版的单件成本，没有取研发已生效版；没看成本权限、都没生效是 null */
    private BigDecimal unitCost;
    private Long unitCostPackId;
    private PackStage unitCostStage;
}
