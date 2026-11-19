package com.lingmao.scm.common.excel;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExcelPreviewTest {

    /**
     * 规则（RFC 011 §2.3）：工艺单在页面里看要和 Excel 里差不多：格子的字按 Excel 的显示格式出（12.5 设成两位小数是 12.50），
     * 合并单元格、粗体、底色带出来，没字但有底色的格子也要；图片按锚点算出位置，内容是 data URI；隐藏的 sheet 标出来。
     * 用 .xls（甲方的工艺单大多是这个格式）
     */
    @Test
    void hssf_cellsStylesMergesAndImages() throws Exception {
        try (Workbook wb = new HSSFWorkbook()) {
            Sheet sheet = wb.createSheet("工艺");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("缝制要求");
            title.getCell(0).setCellStyle(bold);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            CellStyle twoDecimals = wb.createCellStyle();
            twoDecimals.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            sheet.createRow(2).createCell(1).setCellValue(12.5);
            sheet.getRow(2).getCell(1).setCellStyle(twoDecimals);
            CellStyle yellow = wb.createCellStyle();
            yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            sheet.createRow(4).createCell(3).setCellStyle(yellow);

            ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
            anchor.setCol1(1);
            anchor.setRow1(1);
            anchor.setCol2(3);
            anchor.setRow2(4);
            sheet.createDrawingPatriarch().createPicture(anchor, wb.addPicture(png(), Workbook.PICTURE_TYPE_PNG));
            wb.createSheet("隐藏");
            wb.setSheetHidden(1, true);

            ExcelPreview.Book book = ExcelPreview.of(wb);

            assertThat(book.sheets()).extracting(ExcelPreview.SheetView::hidden).containsExactly(false, true);
            ExcelPreview.SheetView view = book.sheets().getFirst();
            assertThat(view.merges()).containsExactly(new ExcelPreview.Merge(0, 0, 1, 3));
            assertThat(view.cells()).extracting(ExcelPreview.CellView::v).containsExactly("缝制要求", "12.50", "");
            ExcelPreview.CellView head = view.cells().getFirst();
            assertThat(view.styles().get(head.s()).b()).isTrue();
            ExcelPreview.CellView filled = view.cells().get(2);
            assertThat(view.styles().get(filled.s()).bg()).isEqualTo("#ffff00");
            assertThat(view.images()).singleElement().satisfies(img -> {
                assertThat(img.data()).startsWith("data:image/png;base64,");
                assertThat(img.x()).isEqualTo(view.colWidths().getFirst());
                assertThat(img.y()).isEqualTo(view.rowHeights().getFirst());
                assertThat(img.w()).isEqualTo(view.colWidths().get(1) + view.colWidths().get(2));
            });
        }
    }

    /** 规则：只读前 500 行、60 列，超出的数出来（页面上提示"还有 N 行没显示，下载看"） */
    @Test
    void xssf_limitsRowsAndColumns() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("大表");
            sheet.createRow(0).createCell(0).setCellValue("左上");
            sheet.createRow(599).createCell(69).setCellValue("右下");

            ExcelPreview.SheetView view = ExcelPreview.of(wb).sheets().getFirst();

            assertThat(view.rowHeights()).hasSize(ExcelPreview.MAX_ROWS);
            assertThat(view.colWidths()).hasSize(ExcelPreview.MAX_COLS);
            assertThat(view.moreRows()).isEqualTo(100);
            assertThat(view.moreCols()).isEqualTo(10);
            assertThat(view.cells()).extracting(ExcelPreview.CellView::v).containsExactly("左上");
        }
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
