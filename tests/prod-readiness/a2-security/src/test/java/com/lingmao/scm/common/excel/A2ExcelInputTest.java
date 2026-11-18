package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2ExcelInputTest {
    private static final class Item { String name; Integer qty; }
    private static ExcelSchema<Item> schema() {
        return ExcelSchema.of("数据", Item::new,
                ExcelColumn.<Item>text("名称", row -> row.name, (row, value) -> row.name = value).required(),
                ExcelColumn.<Item>integer("数量", row -> row.qty, (row, value) -> row.qty = value));
    }

    @Test void formulasAreRejectedWithExactRowAndColumnBeforeDataCanBeReturned() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("数据");
            Row header = sheet.createRow(0); header.createCell(0).setCellValue("名称"); header.createCell(1).setCellValue("数量");
            for (int i = 0; i < 4; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(List.of("=CMD|' /C example'!A0", " +HYPERLINK(\"test\")", "-SUM(1)", "@SUM(1)").get(i));
                row.createCell(1).setCellValue(3);
            }
            Row formula = sheet.createRow(5); formula.createCell(0).setCellValue("普通名称"); formula.createCell(1).setCellFormula("1+1");
            bytes = bytes(book);
        }
        var read = Excel.read(schema(), file(bytes));
        assertThat(read.errors()).extracting(ImportError::row).containsExactly(2, 3, 4, 5, 6);
        assertThat(read.errors()).extracting(ImportError::column).containsExactly("名称", "名称", "名称", "名称", "数量");
        assertThatThrownBy(read::dataOrThrow).isInstanceOf(BizException.class).hasMessageContaining("一行都没有导入");
    }

    @Test void explicitlyEscapedLiteralAndTypedNumericValueKeepTheirValues() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(Excel.template(schema())))) {
            Row row = book.getSheet("数据").createRow(1);
            row.createCell(0).setCellValue("'+8613800000000"); row.createCell(1).setCellValue(-7);
            Item result = Excel.read(schema(), file(bytes(book))).dataOrThrow().getFirst();
            assertThat(result.name).isEqualTo("+8613800000000");
            assertThat(result.qty).isEqualTo(-7); // Domain columns still own positive/nonnegative numeric rules.
        }
    }

    @Test void bothExportPathsEscapeFormulaPrefixesAndRoundTripAsLiteralText() throws IOException {
        List<String> names = List.of("=CMD|' /C example'!A0", "+8613800000000", "-label", "@tag",
                " \t=HYPERLINK(\"example\")", "\u00a0+phone", "普通名称");
        List<Item> items = names.stream().map(name -> {
            Item item = new Item(); item.name = name; item.qty = -7; return item;
        }).toList();
        var streamed = new ByteArrayOutputStream();
        assertThat(StreamingExcel.write(schema(), streamed, sink -> {
            for (Item item : items) { sink.row(item); sink.flush(); }
        })).isEqualTo(items.size());
        for (byte[] bytes : List.of(Excel.export(schema(), items), streamed.toByteArray())) {
            try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                for (int i = 0; i < items.size(); i++) {
                    Row row = book.getSheetAt(0).getRow(i + 1);
                    assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
                    assertThat(row.getCell(0).getStringCellValue())
                            .isEqualTo(i < items.size() - 1 ? "'" + names.get(i) : names.get(i));
                    assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
                    assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(-7);
                }
            }
            List<Item> imported = Excel.read(schema(), file(bytes)).dataOrThrow();
            assertThat(imported).extracting(item -> item.name)
                    .containsExactlyElementsOf(names.stream().map(name -> name.replace('\u00a0', ' ').strip()).toList());
            assertThat(imported).allSatisfy(item -> assertThat(item.qty).isEqualTo(-7));
        }
    }

    @Test void sparseFarAwayRowsAndExcessColumnsFailBeforeWorkbookAllocation() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("数据"); sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.createRow(1_000_000).createCell(0).setCellValue("sparse");
            assertThatThrownBy(() -> Excel.open(file(bytes(book)))).isInstanceOf(BizException.class).hasMessageContaining("文件结构过大");
        }
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            book.createSheet("数据").createRow(0).createCell(128).setCellValue("wide");
            assertThatThrownBy(() -> Excel.open(file(bytes(book)))).isInstanceOf(BizException.class).hasMessageContaining("文件结构过大");
        }
    }

    @Test void archiveEntryAggregateSizeAndEntryCountAreBoundedEvenForUnusedParts() throws IOException {
        Map<String, byte[]> parts = parts(Excel.template(schema()));
        parts.put("unused/large.bin", new byte[(int) ExcelArchiveGuard.MAX_ENTRY_BYTES + 1]);
        byte[] largeEntry = zip(parts);
        assertThatThrownBy(() -> Excel.open(file(largeEntry))).isInstanceOf(BizException.class).hasMessageContaining("文件结构过大");
        parts = parts(Excel.template(schema()));
        byte[] halfMegabyte = new byte[512 * 1024];
        for (int i = 0; i < 65; i++) parts.put("unused/" + i + ".bin", halfMegabyte);
        byte[] aggregate = zip(parts);
        assertThat(aggregate.length).isLessThan((int) Excel.MAX_IMPORT_BYTES);
        assertThatThrownBy(() -> Excel.open(file(aggregate))).isInstanceOf(BizException.class).hasMessageContaining("文件结构过大");
        parts = parts(Excel.template(schema()));
        for (int i = 0; i < 129; i++) parts.put("unused/" + i + ".bin", new byte[0]);
        byte[] tooMany = zip(parts);
        assertThatThrownBy(() -> Excel.open(file(tooMany))).isInstanceOf(BizException.class).hasMessageContaining("文件结构过大");
    }

    @Test void traversalExternalWorkbookAndXmlEntitiesAreRejected() throws IOException {
        for (String name : List.of("../outside.bin", "xl/externalLinks/externalLink1.xml", "xl/vbaProject.bin")) {
            Map<String, byte[]> parts = parts(Excel.template(schema())); parts.put(name, new byte[0]);
            byte[] hostile = zip(parts);
            assertThatThrownBy(() -> Excel.open(file(hostile))).isInstanceOf(BizException.class);
        }
        Map<String, byte[]> parts = parts(Excel.template(schema()));
        parts.put("unused/entity.xml", "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///not-opened'>]><a>&x;</a>".getBytes(StandardCharsets.UTF_8));
        byte[] hostile = zip(parts);
        assertThatThrownBy(() -> Excel.open(file(hostile))).isInstanceOf(BizException.class).hasMessageContaining("DTD");
    }

    @Test void actualBytesAreLimitedEvenWhenCallerMetadataUnderstatesLength() {
        assertThatThrownBy(() -> ExcelReader.open(new ByteArrayInputStream(new byte[(int) Excel.MAX_IMPORT_BYTES + 1])))
                .isInstanceOf(BizException.class).hasMessageContaining("超过 10MB");
    }

    private static MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "合成测试.xlsx", Excel.CONTENT_TYPE, bytes); }
    private static byte[] bytes(XSSFWorkbook book) throws IOException { var out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray(); }
    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) parts.put(entry.getName(), zip.readAllBytes());
        }
        return parts;
    }
    private static byte[] zip(Map<String, byte[]> parts) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : parts.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
}
