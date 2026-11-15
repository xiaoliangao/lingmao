package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个文件几张表的导入：按工作表的名字读（领猫导出的物料是"物料信息""物料信息SKU"两张表，"物料信息多单位"可有可无）。
 * 每张表一份列定义，列定义的 name 就是工作表名。由 Excel.open 打开：
 * <pre>
 * try (ExcelBook book = Excel.open(multipartFile)) {
 *     ExcelImport&lt;MaterialRow&gt; materials = book.sheet(materialSchema());        // 必须有这张表
 *     ExcelImport&lt;SkuRow&gt; skus = book.sheet(skuSchema());
 *     ExcelImport&lt;UnitRow&gt; units = book.optionalSheet(unitSchema());          // 没有这张表：空的，不报错
 *     ...每张表自己的检查、跨表的检查：skus.error(line.row(), "物料编码", "「物料信息」里没有这个物料")
 *     ...不拦导入的提示：materials.note(line.row(), "颜色", "颜色 C012 没有，按「C012 藏青」新建了")
 *     book.throwIfInvalid();                                    // 所有表的问题一起报，有就抛 1003
 *     ...在一个事务里写入
 *     return book.result(materials.lines().size());             // { total, notes }
 * }
 * </pre>
 * 问题清单、提示清单里每一项都带工作表名，按读表的顺序、表里按行号排。
 */
public final class ExcelBook implements AutoCloseable {

    private final Workbook workbook;
    /** 读过的表，按读的顺序：问题、提示按这个顺序列 */
    private final List<ExcelImport<?>> sheets = new ArrayList<>();

    ExcelBook(Workbook workbook) {
        this.workbook = workbook;
    }

    Workbook workbook() {
        return workbook;
    }

    /** 读一张必须有的表。没有这张表、或者整张是空的，记一处问题（throwIfInvalid 时一起报），返回空的 */
    public <R> ExcelImport<R> sheet(ExcelSchema<R> schema) {
        return read(schema, true);
    }

    /** 读一张可以没有的表。没有、或者整张是空的，返回空的，不算问题 */
    public <R> ExcelImport<R> optionalSheet(ExcelSchema<R> schema) {
        return read(schema, false);
    }

    private <R> ExcelImport<R> read(ExcelSchema<R> schema, boolean required) {
        String name = schema.name();
        Sheet sheet = workbook.getSheet(name);
        ExcelImport<R> result;
        if (sheet == null || ExcelReader.isBlank(sheet)) {
            List<ImportError> errors = !required ? List.of() : List.of(new ImportError(name, 0, null, sheet == null
                    ? "文件里没有「" + name + "」这张工作表：表名要和模板里的一样"
                    : "「" + name + "」这张表是空的：第 1 行应该是表头"));
            result = new ExcelImport<>(name, List.of(), errors, required);
        } else {
            result = ExcelReader.read(schema, sheet, name);
        }
        sheets.add(result);
        return result;
    }

    public boolean hasErrors() {
        return sheets.stream().anyMatch(ExcelImport::hasErrors);
    }

    /** 所有表的问题：按读表的顺序，表里按行号 */
    public List<ImportError> errors() {
        return sheets.stream().flatMap(sheet -> sheet.errors().stream()).toList();
    }

    /** 所有表的提示，顺序同 errors */
    public List<ImportError> notes() {
        return sheets.stream().flatMap(sheet -> sheet.notes().stream()).toList();
    }

    /** 有任何一处问题：抛 1003，data 是所有表的问题。读过的表一行数据都没有：抛 1000 */
    public void throwIfInvalid() {
        List<ImportError> errors = errors();
        if (!errors.isEmpty()) {
            throw ExcelImport.invalid(errors);
        }
        if (sheets.stream().allMatch(sheet -> sheet.lines().isEmpty())) {
            throw new BizException("文件里没有数据：每张表第 1 行是表头，从第 2 行开始一行填一条");
        }
    }

    /** 导入成功的返回：条数由调用方说（几张表的行数怎么算一条，各资源不一样），提示是所有表的 */
    public ImportResult result(int total) {
        return ImportResult.of(total, notes());
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("关闭导入文件失败", e);
        }
    }
}
