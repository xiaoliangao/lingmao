package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** 可重复读取的导出计划；HTTP 使用 ExcelDownloads，在同一快照中先预检再流式输出。 */
public final class ExcelExport {
    private final List<StreamingExcel.Sheet<?>> sheets = new ArrayList<>();
    ExcelExport() { }
    public <R> ExcelExport sheet(ExcelSchema<R> schema, List<R> rows) {
        if (rows.size() > Excel.MAX_EXPORT_ROWS)
            throw new BizException("要导出的超过 5 万行了，请缩小筛选范围再导出");
        return sheet(schema, sink -> { for (R row : rows) sink.row(row); });
    }
    /** 生产者的游标、预算必须每次执行重新初始化，不能捕获已消费的迭代器。 */
    public <R> ExcelExport sheet(ExcelSchema<R> schema, StreamingExcel.Producer<R> producer) {
        if (sheets.stream().anyMatch(s -> s.schema().name().equalsIgnoreCase(schema.name())))
            throw new IllegalArgumentException("工作表重名了: " + schema.name());
        sheets.add(new StreamingExcel.Sheet<>(schema, producer, Excel.MAX_EXPORT_ROWS)); return this;
    }
    public void validate() throws IOException { validate(() -> { }); }
    void validate(Runnable checkpoint) throws IOException { StreamingExcel.validate(sheets, checkpoint); }
    /** 调用方须在提交响应头前 validate；写出时仍逐行检查边界。 */
    public void writeTo(OutputStream output) throws IOException { writeTo(output, () -> { }); }
    void writeTo(OutputStream output, Runnable checkpoint) throws IOException { StreamingExcel.write(sheets, output, checkpoint); }
    /** 小文件/内部兼容入口；HTTP 业务导出不得使用。 */
    public byte[] toBytes() {
        try { validate(); ByteArrayOutputStream output = new ByteArrayOutputStream(); writeTo(output); return output.toByteArray(); }
        catch (IOException e) { throw new UncheckedIOException("生成导出文件失败", e); }
    }
}
