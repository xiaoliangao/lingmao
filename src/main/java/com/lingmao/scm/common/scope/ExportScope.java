package com.lingmao.scm.common.scope;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 导出勾选的单据时，在按数据范围过滤的查询之后、生成文件之前再核一遍。 */
public final class ExportScope {
    private ExportScope() { }

    /** 分页导出只保存所选 ID 的可见性，不累计整行；主体检查仍覆盖每一页。 */
    public static <T> Accumulator<T> accumulate(Collection<Long> requested, Function<T, Long> id,
                                               Function<T, Long> company) {
        return new Accumulator<>(requested, id, company);
    }

    public static final class Accumulator<T> {
        private final java.util.Set<Long> requested;
        private final java.util.Set<Long> seen = new HashSet<>();
        private final Function<T, Long> id;
        private final Function<T, Long> company;
        private final Long expected = CompanyContext.header(false);
        private boolean mismatchedCompany;
        private Accumulator(Collection<Long> requested, Function<T, Long> id, Function<T, Long> company) {
            if (requested != null && requested.stream().anyMatch(value -> value == null || value <= 0))
                throw new BoundaryViolation(400, "导出单据编号不正确");
            this.requested = requested == null ? java.util.Set.of() : new HashSet<>(requested);
            this.id = id; this.company = company;
        }
        public void accept(Collection<T> rows) {
            for (T row : rows) {
                if (expected != null && !Objects.equals(expected, company.apply(row))) mismatchedCompany = true;
                if (!requested.isEmpty()) seen.add(id.apply(row));
            }
        }
        public void finish() {
            if (!requested.isEmpty() && !requested.equals(seen))
                throw new BoundaryViolation(404, "所选单据不存在或已不在当前可见范围，请刷新后重新选择");
            if (mismatchedCompany)
                throw new BoundaryViolation(403, "当前页面主体与所选导出单据不一致，请重新选择");
        }
    }

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
