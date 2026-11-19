package com.lingmao.scm.common.excel;

import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFFont;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一份 Excel 读成能在页面上画的表格数据（资料包工艺单预览，RFC 011 §2.3）：每个 sheet 的单元格文字、合并单元格、列宽行高、
 * 样式（粗体、斜体、字号、字色、底色、对齐、换行、细边框）、嵌在表里的图片。
 * <ul>
 *   <li>返回的是数据不是 HTML：前端一律当文本画，表里有什么都不会执行。</li>
 *   <li>公式取 Excel 存下的计算结果，不在服务器上算。</li>
 *   <li>上限：{@link #MAX_SHEETS} 个 sheet、每个 {@link #MAX_ROWS} 行 × {@link #MAX_COLS} 列、图片合计 {@link #MAX_IMAGE_BYTES}；
 *       超出的不画，记在 moreRows、moreCols、skippedImages 里，页面提示下载看全部。</li>
 *   <li>隐藏的行、列照样出（宽高是 0），合并单元格的坐标才对得上。</li>
 * </ul>
 * 宽高都是像素（按 96 dpi）；字号是磅。
 */
public final class ExcelPreview {

    static final int MAX_SHEETS = 20;
    static final int MAX_ROWS = 500;
    static final int MAX_COLS = 60;
    static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif");

    /** 一份文件：sheets 按 Excel 里的顺序；moreSheets 是超出上限没读的 sheet 数 */
    public record Book(List<SheetView> sheets, int moreSheets) {
    }

    /**
     * 一个 sheet。cells 只有有字、或者有底色边框的格子；styles 是去重后的样式表，格子用下标引用。
     * hidden：整张 sheet 在 Excel 里是隐藏的
     */
    public record SheetView(String name, boolean hidden, List<Integer> colWidths, List<Integer> rowHeights, List<CellView> cells,
                            List<StyleView> styles, List<Merge> merges, List<ImageView> images,
                            int moreRows, int moreCols, int skippedImages) {
    }

    /** 一个格子：行、列从 0 起；s 是 styles 的下标 */
    public record CellView(int r, int c, String v, Integer s) {
    }

    /** 合并单元格：左上角 r、c，占 rs 行 cs 列（已经裁到上限以内） */
    public record Merge(int r, int c, int rs, int cs) {
    }

    /**
     * 样式。fs 字号（磅）总有；其余只出和默认不一样的项，一样的是 null：b 粗体、i 斜体、fc 字色、bg 底色（#rrggbb）、
     * ha 水平对齐（left、center、right）、va 垂直对齐（top、middle、bottom）、wrap 自动换行、bt/br/bb/bl 四边有没有线
     */
    public record StyleView(Boolean b, Boolean i, Double fs, String fc, String bg, String ha, String va, Boolean wrap,
                            Boolean bt, Boolean br, Boolean bb, Boolean bl) {
        /** 有底色或边框：没字也要画出来 */
        boolean boxed() {
            return bg != null || bt != null || br != null || bb != null || bl != null;
        }
    }

    /** 一张图：左上角相对表格左上角的 x、y 和宽高（像素），data 是 data:image/...;base64,... */
    public record ImageView(int x, int y, int w, int h, String data) {
    }

    private ExcelPreview() {
    }

    public static Book of(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        formatter.setUseCachedValuesForFormulaCells(true);
        List<SheetView> sheets = new ArrayList<>();
        long[] imageBudget = {MAX_IMAGE_BYTES};
        int count = workbook.getNumberOfSheets();
        for (int i = 0; i < Math.min(count, MAX_SHEETS); i++) {
            sheets.add(sheet(workbook, i, formatter, imageBudget));
        }
        return new Book(sheets, Math.max(0, count - MAX_SHEETS));
    }

    private static SheetView sheet(Workbook workbook, int index, DataFormatter formatter, long[] imageBudget) {
        Sheet sheet = workbook.getSheetAt(index);
        int lastRow = sheet.getLastRowNum();          // 空表是 -1
        int lastCol = -1;
        for (Row row : sheet) {
            lastCol = Math.max(lastCol, row.getLastCellNum() - 1);
        }
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            lastRow = Math.max(lastRow, m.getLastRow());
            lastCol = Math.max(lastCol, m.getLastColumn());
        }
        int rows = Math.min(lastRow + 1, MAX_ROWS);
        int cols = Math.min(lastCol + 1, MAX_COLS);

        List<Integer> colWidths = new ArrayList<>(cols);
        for (int c = 0; c < cols; c++) {
            colWidths.add(sheet.isColumnHidden(c) ? 0 : Math.round(sheet.getColumnWidthInPixels(c)));
        }
        List<Integer> rowHeights = new ArrayList<>(rows);
        float defaultHeight = sheet.getDefaultRowHeightInPoints();
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            boolean hidden = row != null && row.getZeroHeight();
            rowHeights.add(hidden ? 0 : px(row == null ? defaultHeight : row.getHeightInPoints()));
        }

        Map<Integer, Integer> styleIndex = new HashMap<>();   // CellStyle.getIndex() → styles 的下标；-1 = 默认样式
        List<StyleView> styles = new ArrayList<>();
        List<CellView> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < Math.min(cols, Math.max(0, (int) row.getLastCellNum())); c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                String text = formatter.formatCellValue(cell);
                Integer s = styleOf(workbook, cell.getCellStyle(), styleIndex, styles);
                // 没字的格子只有底色、边框看得出来
                if (!text.isEmpty() || s != null && styles.get(s).boxed()) {
                    cells.add(new CellView(r, c, text, s));
                }
            }
        }

        List<Merge> merges = new ArrayList<>();
        for (CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.getFirstRow() >= rows || m.getFirstColumn() >= cols) {
                continue;
            }
            int rs = Math.min(m.getLastRow(), rows - 1) - m.getFirstRow() + 1;
            int cs = Math.min(m.getLastColumn(), cols - 1) - m.getFirstColumn() + 1;
            if (rs > 1 || cs > 1) {
                merges.add(new Merge(m.getFirstRow(), m.getFirstColumn(), rs, cs));
            }
        }

        List<ImageView> images = new ArrayList<>();
        int skipped = images(sheet, colWidths, rowHeights, images, imageBudget);
        return new SheetView(sheet.getSheetName(), workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index),
                colWidths, rowHeights, cells, styles, merges, images,
                Math.max(0, lastRow + 1 - rows), Math.max(0, lastCol + 1 - cols), skipped);
    }

    /** 读图片，返回没画的张数（超出范围、超出预算、不是 png/jpeg/gif 的） */
    private static int images(Sheet sheet, List<Integer> colWidths, List<Integer> rowHeights, List<ImageView> out, long[] budget) {
        Drawing<?> drawing = sheet.getDrawingPatriarch();
        if (drawing == null) {
            return 0;
        }
        int skipped = 0;
        for (Shape shape : drawing) {
            if (!(shape instanceof Picture picture)) {
                continue;
            }
            ClientAnchor a;
            PictureData data;
            try {
                a = picture.getClientAnchor();
                data = picture.getPictureData();
            } catch (RuntimeException broken) {
                skipped++;
                continue;
            }
            if (a == null || data == null || !IMAGE_TYPES.contains(data.getMimeType())
                    || a.getRow1() >= rowHeights.size() || a.getCol1() >= colWidths.size()) {
                skipped++;
                continue;
            }
            byte[] bytes = data.getData();
            if (bytes.length > budget[0]) {
                skipped++;
                continue;
            }
            int x1 = offset(colWidths, a.getCol1()) + dx(a, a.getDx1(), colWidths, a.getCol1());
            int y1 = offset(rowHeights, a.getRow1()) + dy(a, a.getDy1(), rowHeights, a.getRow1());
            int w, h;
            if (a.getCol2() >= a.getCol1() && a.getRow2() >= a.getRow1() && (a.getCol2() > a.getCol1() || a.getRow2() > a.getRow1()
                    || a.getDx2() > a.getDx1() || a.getDy2() > a.getDy1())) {
                w = offset(colWidths, a.getCol2()) + dx(a, a.getDx2(), colWidths, a.getCol2()) - x1;
                h = offset(rowHeights, a.getRow2()) + dy(a, a.getDy2(), rowHeights, a.getRow2()) - y1;
            } else {
                // 只锚了左上角的（一格锚点）：按图片原来的像素大小
                var size = picture.getImageDimension();
                w = size.width;
                h = size.height;
            }
            if (w <= 0 || h <= 0) {
                skipped++;
                continue;
            }
            budget[0] -= bytes.length;
            out.add(new ImageView(x1, y1, w, h, "data:" + data.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes)));
        }
        return skipped;
    }

    /** 第 index 列（行）左边（上边）离表格左上角多少像素；超出上限的按最后一格的大小往外推 */
    private static int offset(List<Integer> sizes, int index) {
        int sum = 0;
        for (int i = 0; i < index; i++) {
            sum += i < sizes.size() ? sizes.get(i) : (sizes.isEmpty() ? 64 : sizes.getLast());
        }
        return sum;
    }

    /** 锚点在格子里的横向偏移：xls 是列宽的 1/1024，xlsx 是 EMU */
    private static int dx(ClientAnchor a, int dx, List<Integer> widths, int col) {
        if (a instanceof HSSFClientAnchor) {
            int width = col < widths.size() ? widths.get(col) : 64;
            return Math.round(width * Math.min(dx, 1023) / 1024f);
        }
        return Math.round((float) dx / Units.EMU_PER_PIXEL);
    }

    /** 纵向偏移：xls 是行高的 1/256，xlsx 是 EMU */
    private static int dy(ClientAnchor a, int dy, List<Integer> heights, int row) {
        if (a instanceof HSSFClientAnchor) {
            int height = row < heights.size() ? heights.get(row) : 20;
            return Math.round(height * Math.min(dy, 255) / 256f);
        }
        return Math.round((float) dy / Units.EMU_PER_PIXEL);
    }

    private static int px(float points) {
        return Math.round(points * 96 / 72);
    }

    /** 样式去重：同一个 CellStyle 只算一次，返回 styles 的下标 */
    private static Integer styleOf(Workbook workbook, CellStyle style, Map<Integer, Integer> index, List<StyleView> styles) {
        if (style == null) {
            return null;
        }
        int key = style.getIndex();
        if (index.containsKey(key)) {
            return index.get(key);
        }
        Font font = workbook.getFontAt(style.getFontIndex());
        String bg = style.getFillPattern() == FillPatternType.SOLID_FOREGROUND ? hex(style.getFillForegroundColorColor()) : null;
        String fc = fontColor(workbook, font);
        StyleView view = new StyleView(
                font.getBold() ? Boolean.TRUE : null,
                font.getItalic() ? Boolean.TRUE : null,
                (double) font.getFontHeightInPoints(),
                "#000000".equals(fc) ? null : fc,
                "#ffffff".equals(bg) ? null : bg,
                horizontal(style.getAlignment()),
                vertical(style.getVerticalAlignment()),
                style.getWrapText() ? Boolean.TRUE : null,
                line(style.getBorderTop()), line(style.getBorderRight()), line(style.getBorderBottom()), line(style.getBorderLeft()));
        int i = styles.indexOf(view);
        if (i < 0) {
            i = styles.size();
            styles.add(view);
        }
        index.put(key, i);
        return i;
    }

    private static String fontColor(Workbook workbook, Font font) {
        if (font instanceof XSSFFont x) {
            return hex(x.getXSSFColor());
        }
        if (font instanceof HSSFFont h && workbook instanceof HSSFWorkbook book) {
            return hex(h.getHSSFColor(book));
        }
        return null;
    }

    /** POI 的颜色 → #rrggbb；自动色、取不到的是 null */
    static String hex(Color color) {
        if (color instanceof XSSFColor x) {
            if (x.isAuto()) {
                return null;
            }
            byte[] rgb = x.getRGBWithTint();
            if (rgb == null) {
                rgb = x.getRGB();
            }
            return rgb == null || rgb.length < 3 ? null
                    : String.format("#%02x%02x%02x", rgb[rgb.length - 3] & 255, rgb[rgb.length - 2] & 255, rgb[rgb.length - 1] & 255);
        }
        if (color instanceof HSSFColor h) {
            if (h.getIndex() == HSSFColor.HSSFColorPredefined.AUTOMATIC.getIndex()) {
                return null;
            }
            short[] t = h.getTriplet();
            return t == null || t.length < 3 ? null : String.format("#%02x%02x%02x", t[0], t[1], t[2]);
        }
        return null;
    }

    private static String horizontal(HorizontalAlignment a) {
        return switch (a) {
            case CENTER, CENTER_SELECTION -> "center";
            case RIGHT -> "right";
            case JUSTIFY, DISTRIBUTED, FILL -> "left";
            default -> null;
        };
    }

    private static String vertical(VerticalAlignment a) {
        return switch (a) {
            case TOP -> "top";
            case CENTER, JUSTIFY, DISTRIBUTED -> "middle";
            default -> null;
        };
    }

    private static Boolean line(BorderStyle border) {
        return border == null || border == BorderStyle.NONE ? null : Boolean.TRUE;
    }
}
