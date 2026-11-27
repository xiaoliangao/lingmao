package com.lingmao.scm.module.settle.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读对方发来的 Excel 对账单（RFC 019 §5.3）。格式各家不一样，所以不要模板：
 * 前 30 行里找表头（有"金额"类的列，再有单号、日期、品名、数量里的一个），各列按常见叫法认；
 * 合计、小计行跳过；扣款列、摘要里写了"扣款""罚款"这类的行算扣款（不认单个"扣"字：纽扣、四合扣是辅料）；
 * 写了"退货"的正数金额当退货（记负数）。
 * 第一张认得出表头的工作表就是它。
 * agy 复查 T-adeaba 补的：合计写在单号列也认；纵向合并的单号、日期、品名格子取合并区第一行的值；
 * "扣款原因"不当扣款列、"Note""客户编号"不当单号列；金额是负的退货数量也记负；没写年份的日期取离对账期间最近的一年；
 * 有单号、有数量、金额是 0 的行（我方导出的"缺单价"）也读；金额按分四舍五入
 */
final class TheirSheetReader {

    /** 表里的一行 */
    record TheirRow(int rowNo, String no, String orderNo, LocalDate date, String description, BigDecimal qty, BigDecimal price,
                    BigDecimal amount, boolean deduct) {
    }

    record Parsed(String sheetName, List<TheirRow> rows) {
    }

    enum Field { DEDUCT, DATE, PRICE, ORDER_NO, DESC, NO, QTY, AMOUNT, UNIT, NOTE }

    private static final int HEADER_SCAN_ROWS = 30;
    private static final List<String> ORDER_NO_WORDS = List.of("订单号", "合同号", "生产单号", "采购单号", "加工单号", "订单编号");
    private static final List<String> DESC_WORDS = List.of("款号", "款式", "物料", "品名", "名称", "货品", "商品", "产品", "规格", "型号",
            "颜色", "摘要", "内容", "项目", "货号");
    /** 单号列：认这几个词；单独一个"编号"也算，"客户编号""供应商编号"不算，英文"No."多半是序号也不算 */
    private static final List<String> NO_WORDS = List.of("单号", "单据号", "单据编号", "票号");
    /** 带扣款字样、但写的是原因的列（扣款原因、扣款说明）：当备注，不当扣款金额 */
    private static final List<String> NOTE_WORDS = List.of("原因", "说明", "备注", "事由", "类型", "类别", "项目");
    private static final List<String> AMOUNT_WORDS = List.of("金额", "小计", "货款", "总额", "应付", "总价", "合计", "价税");
    private static final List<String> DEDUCT_WORDS = List.of("扣款", "扣除", "扣减", "扣罚", "罚款", "索赔", "赔款");
    private static final List<String> RETURN_WORDS = List.of("退货", "退回", "退料");
    private static final List<String> TOTAL_WORDS = List.of("合计", "总计", "小计", "本页", "累计", "总金额", "实付", "应付", "余额");
    private static final Pattern FULL_DATE = Pattern.compile("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})");
    private static final Pattern COMPACT_DATE = Pattern.compile("(20\\d{2})(\\d{2})(\\d{2})");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})日?$");
    /** 纵向合并区最多记这么多格子（整列合并这种不正常的表不至于把内存撑满） */
    private static final int MAX_MERGED_CELLS = 200_000;

    private TheirSheetReader() {
    }

    /** periodEnd 用来补没写年份的日期（"11-03"）：取对账期间止的那一年，比期间止晚一个多月的算上一年 */
    static Parsed read(Workbook workbook, LocalDate periodEnd) {
        DataFormatter f = new DataFormatter();
        f.setUseCachedValuesForFormulaCells(true);
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) {
                continue;
            }
            for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SCAN_ROWS); r++) {
                Map<Field, List<Integer>> columns = header(sheet.getRow(r), f);
                if (isHeader(columns)) {
                    return new Parsed(sheet.getSheetName(), rows(sheet, r, columns, f, periodEnd));
                }
            }
        }
        throw new BizException("认不出对方对账单的表头：要有一行写着\"金额\"（或小计、货款），再有单号、日期、品名、数量里的一个。"
                + "照片、PDF 现在还不能识别");
    }

    private static boolean isHeader(Map<Field, List<Integer>> columns) {
        return columns.containsKey(Field.AMOUNT)
                && (columns.containsKey(Field.NO) || columns.containsKey(Field.DATE) || columns.containsKey(Field.DESC)
                || columns.containsKey(Field.QTY));
    }

    /** 一行当表头读：每个格子归到一种列（品名、备注、单号可以有几列，按从左到右；别的取第一列） */
    static Map<Field, List<Integer>> header(Row row, DataFormatter f) {
        Map<Field, List<Integer>> columns = new EnumMap<>(Field.class);
        if (row == null) {
            return columns;
        }
        for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) {
            String t = normalize(text(row, c, f));
            if (t.isEmpty() || t.length() > 12) {
                continue;
            }
            Field field = classify(t);
            if (field == null) {
                continue;
            }
            List<Integer> cols = columns.computeIfAbsent(field, k -> new ArrayList<>());
            if (field == Field.DESC || field == Field.NOTE || field == Field.NO || cols.isEmpty()) {
                cols.add(c);
            }
        }
        return columns;
    }

    static Field classify(String t) {
        if (DEDUCT_WORDS.stream().anyMatch(t::contains) || t.equals("扣")) {
            return NOTE_WORDS.stream().anyMatch(t::contains) ? Field.NOTE : Field.DEDUCT;
        }
        if (t.contains("日期") || t.contains("时间") || t.equals("date")) {
            return Field.DATE;
        }
        if (t.contains("单价") || t.equals("价格") || t.equals("价") || t.equals("price")) {
            return Field.PRICE;
        }
        if (ORDER_NO_WORDS.stream().anyMatch(t::contains)) {
            return Field.ORDER_NO;
        }
        if (DESC_WORDS.stream().anyMatch(t::contains)) {
            return Field.DESC;
        }
        if (NO_WORDS.stream().anyMatch(t::contains) || t.equals("编号")) {
            return Field.NO;
        }
        if (t.contains("数量") || t.contains("件数") || t.equals("数") || t.equals("qty")) {
            return Field.QTY;
        }
        if (AMOUNT_WORDS.stream().anyMatch(t::contains) || t.equals("amount")) {
            return Field.AMOUNT;
        }
        if (t.contains("单位")) {
            return Field.UNIT;
        }
        if (t.contains("备注") || t.contains("说明") || t.contains("类型") || t.contains("类别")) {
            return Field.NOTE;
        }
        return null;
    }

    private static List<TheirRow> rows(Sheet sheet, int headerRow, Map<Field, List<Integer>> cols, DataFormatter f, LocalDate periodEnd) {
        List<TheirRow> out = new ArrayList<>();
        Merged merged = new Merged(sheet);
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            // 认单据的列（单号、日期、品名、备注）纵向合并了取合并区第一行的；金额、数量不取，免得重复算钱
            String no = firstText(sheet, row, cols.get(Field.NO), merged, f);
            String orderNo = firstText(sheet, row, cols.get(Field.ORDER_NO), merged, f);
            LocalDate date = date(merged.cell(sheet, row, first(cols, Field.DATE)), f, periodEnd);
            String desc = joinText(sheet, row, cols.get(Field.DESC), merged, f);
            String note = joinText(sheet, row, cols.get(Field.NOTE), merged, f);
            if (isTotal(row, f) && (no.isEmpty() || isTotalText(no) || isTotalText(desc) || isTotalText(note))) {
                continue;
            }
            BigDecimal qty = number(row, first(cols, Field.QTY), f);
            BigDecimal price = number(row, first(cols, Field.PRICE), f);
            BigDecimal amount = money(number(row, first(cols, Field.AMOUNT), f));
            BigDecimal deduct = money(number(row, first(cols, Field.DEDUCT), f));
            if (amount == null && qty != null && price != null) {
                amount = money(qty.multiply(price));
            }
            boolean money = amount != null && amount.signum() != 0;
            // 有单号、有数量、金额写的 0：我方导出的"缺单价"那种，留着参加比对
            boolean zeroLine = amount != null && amount.signum() == 0 && !no.isEmpty() && qty != null && qty.signum() != 0;
            boolean deductMoney = deduct != null && deduct.signum() != 0;
            if (!money && !zeroLine && !deductMoney) {
                continue;
            }
            if (out.size() >= Excel.MAX_IMPORT_ROWS) {
                throw new BizException("对方对账单超过 " + Excel.MAX_IMPORT_ROWS + " 行了，分几个文件传");
            }
            String words = desc + " " + note + " " + no;
            int rowNo = r + 1;
            if (deductMoney) {
                out.add(new TheirRow(rowNo, no, orderNo, date, desc.isEmpty() ? "扣款" : desc, null, null, deduct.abs(), true));
            }
            if (money || zeroLine) {
                if (money && !deductMoney && DEDUCT_WORDS.stream().anyMatch(words::contains)) {
                    out.add(new TheirRow(rowNo, no, orderNo, date, desc, null, null, amount.abs(), true));
                    continue;
                }
                if (amount.signum() > 0 && RETURN_WORDS.stream().anyMatch(words::contains)) {
                    amount = amount.negate();
                    qty = qty == null ? null : qty.abs().negate();
                } else if (amount.signum() < 0 && qty != null && qty.signum() > 0) {
                    // 退货金额写了负数、数量还写正数的：数量跟着记负
                    qty = qty.negate();
                }
                out.add(new TheirRow(rowNo, no, orderNo, date, desc, qty, price, amount, false));
            }
        }
        return out;
    }

    /** 几列里第一个有字的（单号：单号、送货单号都有时取左边先写了的） */
    private static String firstText(Sheet sheet, Row row, List<Integer> cols, Merged merged, DataFormatter f) {
        if (cols == null) {
            return "";
        }
        for (int c : cols) {
            String t = text(merged.cell(sheet, row, c), f);
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    private static String joinText(Sheet sheet, Row row, List<Integer> cols, Merged merged, DataFormatter f) {
        if (cols == null) {
            return "";
        }
        return String.join(" ", cols.stream().map(c -> text(merged.cell(sheet, row, c), f)).filter(t -> !t.isEmpty()).toList());
    }

    /** 这个格子本身就是"合计""本月小计""合计 3 件"这种（不是长摘要里碰巧有这两个字） */
    static boolean isTotalText(String s) {
        String t = normalize(s);
        return !t.isEmpty() && t.length() <= 10 && TOTAL_WORDS.stream().anyMatch(t::contains);
    }

    /** 金额按分四舍五入：公式算出来的 0.30000000000000004、33.333333 这种落成两位 */
    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : plain(v.setScale(2, RoundingMode.HALF_UP));
    }

    /** 纵向合并区（跨几行）里除第一行以外的格子 → 合并区左上角。只横着合并的不管（同一行，读的就是左上角） */
    static final class Merged {
        private final Map<Long, CellAddress> topLeft = new HashMap<>();

        Merged(Sheet sheet) {
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                if (region.getFirstRow() == region.getLastRow()) {
                    continue;
                }
                CellAddress origin = new CellAddress(region.getFirstRow(), region.getFirstColumn());
                for (int r = region.getFirstRow() + 1; r <= region.getLastRow(); r++) {
                    for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                        if (topLeft.size() >= MAX_MERGED_CELLS) {
                            return;
                        }
                        topLeft.put(key(r, c), origin);
                    }
                }
            }
        }

        Cell cell(Sheet sheet, Row row, int col) {
            if (col < 0) {
                return null;
            }
            CellAddress origin = topLeft.get(key(row.getRowNum(), col));
            if (origin == null) {
                return row.getCell(col);
            }
            Row source = sheet.getRow(origin.getRow());
            return source == null ? null : source.getCell(origin.getColumn());
        }

        private static long key(int row, int col) {
            return ((long) row << 20) | col;
        }
    }

    /** 合计、小计这种行：这一行任何一个格子写着合计、总计…… */
    private static boolean isTotal(Row row, DataFormatter f) {
        for (int c = Math.max(0, row.getFirstCellNum()); c < row.getLastCellNum(); c++) {
            String t = normalize(text(row, c, f));
            if (!t.isEmpty() && TOTAL_WORDS.stream().anyMatch(t::contains)) {
                return true;
            }
        }
        return false;
    }

    private static int first(Map<Field, List<Integer>> cols, Field field) {
        List<Integer> list = cols.get(field);
        return list == null ? -1 : list.getFirst();
    }

    /** 去掉空白、全角括号里的单位，小写："金额（元）" → "金额" */
    static String normalize(String s) {
        return s.replaceAll("[\\s:：]", "").replaceAll("[(（][^)）]*[)）]", "").toLowerCase(Locale.ROOT);
    }

    static String text(Row row, int col, DataFormatter f) {
        return col < 0 ? "" : text(row.getCell(col), f);
    }

    static String text(Cell cell, DataFormatter f) {
        return cell == null ? "" : f.formatCellValue(cell).replaceAll("\\s+", " ").strip();
    }

    /** 数字格取存的值；文字去掉 ¥、元、千分位逗号再读；读不出是 null */
    static BigDecimal number(Row row, int col, DataFormatter f) {
        if (col < 0) {
            return null;
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            return plain(BigDecimal.valueOf(cell.getNumericCellValue()));
        }
        String t = f.formatCellValue(cell).replaceAll("[\\s,，¥￥元]", "");
        if (t.isEmpty()) {
            return null;
        }
        if (t.startsWith("(") && t.endsWith(")")) {
            t = "-" + t.substring(1, t.length() - 1);
        }
        try {
            return plain(new BigDecimal(t));
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    /** 去掉末尾的 0，但不写成 1E+2 这种 */
    private static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    static LocalDate date(Cell cell, DataFormatter f, LocalDate periodEnd) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double v = cell.getNumericCellValue();
            // 日期格式的格子，或者没设格式、但数看着像 Excel 的日期序号（2009 年到 2064 年）
            if (DateUtil.isCellDateFormatted(cell) || (v > 40_000 && v < 60_000 && v == Math.floor(v))) {
                return DateUtil.getLocalDateTime(v).toLocalDate();
            }
            return parseDate(String.valueOf((long) v), periodEnd);
        }
        return parseDate(f.formatCellValue(cell).strip(), periodEnd);
    }

    /** 没写年份的"1-2""12-30"：前一年、当年、后一年里离对账期间止最近的那天（12 月的账里的"1-2"是下一年的；平年没有 2 月 29 日就跳过那年） */
    static LocalDate nearestYear(int month, int day, LocalDate periodEnd) {
        LocalDate best = null;
        for (int y = periodEnd.getYear() - 1; y <= periodEnd.getYear() + 1; y++) {
            LocalDate d;
            try {
                d = LocalDate.of(y, month, day);
            } catch (DateTimeException notThisYear) {
                continue;
            }
            if (best == null || Math.abs(ChronoUnit.DAYS.between(d, periodEnd)) < Math.abs(ChronoUnit.DAYS.between(best, periodEnd))) {
                best = d;
            }
        }
        return best;
    }

    static LocalDate parseDate(String s, LocalDate periodEnd) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        try {
            Matcher m = FULL_DATE.matcher(t);
            if (m.find()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            m = COMPACT_DATE.matcher(t);
            if (m.matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            }
            m = SHORT_DATE.matcher(t);
            if (m.matches() && periodEnd != null) {
                return nearestYear(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), periodEnd);
            }
        } catch (DateTimeException badDate) {
            return null;
        }
        return null;
    }
}
