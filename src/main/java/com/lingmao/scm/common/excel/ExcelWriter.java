package com.lingmao.scm.common.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** 写导入模板（RFC 001 §9.2）；业务数据导出由 StreamingExcel 逐行写出。 */
final class ExcelWriter {

    private static final String[] GUIDE_HEADERS = {"列", "必填", "怎么填", "例子"};
    private static final int[] GUIDE_WIDTHS = {18, 8, 64, 24};
    private static final int GUIDE_SHEET_WIDTH = 16;

    private ExcelWriter() {
    }

    /**
     * 模板：数据表只有表头（必填的带 *），不放示例行（用户忘了删就被当成数据导进去了）；
     * 编码列整列设成文本格式，日期列设成日期格式，选项列带下拉。数据表后面是"填写说明"：每一列怎么填、例子。
     * 下拉的选项写在一个隐藏的工作表"选项"里：直接写进下拉的话总长不能超过 255 个字，字典一长就超。
     *
     * @param single 只有一张数据表：表名叫"数据"；几张表的，表名用各自列定义的 name
     */
    static byte[] template(List<ExcelSchema<?>> schemas, boolean single) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            List<Sheet> dataSheets = new ArrayList<>();
            for (ExcelSchema<?> schema : schemas) {
                dataSheets.add(workbook.createSheet(single ? Excel.DATA_SHEET : schema.name()));
            }
            Sheet guide = workbook.createSheet(Excel.GUIDE_SHEET);
            // "选项"页用到时才建，排在最后
            Options options = new Options(workbook);
            for (int i = 0; i < schemas.size(); i++) {
                writeTemplateSheet(dataSheets.get(i), schemas.get(i), styles, options);
            }
            writeGuide(guide, schemas, single, styles);
            if (options.sheet != null) {
                workbook.setSheetHidden(workbook.getSheetIndex(options.sheet), true);
            }
            workbook.setActiveSheet(0);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("生成导入模板失败: " + schemas.getFirst().name(), e);
        }
    }

    private static <R> void writeTemplateSheet(Sheet data, ExcelSchema<R> schema, Styles styles, Options options) {
        List<ExcelColumn<R, ?>> columns = schema.importColumns();
        Row header = data.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            ExcelColumn<R, ?> column = columns.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(column.templateHeader());
            cell.setCellStyle(column.isRequired() ? styles.requiredHeader : styles.header);
            data.setColumnWidth(i, width(column.templateHeader()));
            CellStyle columnStyle = styles.forColumn(column);
            if (columnStyle != null) {
                data.setDefaultColumnStyle(i, columnStyle);
            }
            List<String> labels = column.kind() == ExcelColumn.Kind.CHOICE ? column.enabledLabels() : List.of();
            if (!labels.isEmpty()) {
                addDropdown(data, i, options.add(labels));
            }
        }
        data.createFreezePane(0, 1);
    }

    /** 选项列的下拉：只能从下拉里选，填别的 Excel 当场提示（复制粘贴进来的拦不住，导入时还会再查一遍） */
    private static void addDropdown(Sheet sheet, int column, String rangeFormula) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(rangeFormula);
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(1, Excel.MAX_IMPORT_ROWS, column, column));
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("不在选项里", "请从下拉里选");
        sheet.addValidationData(validation);
    }

    /** "填写说明"：每一列一行（几张表的，第一列写是哪张表），最后几行是通用的规则（和导入对话框里的提示一致） */
    private static void writeGuide(Sheet guide, List<ExcelSchema<?>> schemas, boolean single, Styles styles) {
        int offset = single ? 0 : 1;
        Row header = guide.createRow(0);
        if (!single) {
            Cell cell = header.createCell(0);
            cell.setCellValue("工作表");
            cell.setCellStyle(styles.header);
            guide.setColumnWidth(0, GUIDE_SHEET_WIDTH * 256);
        }
        for (int i = 0; i < GUIDE_HEADERS.length; i++) {
            Cell cell = header.createCell(i + offset);
            cell.setCellValue(GUIDE_HEADERS[i]);
            cell.setCellStyle(styles.header);
            guide.setColumnWidth(i + offset, GUIDE_WIDTHS[i] * 256);
        }
        int r = 1;
        for (ExcelSchema<?> schema : schemas) {
            for (ExcelColumn<?, ?> column : schema.importColumns()) {
                Row row = guide.createRow(r++);
                if (!single) {
                    row.createCell(0).setCellValue(schema.name());
                }
                row.createCell(offset).setCellValue(column.header());
                row.createCell(offset + 1).setCellValue(column.isRequired() ? "必填" : "");
                Cell how = row.createCell(offset + 2);
                how.setCellValue(column.describe());
                how.setCellStyle(styles.wrap);
                Cell example = row.createCell(offset + 3);
                example.setCellValue(column.example() == null ? "" : column.example());
                example.setCellStyle(styles.text);
            }
        }
        r++;
        List<String> rules = single
                ? List.of("在「" + Excel.DATA_SHEET + "」这一页从第 2 行开始填，一行一条。",
                        "带 * 的列必填。表头别改名；列的顺序可以调，用不上的非必填列可以整列删掉。",
                        "一个文件最多 " + Excel.MAX_IMPORT_ROWS + " 行。有一处不对整批都不导入，系统会列出每一处问题（第几行、哪一列），改好再导一次。")
                : List.of("每张表都从第 2 行开始填，一行一条。工作表别改名。",
                        "带 * 的列必填。表头别改名；列的顺序可以调，用不上的非必填列可以整列删掉。",
                        "一张表最多 " + Excel.MAX_IMPORT_ROWS + " 行。有一处不对整批都不导入，系统会列出每一处问题（哪张表、第几行、哪一列），改好再导一次。");
        for (String rule : rules) {
            guide.createRow(r++).createCell(0).setCellValue(rule);
        }
    }

    /** 列宽按表头的字数：汉字算两个字符宽，最窄 10、最宽 40 个字符 */
    private static int width(String header) {
        int units = header.codePoints().map(c -> c < 0x80 ? 1 : 2).sum();
        return Math.clamp(units + 4L, 10, 40) * 256;
    }

    private static byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    /** 隐藏的"选项"页：每个选项列占一列，用到时才建 */
    private static final class Options {
        private final Workbook workbook;
        Sheet sheet;
        private int columns;

        Options(Workbook workbook) {
            this.workbook = workbook;
        }

        /** 把一组选项写进新的一列，返回下拉要引用的区域，如 '选项'!$A$1:$A$5 */
        String add(List<String> labels) {
            if (sheet == null) {
                sheet = workbook.createSheet(Excel.OPTIONS_SHEET);
            }
            String letter = CellReference.convertNumToColString(columns);
            for (int j = 0; j < labels.size(); j++) {
                Row row = sheet.getRow(j) == null ? sheet.createRow(j) : sheet.getRow(j);
                row.createCell(columns).setCellValue(labels.get(j));
            }
            columns++;
            return "'" + Excel.OPTIONS_SHEET + "'!$" + letter + "$1:$" + letter + "$" + labels.size();
        }
    }

    /** 一个工作簿里用到的样式：样式属于工作簿，每个工作簿建一次（每个单元格新建一个的话，Excel 会报样式太多） */
    private static final class Styles {
        final CellStyle header;
        final CellStyle requiredHeader;
        final CellStyle text;
        final CellStyle date;
        final CellStyle wrap;

        Styles(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            Font boldRed = workbook.createFont();
            boldRed.setBold(true);
            boldRed.setColor(IndexedColors.DARK_RED.getIndex());
            header = headerStyle(workbook, bold);
            requiredHeader = headerStyle(workbook, boldRed);
            text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            wrap = workbook.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
        }

        /** 模板里整列的格式：编码列是文本，日期列是日期，别的不设 */
        CellStyle forColumn(ExcelColumn<?, ?> column) {
            if (column.isCode()) {
                return text;
            }
            return column.kind() == ExcelColumn.Kind.DATE ? date : null;
        }

        private static CellStyle headerStyle(Workbook workbook, Font font) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            return style;
        }
    }
}
