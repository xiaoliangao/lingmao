package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * 给旧版多 Sheet 导出读取子行时使用的硬预算。查询必须把 limit 放进 SQL；
 * 按实际返回数扣减预算，额外一行只用于判定超限，禁止依赖预 COUNT 后全量读取。
 * 结果仍是有上限的内存列表，不代表 HTTP 或工作簿已经全链路流式化。
 */
public final class BoundedExportRows {
    private BoundedExportRows() { }

    /**
     * 每批只允许剩余预算 + 1 行；超额立即拒绝，后续父 ID 批次不会再查询。
     * 恰好填满预算时，后续批次仅用 LIMIT 1 探测，避免把恰好合法的导出误判为超限。
     * maxRows 是通用预算参数；生产导出固定传 Excel.MAX_EXPORT_ROWS，不从请求或环境变量读取。
     */
    public static <K, T> List<T> read(List<K> parentIds, int batchSize, int maxRows, String label,
                                    BiFunction<List<K>, Integer, List<T>> query) {
        Objects.requireNonNull(parentIds, "parentIds");
        Objects.requireNonNull(query, "query");
        if (batchSize < 1 || maxRows < 1 || maxRows > Excel.MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("无效的导出读取预算");
        }
        List<T> rows = new ArrayList<>();
        for (int from = 0; from < parentIds.size(); from += batchSize) {
            int remaining = maxRows - rows.size();
            List<T> batch = Objects.requireNonNull(query.apply(
                    parentIds.subList(from, Math.min(parentIds.size(), from + batchSize)), remaining + 1), "query rows");
            requireWithinLimit(batch.size(), remaining, label, maxRows);
            rows.addAll(batch);
        }
        return rows;
    }

    /** 主档 ID 已有 SQL 上限，内部导出入口也独立拒绝超量调用。 */
    public static void requireWithinLimit(int rows, int maxRows, String label) {
        requireWithinLimit(rows, maxRows, label, maxRows);
    }

    private static void requireWithinLimit(int rows, int remaining, String label, int maxRows) {
        if (rows > remaining) {
            throw new BizException(label + "超过导出上限（" + maxRows + " 行），请缩小筛选范围再导出");
        }
    }
}
