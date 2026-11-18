package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Inspect the bounded OOXML archive before POI allocates its workbook object graph. */
final class ExcelArchiveGuard {
    static final int MAX_FILES = 128;
    static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
    static final int MAX_CELLS = 250_000;
    static final int MAX_ROW_POSITION = 20_000;
    static final int MAX_COLUMNS = 128;
    private static final String LIMIT_MESSAGE = "文件结构过大：最多 16 张表、128 列、250000 个单元格，解压后最多 32MB，请删掉多余空行、格式或拆分文件";

    private ExcelArchiveGuard() {}

    static byte[] inspect(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes((int) Excel.MAX_IMPORT_BYTES + 1);
        if (bytes.length > Excel.MAX_IMPORT_BYTES) throw new BizException("文件超过 10MB，请拆成几个文件分批导入");
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4)
            throw new BizException("文件打不开：只收真正的 .xlsx 文件，请另存为 .xlsx 后重试");
        Budget budget = new Budget();
        Set<String> paths = new HashSet<>();
        XMLInputFactory xml = XMLInputFactory.newFactory();
        xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xml.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        xml.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External resources are forbidden"); });
        // POI's streaming writer uses ZIP64 descriptors, which java.util.zip cannot reliably stream.
        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(new ByteArrayInputStream(bytes))) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!zip.canReadEntryData(entry)) throw new BizException("文件压缩格式不受支持，请另存为 .xlsx 后重试");
                String name = entry.getName();
                if (paths.size() >= MAX_FILES) throw tooLarge();
                if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                        || java.util.Arrays.asList(name.split("/", -1)).contains("..") || !paths.add(name))
                    throw new BizException("文件包含不安全或重复的压缩路径，请另存为 .xlsx 后重试");
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("vbaproject") || lower.startsWith("xl/externallinks/")
                        || lower.startsWith("xl/activex/") || lower.startsWith("xl/embeddings/"))
                    throw new BizException("导入文件不能包含宏、外部工作簿链接或嵌入对象，请移除后重试");
                EntryInput bounded = new EntryInput(zip, budget);
                if (!entry.isDirectory() && (lower.endsWith(".xml") || lower.endsWith(".rels"))) {
                    inspectXml(xml, bounded, lower, budget);
                }
                byte[] buffer = new byte[8192];
                while (bounded.read(buffer) >= 0) { /* Count every entry, including unused images/parts. */ }
                if (bounded.bytes > 1024 * 1024 && entry.getCompressedSize() > 0
                        && (double) entry.getCompressedSize() / bounded.bytes < 0.01)
                    throw new BizException("文件压缩比例异常，请去掉多余格式或另存为 .xlsx 后重试");
            }
        } catch (XMLStreamException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof BizException known) throw known;
            BizException invalid = new BizException("文件 XML 结构不安全或已损坏，请另存为 .xlsx 后重试");
            invalid.initCause(failure);
            throw invalid;
        }
        if (!paths.contains("[Content_Types].xml") || !paths.contains("xl/workbook.xml"))
            throw new BizException("文件打不开：缺少 Excel 工作簿结构，请另存为 .xlsx 后重试");
        return bytes;
    }

    private static void inspectXml(XMLInputFactory factory, InputStream input, String part, Budget budget)
            throws XMLStreamException {
        XMLStreamReader reader = factory.createXMLStreamReader(input);
        boolean sheet = part.startsWith("xl/worksheets/");
        int rows = 0, rowCells = 0, cellChars = 0, depth = 0;
        boolean inCell = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
                    throw new BizException("文件不能包含 DTD 或外部实体，请另存为 .xlsx 后重试");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++depth > 64) throw tooLarge();
                    String name = reader.getLocalName();
                    if (sheet && name.equals("row")) {
                        if (++rows > MAX_ROW_POSITION) throw tooLarge();
                        boundedRow(reader.getAttributeValue(null, "r"));
                        rowCells = 0;
                    } else if (sheet && name.equals("c")) {
                        if (++budget.cells > MAX_CELLS || ++rowCells > MAX_COLUMNS) throw tooLarge();
                        boundedCell(reader.getAttributeValue(null, "r"));
                        inCell = true; cellChars = 0;
                    } else if (part.equals("xl/workbook.xml") && name.equals("sheet") && ++budget.sheets > 16) throw tooLarge();
                    else if (part.equals("xl/styles.xml") && name.equals("xf") && ++budget.styles > 4096) throw tooLarge();
                    else if (part.equals("xl/sharedstrings.xml") && name.equals("si")) {
                        if (++budget.strings > MAX_CELLS) throw tooLarge();
                        inCell = true; cellChars = 0;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    budget.characters += reader.getTextLength();
                    if (budget.characters > 8L * 1024 * 1024) throw tooLarge();
                    if (inCell && (cellChars += reader.getTextLength()) > 32767) throw tooLarge();
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (reader.getLocalName().equals("c") || reader.getLocalName().equals("si")) inCell = false;
                    depth--;
                }
            }
        } finally { reader.close(); }
    }

    private static void boundedRow(String value) {
        if (value != null && (!value.matches("[1-9][0-9]{0,5}") || Integer.parseInt(value) > MAX_ROW_POSITION)) throw tooLarge();
    }

    private static void boundedCell(String reference) {
        if (reference == null) return;
        if (!reference.matches("[A-Z]{1,3}[1-9][0-9]{0,5}")) throw tooLarge();
        int offset = 0, column = 0;
        while (reference.charAt(offset) >= 'A' && reference.charAt(offset) <= 'Z') {
            column = column * 26 + reference.charAt(offset++) - 'A' + 1;
        }
        if (column > MAX_COLUMNS) throw tooLarge();
        boundedRow(reference.substring(offset));
    }

    private static BizException tooLarge() { return new BizException(LIMIT_MESSAGE); }

    private static final class Budget { long expanded, characters; int cells, sheets, styles, strings; }
    private static final class EntryInput extends FilterInputStream {
        private final Budget budget;
        private long bytes;
        private EntryInput(InputStream input, Budget budget) { super(input); this.budget = budget; }
        private int count(int n) {
            if (n > 0 && ((bytes += n) > MAX_ENTRY_BYTES || (budget.expanded += n) > MAX_EXPANDED_BYTES)) throw tooLarge();
            return n;
        }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0) count(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException { return count(in.read(bytes, offset, length)); }
        @Override public void close() { /* The owning ZipInputStream advances to the next entry. */ }
    }
}
