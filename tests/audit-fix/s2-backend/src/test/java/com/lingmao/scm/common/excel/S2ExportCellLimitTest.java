package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2ExportCellLimitTest {
    private record TextRow(String code, String text) { }
    private static final ExcelSchema<TextRow> SHEET = ExcelSchema.of("字符边界", () -> new TextRow(null, null),
            ExcelColumn.text("编码", TextRow::code, null), ExcelColumn.text("说明", TextRow::text, null));

    @Test void acceptsExactly32767CharactersWithoutTruncatingTheCell() throws Exception {
        String text = text(32_767);
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(Excel.export(SHEET, List.of(new TextRow("X", text)))))) {
            var cell = book.getSheetAt(0).getRow(1).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo(text);
        }
    }

    @Test void rejects32768WithActualSheetRowAndColumnIncludingTheHeader() throws Exception {
        Set<Path> before = temporarySheets();
        assertThatThrownBy(() -> Excel.export(SHEET, List.of(new TextRow("X", "ok"), new TextRow("Y", text(32_768)))))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(1000))
                .hasMessageContaining("工作表「字符边界」第 3 行第 2 列（说明）")
                .hasMessageContaining("32767");
        assertThat(temporarySheets()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test void theFormulaEscapeCountsTowardTheLimit() throws Exception {
        String valid = "=" + text(32_765);
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(Excel.export(SHEET, List.of(new TextRow("X", valid)))))) {
            var cell = book.getSheetAt(0).getRow(1).getCell(1);
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).isEqualTo("'" + valid).hasSize(32_767);
        }
        assertThatThrownBy(() -> Excel.export(SHEET, List.of(new TextRow("X", "=" + text(32_766)))))
                .isInstanceOf(BizException.class).hasMessageContaining("第 2 行第 2 列").hasMessageContaining("32767");
    }

    @Test void headerOverflowIsControlledAndDoesNotEchoTheLargeHeader() {
        var schema = ExcelSchema.of("表头边界", () -> new TextRow(null, null),
                ExcelColumn.text(text(32_768), TextRow::text, null));
        assertThatThrownBy(() -> Excel.export(schema, List.of()))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(1000);
                    assertThat(error.getMessage()).contains("工作表「表头边界」第 1 行第 1 列", "32767");
                    assertThat(error.getMessage()).hasSizeLessThan(180);
                });
    }

    private static String text(int size) {
        Random random = new Random(37);
        StringBuilder value = new StringBuilder(size);
        for (int i = 0; i < size; i++) value.append((char) ('a' + random.nextInt(26)));
        return value.toString();
    }

    private static Set<Path> temporarySheets() throws Exception {
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "poifiles");
        if (!Files.isDirectory(directory)) return Set.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("poi-sxssf"))
                    .collect(Collectors.toSet());
        }
    }
}
