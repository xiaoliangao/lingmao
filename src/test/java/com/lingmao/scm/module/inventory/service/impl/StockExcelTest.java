package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.entity.StockChangeType;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** API 04 §3.6.4–3.6.5：解析实际导出文件，估值事实与日志列表一致。 */
class StockExcelTest {

    @org.junit.jupiter.api.BeforeEach
    void financialExportRole() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new com.lingmao.scm.security.LoginUser(1L, "unit-stock-export"), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("techpack:cost:query"))));
    }
    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    void recordedValue_exportsPriceAmountCurrencyAndFrozenUnit() throws IOException {
        StockLogRowVO log = log("RECORDED", "METER");
        log.setChangeQty(new BigDecimal("2"));
        log.setUnitPrice(new BigDecimal("12.3456"));
        log.setAmount(new BigDecimal("24.69"));
        log.setCurrency("CNY");

        try (XSSFWorkbook workbook = export(log)) {
            Sheet sheet = workbook.getSheet("库存日志");
            assertNumber(cell(sheet, 1, "单价"), "12.3456");
            assertNumber(cell(sheet, 1, "金额"), "24.69");
            assertThat(cell(sheet, 1, "币种").getStringCellValue()).isEqualTo("CNY");
            assertThat(cell(sheet, 1, "估值状态").getStringCellValue()).isEqualTo("已记价");
            assertThat(cell(sheet, 1, "单位").getStringCellValue()).isEqualTo("米");
            assertThat(cell(sheet, 1, "冲销原流水 ID")).isNull();
        }
    }

    @Test
    void unknownValue_exportsBlankPriceAmountAndHistoricalUnit() throws IOException {
        StockLogRowVO log = log("UNKNOWN", null);
        MaterialSkuBrief current = new MaterialSkuBrief();
        current.setStockUnit("METER");
        log.fillFrom(current); // 当前主档不能把旧流水未知单位倒填成米。

        try (XSSFWorkbook workbook = export(log)) {
            Sheet sheet = workbook.getSheet("库存日志");
            assertThat(cell(sheet, 1, "单价")).isNull();
            assertThat(cell(sheet, 1, "金额")).isNull();
            assertThat(cell(sheet, 1, "币种")).isNull();
            assertThat(cell(sheet, 1, "单位")).isNull();
            assertThat(cell(sheet, 1, "估值状态").getStringCellValue()).isEqualTo("估值未知");
            assertThat(cell(sheet, 1, "冲销原流水 ID")).isNull();
        }
    }

    @Test
    void reversal_exportsSignedAmountAndExactReferenceWithoutBackfillingUnknown() throws IOException {
        long originalLogId = 9_007_199_254_740_993L;
        StockLogRowVO reversal = log("RECORDED", "OLD_ROLL");
        reversal.setChangeQty(new BigDecimal("-2"));
        reversal.setUnitPrice(new BigDecimal("12.3456"));
        reversal.setAmount(new BigDecimal("-24.69"));
        reversal.setCurrency("USD");
        reversal.setReversalOfLogId(originalLogId);
        MaterialSkuBrief current = new MaterialSkuBrief();
        current.setStockUnit("METER");
        reversal.fillFrom(current);
        StockLogRowVO unknownReversal = log("UNKNOWN", null);
        unknownReversal.setReversalOfLogId(originalLogId + 1);

        try (XSSFWorkbook workbook = export(reversal, unknownReversal)) {
            Sheet sheet = workbook.getSheet("库存日志");
            assertNumber(cell(sheet, 1, "单价"), "12.3456");
            assertNumber(cell(sheet, 1, "金额"), "-24.69");
            assertThat(cell(sheet, 1, "币种").getStringCellValue()).isEqualTo("USD");
            assertThat(cell(sheet, 1, "单位").getStringCellValue()).isEqualTo("OLD_ROLL");
            Cell reference = cell(sheet, 1, "冲销原流水 ID");
            assertThat(reference.getCellType()).isEqualTo(CellType.STRING);
            assertThat(reference.getStringCellValue()).isEqualTo(Long.toString(originalLogId));
            assertThat(cell(sheet, 2, "单价")).isNull();
            assertThat(cell(sheet, 2, "金额")).isNull();
            assertThat(cell(sheet, 2, "估值状态").getStringCellValue()).isEqualTo("估值未知");
            assertThat(cell(sheet, 2, "冲销原流水 ID").getStringCellValue()).isEqualTo(Long.toString(originalLogId + 1));
        }
    }

    @Test
    void noCostPermission_dropsAllValueColumns() throws IOException {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        StockLogRowVO log = log("RECORDED", "METER");
        log.setUnitPrice(new BigDecimal("12.3456"));
        log.setCurrency("CNY");

        try (XSSFWorkbook workbook = export(log)) {
            Sheet sheet = workbook.getSheet("库存日志");
            List<String> headers = new java.util.ArrayList<>();
            sheet.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
            assertThat(headers).doesNotContain("单价", "金额", "币种", "估值状态").contains("单位", "冲销原流水 ID");
        }
    }

    private static StockLogRowVO log(String valuationStatus, String stockUnit) {
        StockLogRowVO log = new StockLogRowVO();
        log.setChangeType(StockChangeType.OTHER_IN);
        log.setValuationStatus(valuationStatus);
        log.setStockUnit(stockUnit);
        return log;
    }

    private static XSSFWorkbook export(StockLogRowVO... logs) throws IOException {
        DictDataService dictionaries = mock(DictDataService.class);
        when(dictionaries.listByType("unit")).thenReturn(List.of(new DictOption("米", "METER", 1)));
        byte[] bytes = new StockExcel(dictionaries).logs(List.of(logs));
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static Cell cell(Sheet sheet, int rowIndex, String header) {
        assertThat(sheet).isNotNull();
        for (Cell cell : sheet.getRow(0)) {
            if (header.equals(cell.getStringCellValue())) {
                return sheet.getRow(rowIndex).getCell(cell.getColumnIndex(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            }
        }
        throw new AssertionError("导出缺少列：" + header);
    }

    private static void assertNumber(Cell cell, String expected) {
        assertThat(cell).isNotNull();
        assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(new BigDecimal(((XSSFCell) cell).getRawValue())).isEqualByComparingTo(expected);
    }
}
