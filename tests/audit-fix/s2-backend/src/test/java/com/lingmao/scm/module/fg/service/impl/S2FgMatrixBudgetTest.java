package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.fg.dto.FgStockByStyleRowVO;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S2FgMatrixBudgetTest {
    @Test void exactExcelColumnBoundaryIncludesAllFourFixedColumns() {
        assertThatNoException().isThrownBy(() -> FgExcel.checkMatrixBudget(0, 16_380));
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(0, 16_381))
                .isInstanceOf(BizException.class).hasMessageContaining("16384");
    }
    @Test void exactCellBudgetIncludesHeaderAndDoesNotWrap() {
        assertThatNoException().isThrownBy(() -> FgExcel.checkMatrixBudget(49_999, 96));
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(50_000, 96))
                .isInstanceOf(BizException.class).hasMessageContaining("5000000");
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(Long.MAX_VALUE, 1)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(1, Long.MAX_VALUE)).isInstanceOf(BizException.class);
    }
    @Test void rowBudgetIsIndependentOfWidth() {
        assertThatNoException().isThrownBy(() -> FgExcel.checkMatrixBudget(50_000, 1));
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(50_001, 1))
                .isInstanceOf(BizException.class).hasMessageContaining("50000");
    }
    @Test void negativeDimensionsAreNotABypass() {
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(-1, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FgExcel.checkMatrixBudget(1, -2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalMatrixKeepsSizeOrderAndLongQuantityWithoutIntOverflow() throws Exception {
        var color = new FgStyleOptionVO.Color(9L, "BK", "Black", null);
        var sku = new FgStyleOptionVO.Sku(12L, 9L, "M", "ST-BK-M", 1);
        var row = new FgStockByStyleRowVO(1L, 2L, "ST", "Style", null, List.of("S", "M"), List.of(color), List.of(sku),
                3_000_000_000L, Map.of("12", 3_000_000_000L));
        byte[] result = new FgExcel(null).exportByStyle(List.of(row));
        try (var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(result))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("S");
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("M");
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(3_000_000_000d);
            assertThat(sheet.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(3_000_000_000d);
        }
    }
}
