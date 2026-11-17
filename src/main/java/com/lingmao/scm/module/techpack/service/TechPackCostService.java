package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;

/**
 * 核价（PRD §6.4.5、架构 §4.9）：打开时现算，不存。面料 + 辅料 + 加工费 + 其他费用 = 单件成本；生产版有研发来源时再算一遍研发版当"研发估"。
 * 草稿算的是上次保存的数（docs/api/01 §6 第 1 条）。看成本的权限由 Controller 管
 */
public interface TechPackCostService {

    /** 核价页签。colorId 不给 = 款式第一个颜色；给了却不是款式启用的颜色 → 1000 */
    TechPackCostVO cost(long id, Long colorId);

    /** 按款看成本：每个款取生产已生效版，没有取研发已生效版，按款式第一个颜色算 */
    PageResult<StyleCostRowVO> styleCosts(StyleCostQuery query);
}
