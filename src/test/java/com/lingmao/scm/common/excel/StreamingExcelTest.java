package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class StreamingExcelTest {
    private record Row(String code, LocalDate date, BigDecimal amount) { }
    private static ExcelSchema<Row> schema(String name) {
        return ExcelSchema.of(name, () -> new Row(null, null, null),
                ExcelColumn.text("编码", Row::code, null).code(), ExcelColumn.date("日期", Row::date, null),
                ExcelColumn.decimal("金额", 2, Row::amount, null));
    }
    @Test void twoSheetsRoundTripTypesEscapingAndRestartTheirOwnProducers() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        var plan = Excel.exportBook().sheet(schema("第一页"), sink -> {
            runs.incrementAndGet(); sink.flush();
            sink.row(new Row("001234567890123456789", LocalDate.of(2026, 11, 24), new BigDecimal("12.34")));
        }).sheet(schema("第二页"), sink -> { runs.incrementAndGet(); sink.flush(); sink.row(new Row("=danger", null, null)); });
        plan.validate(); assertThat(runs).hasValue(2);
        var output = new ByteArrayOutputStream(); plan.writeTo(output); assertThat(runs).hasValue(4);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("001234567890123456789");
            assertThat(row.getCell(1).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(2).getNumericCellValue()).isEqualTo(12.34);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=danger");
        }
    }
    @Test void textRoundTripPreservesXmlControlsAndLiteralOoxmlEscapeSequences() throws Exception {
        String text = "literal _x0041_ _x005F_x0041_ & < > \" ' \u0000 \u0001 \u000b \r\n\t 😀";
        byte[] bytes = Excel.export(schema("文本"), List.of(new Row(text, null, null)));
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo(text);
        }
    }
    @Test void longTextRoundTripPreservesSurrogatesAndEscapesAcrossCharacterBufferBoundaries() throws Exception {
        String beforeText = "<row r=\"2\"><c r=\"A2\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">";
        String edge = "😀 & < > \" ' _x0041_ _x005F_x0041_ \r\n\t\u0000\u0001\u000b 尾😀";
        for (int boundary : new int[]{8192, 16384}) {
            // Flush the header first, then place the emoji's high surrogate in the buffer's last character slot.
            // Its low surrogate and the XML/ST_Xstring escapes immediately follow in the next buffer.
            String text = "a".repeat(boundary - beforeText.length() - 1) + edge;
            assertThat(text.length()).isLessThanOrEqualTo(32_767);
            byte[] bytes = Excel.exportBook().sheet(schema("长文本"), sink -> {
                sink.flush(); sink.row(new Row(text, null, null)); sink.flush();
            }).toBytes();
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                String actual = workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue();
                assertThat(actual.toCharArray()).as("UTF-16 text across character boundary %s", boundary)
                        .containsExactly(text.toCharArray());
            }
        }
    }

    @Test void failureDoesNotFetchLaterPagesOrFinishATruncatedWorkbook() throws Exception {
        AtomicInteger fetched = new AtomicInteger(), laterSheet = new AtomicInteger();
        class BrokenOutput extends ByteArrayOutputStream {
            boolean failed, closed;
            @Override public void flush() throws IOException { if (failed) throw new IOException("disconnected"); }
            @Override public void close() { closed = true; }
        }
        var output = new BrokenOutput();
        var plan = Excel.exportBook().sheet(schema("数据"), sink -> {
            fetched.incrementAndGet(); sink.row(new Row("one", null, null));
            output.failed = true; sink.flush(); fetched.incrementAndGet();
        }).sheet(schema("不应读取"), sink -> laterSheet.incrementAndGet());
        assertThatThrownBy(() -> plan.writeTo(output)).isInstanceOf(IOException.class);
        assertThat(fetched).hasValue(1); assertThat(laterSheet).hasValue(0); assertThat(output.closed).isFalse();
        byte[] bytes = output.toByteArray();
        for (int i = 0; i + 3 < bytes.length; i++)
            assertThat(bytes[i] == 0x50 && bytes[i+1] == 0x4b && bytes[i+2] == 0x05 && bytes[i+3] == 0x06)
                    .as("failed output has no ZIP end-of-central-directory").isFalse();
    }
    @Test void dataFreeFlushStillWritesHeartbeatBeforeAnotherDatabaseBatch() throws Exception {
        var output = new ByteArrayOutputStream(); AtomicInteger previous = new AtomicInteger();
        StreamingExcel.write(schema("数据"), output, sink -> {
            sink.flush(); previous.set(output.size());
            sink.flush(); assertThat(output.size()).isGreaterThan(previous.get());
        });
    }
    @Test void preflightRejectsOversizedCellsAndResourceBudgetWithoutBuildingAnArchive() {
        var text = ExcelSchema.of("内容", () -> "", ExcelColumn.<String>text("说明", value -> value, null));
        assertThatThrownBy(() -> Excel.exportBook().sheet(text, sink -> sink.row("x".repeat(32_768))).validate())
                .isInstanceOf(BizException.class).hasMessageContaining("第 2 行第 1 列", "32767");
        String large = "x".repeat(32_000);
        assertThatThrownBy(() -> Excel.exportBook().sheet(text, sink -> {
            for (int i = 0; i < 2_000; i++) sink.row(large);
        }).validate()).isInstanceOf(BizException.class).hasMessageContaining("250MB");
    }
    @Test void rejectsNamesThatCollideAfterExcelSanitizingBeforeReadingAnyRows() {
        AtomicInteger calls = new AtomicInteger();
        var plan = Excel.exportBook().sheet(schema("one/two"), sink -> calls.incrementAndGet())
                .sheet(schema("one?two"), sink -> calls.incrementAndGet());
        assertThatThrownBy(plan::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("工作表重名");
        assertThat(calls).hasValue(0);
    }
}
