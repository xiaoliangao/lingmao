package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;

import java.util.ArrayList;
import java.util.List;

/**
 * 几张表的导出：每张表一个工作表，表名用列定义的 name，和几张表的模板（Excel.template(schemas...)）一样，
 * 导出的文件改了能原样导回来。由 Excel.exportBook() 开始：
 * <pre>
 * return Excel.exportBook()
 *         .sheet(materialSchema(), materials)
 *         .sheet(skuSchema(), skus)
 *         .toBytes();
 * </pre>
 */
public final class ExcelExport {

    private final List<ExcelWriter.SheetRows<?>> sheets = new ArrayList<>();

    ExcelExport() {
    }

    /** 加一张表。一张表超过 5 万行抛 1000，提示缩小筛选范围 */
    public <R> ExcelExport sheet(ExcelSchema<R> schema, List<R> rows) {
        if (rows.size() > Excel.MAX_EXPORT_ROWS) {
            throw new BizException("要导出的超过 " + Excel.MAX_EXPORT_ROWS / 10_000 + " 万行了，请缩小筛选范围再导出");
        }
        if (sheets.stream().anyMatch(sheet -> sheet.schema().name().equalsIgnoreCase(schema.name()))) {
            throw new IllegalArgumentException("工作表重名了: " + schema.name());
        }
        sheets.add(new ExcelWriter.SheetRows<>(schema, rows));
        return this;
    }

    public byte[] toBytes() {
        if (sheets.isEmpty()) {
            throw new IllegalStateException("一张表都没有");
        }
        return ExcelWriter.export(sheets);
    }
}
