package com.lingmao.scm.common.page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表状态页签上的条数（GET /api/xxx/counts，docs/api/README §3）：一个状态一行，mapper 里 GROUP BY status 查出来，
 * toMap 成 { DRAFT: 3, PENDING: 1, … } 返回；没有单据的状态不在里面，"全部"由前端相加
 */
public record StatusCount(String status, Long count) {

    public static Map<String, Long> toMap(List<StatusCount> counts) {
        Map<String, Long> map = new LinkedHashMap<>();
        counts.forEach(c -> map.put(c.status(), c.count()));
        return map;
    }
}
