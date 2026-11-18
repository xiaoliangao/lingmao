package com.lingmao.scm.common.scope;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validate an explicit document selection after its DataScope-filtered query, before rendering bytes. */
public final class ExportScope {
    private ExportScope() { }

    public static <T> void check(Collection<Long> requested, Collection<T> visible,
                                 Function<T, Long> id, Function<T, Long> company) {
        if (requested != null && !requested.isEmpty()) {
            if (requested.stream().anyMatch(value -> value == null || value <= 0))
                throw new BoundaryViolation(400, "导出单据编号不正确");
            var actual = visible.stream().map(id).collect(Collectors.toSet());
            if (!actual.equals(new HashSet<>(requested)))
                throw new BoundaryViolation(404, "所选单据不存在或已不在当前可见范围，请刷新后重新选择");
        }
        Long expected = CompanyContext.header(false);
        if (expected != null && visible.stream().anyMatch(row -> !Objects.equals(expected, company.apply(row))))
            throw new BoundaryViolation(403, "当前页面主体与所选导出单据不一致，请重新选择");
    }
}
