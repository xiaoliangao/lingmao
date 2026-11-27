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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * 第一张认得出表头的工作表就是它
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
    private static final List<String> NO_WORDS = List.of("单号", "单据号", "单据编号", "票号", "编号", "no");
    private static final List<String> AMOUNT_WORDS = List.of("金额", "小计", "货款", "总额", "应付", "总价", "合计", "价税");
    private static final List<String> DEDUCT_WORDS = List.of("扣款", "扣除", "扣减", "扣罚", "罚款", "索赔", "赔款");
    private static final List<String> RETURN_WORDS = List.of("退货", "退回", "退料");
    private static final List<String> TOTAL_WORDS = List.of("合计", "总计", "小计", "本页", "累计", "总金额", "实付", "应付", "余额");
    private static final Pattern FULL_DATE = Pattern.compile("(\\d{4})\\s*[-/.年]\\s*(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})");
    private static final Pattern COMPACT_DATE = Pattern.compile("(20\\d{2})(\\d{2})(\\d{2})");
    private static final Pattern SHORT_DATE = Pattern.compile("^(\\d{1,2})\\s*[-/.月]\\s*(\\d{1,2})日?$");

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

    /** 一行当表头读：每个格子归到一种列（品名类可以有几列，别的取第一列） */
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
            if (field == Field.DESC || cols.isEmpty()) {
                cols.add(c);
            }
        }
        return columns;
    }

    static Field classify(String t) {
        if (DEDUCT_WORDS.stream().anyMatch(t::contains) || t.equals("扣")) {
            return Field.DEDUCT;
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
        if (NO_WORDS.stream().anyMatch(t::contains)) {
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
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String no = text(row, first(cols, Field.NO), f);
            String orderNo = text(row, first(cols, Field.ORDER_NO), f);
            LocalDate date = date(row, first(cols, Field.DATE), f, periodEnd);
            String desc = String.join(" ", cols.getOrDefault(Field.DESC, List.of()).stream()
                    .map(c -> text(row, c, f)).filter(t -> !t.isEmpty()).toList());
            String note = text(row, first(cols, Field.NOTE), f);
            if (no.isEmpty() && date == null && isTotal(row, f)) {
                continue;
            }
            BigDecimal qty = number(row, first(cols, Field.QTY), f);
            BigDecimal price = number(row, first(cols, Field.PRICE), f);
            BigDecimal amount = number(row, first(cols, Field.AMOUNT), f);
            BigDecimal deduct = number(row, first(cols, Field.DEDUCT), f);
            if (amount == null && qty != null && price != null) {
                amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            }
            boolean money = amount != null && amount.signum() != 0;
            boolean deductMoney = deduct != null && deduct.signum() != 0;
            if (!money && !deductMoney) {
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
            if (money) {
                if (!deductMoney && DEDUCT_WORDS.stream().anyMatch(words::contains)) {
                    out.add(new TheirRow(rowNo, no, orderNo, date, desc, null, null, amount.abs(), true));
                    continue;
                }
                if (amount.signum() > 0 && RETURN_WORDS.stream().anyMatch(words::contains)) {
                    amount = amount.negate();
                    qty = qty == null ? null : qty.abs().negate();
                }
                out.add(new TheirRow(rowNo, no, orderNo, date, desc, qty, price, amount, false));
            }
        }
        return out;
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
        if (col < 0) {
            return "";
        }
        Cell cell = row.getCell(col);
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

    static LocalDate date(Row row, int col, DataFormatter f, LocalDate periodEnd) {
        if (col < 0) {
            return null;
        }
        Cell cell = row.getCell(col);
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
                LocalDate d = LocalDate.of(periodEnd.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return d.isAfter(periodEnd.plusDays(45)) ? d.minusYears(1) : d;
            }
        } catch (DateTimeException badDate) {
            return null;
        }
        return null;
    }
}
