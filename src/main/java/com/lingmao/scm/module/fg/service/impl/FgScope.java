package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import java.util.Collection;
import java.util.List;

/** 主体只是已选择的业务上下文；领域 DataScope 和动作权限必须另行检查。 */
final class FgScope {
    private FgScope() { }
    static long company(Long companyId) {
        if (companyId == null || companyId <= 0) throw new BizException(400, "请明确选择主体");
        CompanyContext.create(companyId); // 数据 GET 同样必须提供匹配的 X-Company-Id。
        return companyId;
    }
    static List<Long> ids(Collection<Long> ids, int maximum, String label) {
        if (ids == null || ids.isEmpty() || ids.size() > maximum || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new BizException(400, label + "需要 1 至 " + maximum + " 个有效 ID");
        return ids.stream().distinct().toList();
    }
}
