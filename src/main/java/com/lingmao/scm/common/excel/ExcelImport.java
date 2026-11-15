package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 读出来的一张工作表：每一行（带 Excel 里的行号）、到目前为止发现的问题、给用户的提示。
 * Excel.read / ExcelBook 已经逐格解析、校验过了（必填、格式、长度、选项）；Service 再查文件内部的重复和库里的情况，
 * 把问题记进来，最后 dataOrThrow()：有任何一处问题整批不导，抛 1003 和全部问题；全对才拿到数据去写库（RFC 001 §9.3）。
 * <pre>
 * ExcelImport&lt;SupplierExcelRow&gt; file = Excel.read(excelSchema(), multipartFile);
 * file.checkUnique(SupplierExcelRow::getCode, "供应商编码");                     // 同一个文件里重复
 * Set&lt;String&gt; existing = ...;                                                 // 库里已有的编码，一次查出来
 * for (ExcelImport.Line&lt;SupplierExcelRow&gt; line : file.lines()) {
 *     if (existing.contains(line.data().getCode())) {
 *         file.error(line.row(), "供应商编码", "编码已存在");
 *     }
 * }
 * List&lt;SupplierExcelRow&gt; rows = file.dataOrThrow();                            // 有错抛 1003
 * ...写库
 * return ImportResult.of(rows.size(), file.notes());
 * </pre>
 * 某一格没解析出来时，行对象里那个字段是 null：Service 查库前判一下 null，跳过就行，那一格的问题已经记下了。
 * <p>
 * 问题（error）拦导入；提示（note）不拦，导入成功后和条数一起返回，让人知道系统替他做了什么（新建了颜色、某一格没导……）。
 */
public final class ExcelImport<R> {

    /** 1003 带回去的问题、成功时带回去的提示，最多列多少处：5000 行全错能有几万处，前端表格放不下，也没人会一条条看 */
    static final int MAX_LISTED_ERRORS = 500;

    /** 文件里的一行：row 是 Excel 里看到的行号（表头是第 1 行，数据从第 2 行起） */
    public record Line<R>(int row, R data) {
    }

    /** 工作表名；只读一张表的导入是 null，问题清单里不带表名 */
    private final String sheet;
    private final List<Line<R>> lines;
    private final List<ImportError> errors;
    private final List<ImportError> notes = new ArrayList<>();
    /** 表头没对上（缺必填列、不认识的列）：行都没读 */
    private final boolean headerFailed;

    ExcelImport(String sheet, List<Line<R>> lines, List<ImportError> errors, boolean headerFailed) {
        this.sheet = sheet;
        this.lines = List.copyOf(lines);
        this.errors = new ArrayList<>(errors);
        this.headerFailed = headerFailed;
    }

    /** 全部非空行，按文件里的顺序 */
    public List<Line<R>> lines() {
        return lines;
    }

    /** 记一处问题（拦导入）。column 写表头的名字（不带 *）；整行的问题写 null */
    public void error(int row, String column, String message) {
        errors.add(new ImportError(sheet, row, column, message));
    }

    /** 记一条提示（不拦导入）。写法同 error；说整张表的 row 写 0 */
    public void note(int row, String column, String message) {
        notes.add(new ImportError(sheet, row, column, message));
    }

    /** 同一个文件里 key 重复的（如同一个编码出现两次）：第一次出现的不算，后面每一次都记"和第 n 行重复"。key 是 null 的不管 */
    public void checkUnique(Function<R, ?> key, String column) {
        Map<Object, Integer> firstRow = new HashMap<>();
        for (Line<R> line : lines) {
            Object value = key.apply(line.data());
            if (value == null) {
                continue;
            }
            Integer first = firstRow.putIfAbsent(value, line.row());
            if (first != null) {
                error(line.row(), column, "和第 " + first + " 行重复");
            }
        }
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** 到目前为止的问题，按行号排 */
    public List<ImportError> errors() {
        return sorted(errors);
    }

    /** 到目前为止的提示，按行号排 */
    public List<ImportError> notes() {
        return sorted(notes);
    }

    /** 没有问题：返回每一行的数据，按文件里的顺序。有问题：抛 1003，data 是问题清单 */
    public List<R> dataOrThrow() {
        if (!errors.isEmpty()) {
            throw invalid(errors());
        }
        return lines.stream().map(Line::data).toList();
    }

    String sheet() {
        return sheet;
    }

    boolean headerFailed() {
        return headerFailed;
    }

    /** 1003：errors 按要显示的顺序给，最多列 500 处 */
    static BizException invalid(List<ImportError> errors) {
        int total = errors.size();
        String msg = "导入校验没通过，共 " + total + " 处问题，一行都没有导入。改好以后重新导入整个文件";
        if (total > MAX_LISTED_ERRORS) {
            msg += "（下面只列出前 " + MAX_LISTED_ERRORS + " 处）";
        }
        return new BizException(ErrorCodes.IMPORT_INVALID, msg, List.copyOf(errors.subList(0, Math.min(total, MAX_LISTED_ERRORS))));
    }

    /** 稳定排序：同一行里先解析出来的（按列的顺序）在前，Service 后加的在后 */
    private static List<ImportError> sorted(List<ImportError> items) {
        return items.stream().sorted(Comparator.comparingInt(ImportError::row)).toList();
    }
}
