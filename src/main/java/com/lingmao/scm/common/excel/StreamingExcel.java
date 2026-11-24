package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.WorkbookUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 按行生成 XLSX，支持多工作表；不缓存完整工作簿，不产生 SXSSF 临时文件。 */
public final class StreamingExcel {
    private StreamingExcel() { }
    @FunctionalInterface public interface Producer<R> { void write(Sink<R> sink) throws IOException; }
    public interface Checkpoint extends Runnable {
        default void flush() { run(); }
    }
    public interface Sink<R> {
        void row(R row) throws IOException;
        /** 每次数据库取下一批之前调用；断连异常必须立即终止生产者。 */
        void flush() throws IOException;
        long rows();
    }
    public record Sheet<R>(ExcelSchema<R> schema, Producer<R> producer, long maxRows) {
        public Sheet { Objects.requireNonNull(schema); Objects.requireNonNull(producer); }
    }

    /** 库存流水等专用生产者自行执行更严格的行数/任务预算。 */
    public static <R> long write(ExcelSchema<R> schema, OutputStream output, Producer<R> producer) throws IOException {
        return write(List.of(new Sheet<>(schema, producer, 1_048_575)), output, () -> { }, Long.MAX_VALUE);
    }

    static void validate(List<Sheet<?>> sheets, Runnable checkpoint) throws IOException {
        names(sheets);
        Budget budget = new Budget(checkpoint);
        for (Sheet<?> sheet : sheets) validateSheet(sheet, budget);
    }

    private static <R> void validateSheet(Sheet<R> sheet, Budget budget) throws IOException {
        var schema = sheet.schema(); var columns = schema.columns();
        for (int i = 0; i < columns.size(); i++) budget.text(checkedText(schema.name(), 1, i, columns.get(i).header(), columns.get(i).header()));
        sheet.producer().write(new Sink<>() {
            long count;
            public void row(R row) {
                checkRows(++count, sheet.maxRows(), schema.name());
                budget.row();
                for (int i = 0; i < columns.size(); i++) {
                    Object value = columns.get(i).exportValue(row);
                    if (value != null) {
                        if (value instanceof Number || value instanceof LocalDate) budget.text(value.toString());
                        else budget.text(checkedText(schema.name(), count + 1, i, columns.get(i).header(), value.toString()));
                    }
                }
            }
            public void flush() { budget.flush(); }
            public long rows() { return count; }
        });
    }

    static long write(List<Sheet<?>> sheets, OutputStream output, Runnable checkpoint) throws IOException {
        return write(sheets, output, checkpoint, 250L * 1024 * 1024);
    }

    private static long write(List<Sheet<?>> sheets, OutputStream output, Runnable checkpoint, long maxXml) throws IOException {
        List<String> names = names(sheets);
        FlushableZip zip = new FlushableZip(output);
        try {
            StringBuilder types = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder workbook = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder relationships = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for (int i = 1; i <= sheets.size(); i++) {
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                workbook.append("<sheet name=\"").append(xml(names.get(i-1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
                relationships.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
            }
            entry(zip, "[Content_Types].xml", types.append("</Types>").toString());
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
            entry(zip, "xl/_rels/workbook.xml.rels", relationships.append("</Relationships>").toString());
            entry(zip, "xl/styles.xml", STYLES);
            long rows = 0; Budget budget = new Budget(checkpoint, maxXml);
            for (int i = 0; i < sheets.size(); i++) {
                checkpoint.run(); zip.flush();
                rows += writeSheet(zip, i + 1, sheets.get(i), budget);
            }
            zip.finish(); zip.flush(); return rows;
        } finally {
            // close() would finish a failed sheet/ZIP. Abort instead, and leave the caller-owned stream open.
            zip.release();
        }
    }

    private static <R> long writeSheet(FlushableZip zip, int index, Sheet<R> sheet, Budget budget) throws IOException {
        var schema = sheet.schema(); var columns = schema.columns();
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + index + ".xml"));
        put(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for (int i = 0; i < columns.size(); i++) {
            int width = Math.min(48, Math.max(12, columns.get(i).header().codePointCount(0, columns.get(i).header().length()) * 2 + 4));
            put(zip, "<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + width + "\" customWidth=\"1\"/>");
        }
        put(zip, "</cols><sheetData><row r=\"1\">");
        for (int i = 0; i < columns.size(); i++) cell(zip, schema.name(), i, 1, columns.get(i).header(), columns.get(i).header(), true, true, budget);
        put(zip, "</row>");
        class Rows implements Sink<R> {
            long count;
            public void row(R row) throws IOException {
                checkRows(++count, sheet.maxRows(), schema.name()); budget.row();
                long number = count + 1;
                put(zip, "<row r=\"" + number + "\">");
                for (int i = 0; i < columns.size(); i++) cell(zip, schema.name(), i, number, columns.get(i).header(), columns.get(i).exportValue(row), columns.get(i).isCode(), false, budget);
                put(zip, "</row>");
            }
            public void flush() throws IOException { budget.flush(); put(zip, "\n"); zip.flush(); }
            public long rows() { return count; }
        }
        Rows rows = new Rows(); sheet.producer().write(rows);
        put(zip, "</sheetData></worksheet>"); zip.closeEntry(); zip.flush(); return rows.rows();
    }

    private static void cell(OutputStream out, String sheet, int column, long row, String header, Object value, boolean text, boolean heading, Budget budget) throws IOException {
        if (value == null) return;
        String reference = CellReference.convertNumToColString(column) + row;
        if (!text && value instanceof Number number) {
            String numeric = number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
            budget.text(numeric); put(out, "<c r=\"" + reference + "\"><v>" + numeric + "</v></c>");
        } else if (value instanceof LocalDate date) {
            budget.text(date.toString()); put(out, "<c r=\"" + reference + "\" s=\"2\"><v>" + DateUtil.getExcelDate(date) + "</v></c>");
        } else {
            String string = checkedText(sheet, row, column, header, value.toString()); budget.text(string);
            put(out, "<c r=\"" + reference + "\" s=\"" + (heading ? 1 : text ? 3 : 0) + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + cellText(string) + "</t></is></c>");
        }
    }

    public static String checkedText(String sheet, long row, int column, String header, String value) {
        String text = safeText(value);
        if (text != null && text.length() > 32_767) {
            int end = header.offsetByCodePoints(0, Math.min(64, header.codePointCount(0, header.length())));
            String label = header.substring(0, end) + (end < header.length() ? "…" : "");
            throw new BizException("工作表「" + sheet + "」第 " + row + " 行第 " + (column + 1) + " 列（" + label + "）超过 32767 字符，请减少该单元格的关联内容后重试");
        }
        return text;
    }

    public static String safeText(String value) {
        if (value == null || value.isEmpty()) return value;
        String stripped = value.replace('\u00a0', ' ').stripLeading();
        return !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0 ? "'" + value : value;
    }
    private static void checkRows(long rows, long max, String name) {
        if (rows > max) throw new BizException(name + "超过导出上限（" + max + " 行），请缩小筛选范围再导出");
    }
    private static List<String> names(List<Sheet<?>> sheets) {
        if (sheets.isEmpty()) throw new IllegalStateException("一张表都没有");
        HashSet<String> used = new HashSet<>();
        return sheets.stream().map(s -> {
            if (s.schema().columns().size() > 16_384) throw new BizException("导出列数超过 Excel 上限，请缩小筛选范围");
            String name = WorkbookUtil.createSafeSheetName(s.schema().name());
            if (!used.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("工作表重名了: " + name);
            return name;
        }).toList();
    }
    /** 保守估计未压缩 XML，避免高压缩率的大单元格绕过文件资源预算。 */
    private static final class Budget {
        final Runnable checkpoint; final long maxXml; long bytes = 1_048_576;
        Budget(Runnable checkpoint) { this(checkpoint, 250L * 1024 * 1024); }
        Budget(Runnable checkpoint, long maxXml) { this.checkpoint = checkpoint; this.maxXml = maxXml; }
        void flush() { if (checkpoint instanceof Checkpoint c) c.flush(); else checkpoint.run(); }
        void row() { checkpoint.run(); add(64); }
        void text(String value) { add(128L + value.length() * 8L); }
        void add(long n) { bytes += n; if (bytes > maxXml) throw new BizException("导出内容超过 250MB 资源预算，请缩小筛选范围再导出"); }
    }
    /** ST_Xstring 的控制字符编码和字面 _xHHHH_ 都必须保留，不能交给 XML 换行规范化或 Excel 自动解码。 */
    private static String cellText(String value) {
        StringBuilder out = new StringBuilder(value.length());
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_' && excelEscapeAt(value, i)) { out.append("_x005F_"); continue; }
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                out.append(c).append(value.charAt(++i)); continue;
            }
            if (c < 32 && c != '\t' && c != '\n' || c == '\uFFFE' || c == '\uFFFF' || Character.isSurrogate(c)) {
                out.append("_x").append(hex.charAt(c >>> 12)).append(hex.charAt(c >>> 8 & 15))
                        .append(hex.charAt(c >>> 4 & 15)).append(hex.charAt(c & 15)).append('_');
                continue;
            }
            switch (c) {
                case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;"); case '\'' -> out.append("&apos;"); default -> out.append(c);
            }
        }
        return out.toString();
    }
    private static boolean excelEscapeAt(String text, int start) {
        if (start + 6 >= text.length() || text.charAt(start + 6) != '_'
                || text.charAt(start + 1) != 'x' && text.charAt(start + 1) != 'X') return false;
        for (int i = start + 2; i < start + 6; i++) if (Character.digit(text.charAt(i), 16) < 0) return false;
        return true;
    }
    private static String xml(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            switch (c) { case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '\"' -> out.append("&quot;"); case '\'' -> out.append("&apos;");
                default -> { if (c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF) out.appendCodePoint(c); }
            }
        }); return out.toString();
    }
    private static void entry(ZipOutputStream zip, String name, String body) throws IOException { zip.putNextEntry(new ZipEntry(name)); put(zip, body); zip.closeEntry(); }
    private static void put(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/></numFmts>
            <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
            <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor indexed="22"/><bgColor indexed="64"/></patternFill></fill></fills>
            <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
            <cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
            <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
            """;
    private static final class FlushableZip extends ZipOutputStream {
        private boolean entryOpen;
        private FlushableZip(OutputStream out) { super(out, StandardCharsets.UTF_8); }
        public void putNextEntry(ZipEntry entry) throws IOException { super.putNextEntry(entry); entryOpen = true; }
        public void closeEntry() throws IOException { super.closeEntry(); entryOpen = false; }
        public void flush() throws IOException {
            if (entryOpen && !def.finished()) {
                int count;
                do { count = def.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH); if (count > 0) out.write(buf, 0, count); }
                while (count == buf.length);
            }
            out.flush();
        }
        void release() { def.end(); }
    }
}
