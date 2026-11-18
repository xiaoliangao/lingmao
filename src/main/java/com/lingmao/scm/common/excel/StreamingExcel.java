package com.lingmao.scm.common.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 边取数边写出合法的 XLSX，不在内存里建工作簿，也不落整张临时工作表。 */
public final class StreamingExcel {
    private StreamingExcel() {}
    @FunctionalInterface public interface Producer<R> { void write(Sink<R> sink) throws IOException; }
    public interface Sink<R> {
        void row(R row) throws IOException;
        /** 把压缩器和网络连接里的数据冲出去，再去数据库取下一批。 */
        void flush() throws IOException;
        long rows();
    }

    public static <R> long write(ExcelSchema<R> schema, OutputStream output, Producer<R> producer) throws IOException {
        try (FlushableZip zip = new FlushableZip(output)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""");
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\""
                    + xml(schema.name()) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""");
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            put(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews><sheetData><row r=\"1\">");
            List<ExcelColumn<R, ?>> columns = schema.columns();
            for (int i = 0; i < columns.size(); i++) cell(zip, i, 1, columns.get(i).header(), true);
            put(zip, "</row>");
            class Rows implements Sink<R> {
                long count;
                @Override public void row(R row) throws IOException {
                    if (count >= 1_048_575) throw new IOException("Excel row limit exceeded");
                    long number = ++count + 1;
                    put(zip, "<row r=\"" + number + "\">");
                    for (int i = 0; i < columns.size(); i++) cell(zip, i, number, columns.get(i).exportValue(row), columns.get(i).isCode());
                    put(zip, "</row>");
                }
                @Override public void flush() throws IOException { zip.flush(); }
                @Override public long rows() { return count; }
            }
            Rows rows = new Rows(); producer.write(rows);
            put(zip, "</sheetData></worksheet>"); zip.closeEntry(); zip.finish(); zip.flush(); return rows.rows();
        }
    }

    private static void cell(OutputStream out, int column, long row, Object value, boolean text) throws IOException {
        if (value == null) return;
        String reference = org.apache.poi.ss.util.CellReference.convertNumToColString(column) + row;
        if (!text && value instanceof Number number) {
            String numeric = number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
            put(out, "<c r=\"" + reference + "\"><v>" + numeric + "</v></c>");
        } else {
            String string = value instanceof LocalDate date ? date.toString() : value.toString();
            put(out, "<c r=\"" + reference + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xml(safeText(string)) + "</t></is></c>");
        }
    }

    public static String safeText(String value) {
        if (value == null || value.isEmpty()) return value;
        String stripped = value.replace('\u00a0', ' ').stripLeading();
        return !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String xml(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> {
            switch (c) { case '&' -> out.append("&amp;"); case '<' -> out.append("&lt;"); case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;"); case '\'' -> out.append("&apos;");
                default -> { if (c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF) out.appendCodePoint(c); }
            }
        });
        return out.toString();
    }
    private static void entry(ZipOutputStream zip, String name, String body) throws IOException { zip.putNextEntry(new ZipEntry(name)); put(zip, body); zip.closeEntry(); }
    private static void put(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }

    private static final class FlushableZip extends ZipOutputStream {
        private boolean entryOpen;
        private FlushableZip(OutputStream out) { super(out, StandardCharsets.UTF_8); }
        @Override public void putNextEntry(ZipEntry entry) throws IOException { super.putNextEntry(entry); entryOpen = true; }
        @Override public void closeEntry() throws IOException { super.closeEntry(); entryOpen = false; }
        @Override public void flush() throws IOException {
            if (entryOpen && !def.finished()) {
                int count;
                do { count = def.deflate(buf, 0, buf.length, Deflater.SYNC_FLUSH); if (count > 0) out.write(buf, 0, count); }
                while (count == buf.length);
            }
            out.flush();
        }
    }
}
