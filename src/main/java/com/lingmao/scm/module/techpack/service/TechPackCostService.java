package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;

/**
 * 核价（PRD §6.4.5、架构 §4.9）：打开时现算，不存。面料 + 辅料 + 加工费 + 其他费用 = 单件成本。
 * 草稿算的是上次保存的数（docs/api/01 §6 第 1 条）。看成本的权限由 Controller 管
 */
public interface TechPackCostService {

    /** 核价页签。colorId 不给 = 款式第一个颜色；给了却不是款式启用的颜色 → 1000 */
    TechPackCostVO cost(long id, Long colorId);

    /** 按款看成本：每个款取已生效的生产 BOM，按款式第一个颜色算 */
    PageResult<StyleCostRowVO> styleCosts(StyleCostQuery query);

    /** 这几版资料包的单件成本（核价合计，按款式第一个颜色算）：订单毛利用（RFC 019 §3）。款式看不到、算不了、核价没填全的不在结果里 */
    java.util.Map<Long, java.math.BigDecimal> unitCosts(java.util.Collection<Long> techPackIds);
}
