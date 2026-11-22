package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2ExportBudgetTest {
    @Test void subtractsActualRowsAndStopsBeforeReadingLaterParentBatches() {
        List<Integer> limits = new ArrayList<>();
        List<Integer> queried = new ArrayList<>();
        assertThatThrownBy(() -> BoundedExportRows.read(List.of(1, 2, 3, 4), 1, 3, "SKU", (ids, limit) -> {
            queried.addAll(ids); limits.add(limit);
            return List.of(ids.getFirst() + "a", ids.getFirst() + "b");
        })).isInstanceOf(BizException.class).hasMessageContaining("SKU").hasMessageContaining("3 行");
        assertThat(queried).containsExactly(1, 2);
        assertThat(limits).containsExactly(4, 2);
    }

    @Test void exactlyFullBudgetStillChecksLaterParentsWithLimitOne() {
        List<Integer> limits = new ArrayList<>();
        List<String> rows = BoundedExportRows.read(List.of(1, 2, 3), 1, 3, "SKU", (ids, limit) -> {
            limits.add(limit);
            return switch (ids.getFirst()) {
                case 1 -> List.of("a", "b");
                case 2 -> List.of("c");
                default -> List.of();
            };
        });
        assertThat(rows).containsExactly("a", "b", "c");
        assertThat(limits).containsExactly(4, 2, 1);
    }

    @Test void aLaterNonemptyBatchCannotEscapeThroughAnAlreadyFullBudget() {
        List<Integer> limits = new ArrayList<>();
        assertThatThrownBy(() -> BoundedExportRows.read(List.of(1, 2, 3), 1, 3, "SKU", (ids, limit) -> {
            limits.add(limit);
            return ids.getFirst() == 1 ? List.of("a", "b", "c") : List.of("new-row");
        })).isInstanceOf(BizException.class);
        assertThat(limits).containsExactly(4, 1);
    }

    @Test void emptyParentsNeverIssueAQuery() {
        AtomicInteger calls = new AtomicInteger();
        List<String> rows = BoundedExportRows.read(List.<Long>of(), 1, 3, "SKU", (ids, limit) -> {
            calls.incrementAndGet(); return List.of("unexpected");
        });
        assertThat(rows).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test void invalidBudgetsAndOversizedParentSetsAreRejected() {
        for (int maxRows : List.of(0, -1, Excel.MAX_EXPORT_ROWS + 1)) {
            assertThatThrownBy(() -> BoundedExportRows.read(List.of(1), 1, maxRows, "SKU", (ids, limit) -> List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BoundedExportRows.read(List.of(1), 0, 3, "SKU", (ids, limit) -> List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        BoundedExportRows.requireWithinLimit(Excel.MAX_EXPORT_ROWS, Excel.MAX_EXPORT_ROWS, "主档");
        assertThatThrownBy(() -> BoundedExportRows.requireWithinLimit(Excel.MAX_EXPORT_ROWS + 1, Excel.MAX_EXPORT_ROWS, "主档"))
                .isInstanceOf(BizException.class).hasMessageContaining("50000 行");
    }
}
