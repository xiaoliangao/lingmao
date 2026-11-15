package com.lingmao.scm.common.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入接口成功时的返回（RFC 001 §9.5）：{ total, notes }。前端 ImportDialog 显示"导入完成，共 total 条"，
 * notes 不空就在下面列出来。
 * notes 是提示：不拦导入、但要让人知道的事（新建了哪些颜色、哪一格的值没导……），格式和 1003 的错误清单一样。
 * 同一种情况尽量合成一条说（"第 3、8、12 行的客供按我方采购导入"），不要一行一条
 *
 * @param total 导入了多少条
 * @param notes 提示，最多列 500 条，再多的合成最后一条"还有 N 条提示没有列出"
 */
public record ImportResult(int total, List<ImportError> notes) {

    public ImportResult {
        notes = List.copyOf(notes);
    }

    /** 没有提示 */
    public static ImportResult of(int total) {
        return new ImportResult(total, List.of());
    }

    /** notes 按要显示的顺序给（ExcelImport.notes()、ExcelBook.notes() 已经排好） */
    public static ImportResult of(int total, List<ImportError> notes) {
        if (notes.size() <= ExcelImport.MAX_LISTED_ERRORS) {
            return new ImportResult(total, notes);
        }
        List<ImportError> listed = new ArrayList<>(notes.subList(0, ExcelImport.MAX_LISTED_ERRORS - 1));
        int rest = notes.size() - listed.size();
        listed.add(new ImportError(0, null, "还有 " + rest + " 条提示没有列出"));
        return new ImportResult(total, listed);
    }
}
