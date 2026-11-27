package com.lingmao.scm.module.techpack.service.impl;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 读甲方的 BOM 明细表（RFC 011 §4.1）：一个款一张 sheet，第 1 行款号款名，下面面料块、辅料块（各有一行表头），最后是汇总块。
 * 只读出表里写了什么，不和物料库对（那是 BomImporter 的事）。
 * <ul>
 *   <li>按表头的字认列，不按第几列：有"物料名称"和"用量"的那一行是表头，列挪了位置、中间插了列都认得出；每块的表头各认各的</li>
 *   <li>表头下面，物料名称有字、用量单位单价至少写了一样的是一行物料；物料名称空着、只写了规格的是上一行的另一种规格
 *       （门襟拉链按尺码分长短），记在上一行的 variants 里</li>
 *   <li>汇总块：写着"加工费"的取它的数，写着"…运费"的带成其他费用；小计、合计、各种成本不要，系统自己算</li>
 *   <li>费用块（鞋类 BOM，PRD Q49）：表头有"项目名称"和"单价"或"金额"、没有"物料名称"的一行开始一块，上面那行只写了一格字的是块名
 *       （"冲裁及手工（加工）""针车价格（加工）""包装资料"）。块的数取小计行上"价格…"那格右边的数，没有就取"小计"右边的，
 *       都没有按各项的金额加起来。块名带"加工"的合成加工费（几块的明细写进 laborNote），别的带成其他费用。
 *       费用块开始以后，物料清单就结束了（下面再有物料表头才接着认物料）</li>
 *   <li>数取格子里存的值，不取显示的（显示可能只到两位小数）；公式取 Excel 存下的结果。设成百分比格式的 3% 读成 3</li>
 * </ul>
 */
final class BomSheetReader {

    /** 一张 sheet。bom = 认出了表头；code、name 是第 1 行写的款号、款名（没有是 null） */
    record SheetData(int index, String sheetName, String title, String code, String name, boolean bom, List<Line> lines,
                     BigDecimal laborPrice, String laborNote, List<Fee> fees) {
    }

    /** 一行物料。row 是 Excel 里的行号（从 1 起）；数字列各有原样的字（给人看）和读出来的数（读不出是 null） */
    record Line(int row, String section, String category, String name, String spec, String color,
                String priceText, BigDecimal price, String unit, String usageText, BigDecimal usage,
                String lossText, BigDecimal loss, String supplier, String remark, List<String> variants) {
    }

    /** 汇总块里的一项费用：名称去掉了冒号 */
    record Fee(String name, BigDecimal amount) {
    }

    /** 不进物料清单的汇总、小计行上的字（去掉空白和冒号以后） */
    private static final Set<String> TOTALS = Set.of("小计", "合计", "总计", "面料", "辅料", "辅料&耗材", "材料成本", "制造成本", "成本合计");

    private BomSheetReader() {
    }

    static List<SheetData> read(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        formatter.setUseCachedValuesForFormulaCells(true);
        List<SheetData> sheets = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            sheets.add(sheet(workbook.getSheetAt(i), i, formatter));
        }
        return sheets;
    }

    /** 一块的列：表头上找到的列号，没有的是 -1 */
    private static final class Columns {
        int section = -1, category = -1, name = -1, spec = -1, color = -1, price = -1, unit = -1, usage = -1, loss = -1,
                amount = -1, supplier = -1, remark = -1;

        boolean valid() {
            return name >= 0 && usage >= 0;
        }
    }

    private static SheetData sheet(Sheet sheet, int index, DataFormatter f) {
        String title = null;
        Columns cols = null;
        boolean bom = false;
        List<Line> lines = new ArrayList<>();
        BigDecimal labor = null;
        List<Fee> fees = new ArrayList<>();
        // 费用块（鞋类）：blockTitle 是最近一行只写了一格字的（块名候选），block 是正在读的块
        String blockTitle = null;
        FeeBlock block = null;
        List<FeeBlock> blocks = new ArrayList<>();
        for (Row row : sheet) {
            Columns header = header(row, f);
            if (header != null) {
                cols = header;
                bom = true;
                block = closeBlock(block, null, blocks);
                continue;
            }
            int feeAmountCol = feeHeader(row, f);
            if (bom && feeAmountCol >= 0) {
                closeBlock(block, null, blocks);
                block = new FeeBlock(blockTitle, feeAmountCol);
                blockTitle = null;
                cols = null;
                continue;
            }
            String single = singleText(row, f);
            if (cols == null) {
                if (!bom) {
                    if (title == null) {
                        title = firstText(row, f);
                    }
                    continue;
                }
                if (block == null) {
                    if (single != null) {
                        blockTitle = single;
                    }
                    continue;
                }
                BigDecimal subtotal = blockSubtotal(row, f);
                String first = firstText(row, f);
                if (subtotal != null || (first != null && isTotal(first))) {
                    block = closeBlock(block, subtotal, blocks);
                } else if (single != null) {
                    // 没有小计行就到了下一块的块名：这一块按各项加
                    block = closeBlock(block, null, blocks);
                    blockTitle = single;
                } else {
                    BigDecimal item = number(row, block.amountCol, f);
                    if (item != null) {
                        block.items = block.items.add(item);
                    }
                }
                continue;
            }
            String name = text(row, cols.name, f);
            String usageText = text(row, cols.usage, f);
            String unit = text(row, cols.unit, f);
            String priceText = text(row, cols.price, f);
            boolean data = !usageText.isEmpty() || !unit.isEmpty() || !priceText.isEmpty();
            if (!name.isEmpty() && data && !isTotal(name)) {
                lines.add(new Line(row.getRowNum() + 1, text(row, cols.section, f), text(row, cols.category, f), name,
                        text(row, cols.spec, f), text(row, cols.color, f), priceText, number(row, cols.price, f), unit,
                        usageText, number(row, cols.usage, f), text(row, cols.loss, f), number(row, cols.loss, f),
                        text(row, cols.supplier, f), text(row, cols.remark, f), new ArrayList<>()));
                continue;
            }
            String spec = text(row, cols.spec, f);
            if (name.isEmpty() && !spec.isEmpty() && data && !lines.isEmpty()) {
                lines.getLast().variants().add(spec);
                continue;
            }
            if (single != null) {
                blockTitle = single;
            }
            // 汇总块：一行里找写着加工费、运费的格子
            for (Cell cell : row) {
                String label = label(f.formatCellValue(cell));
                if (label.contains("加工费")) {
                    labor = valueRightOf(row, cell.getColumnIndex(), cols.amount, f);
                } else if (label.endsWith("运费")) {
                    BigDecimal amount = valueRightOf(row, cell.getColumnIndex(), cols.amount, f);
                    if (amount != null && amount.signum() > 0) {
                        fees.add(new Fee(label, amount));
                    }
                }
            }
        }
        closeBlock(block, null, blocks);
        String laborNote = null;
        List<FeeBlock> laborBlocks = blocks.stream().filter(FeeBlock::labor).toList();
        if (!laborBlocks.isEmpty() && labor == null) {
            labor = laborBlocks.stream().map(b -> b.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            laborNote = String.join(" + ", laborBlocks.stream().map(b -> b.name() + " " + b.amount.stripTrailingZeros().toPlainString()).toList());
        }
        blocks.stream().filter(b -> !b.labor()).forEach(b -> fees.add(new Fee(b.name(), b.amount)));
        String code = null;
        String name = null;
        if (title != null) {
            String[] parts = title.split("\\s+", 2);
            if (parts[0].matches("[A-Za-z0-9][A-Za-z0-9_#.\\-]*") && parts[0].matches(".*\\d.*")) {
                code = parts[0];
                name = parts.length > 1 ? parts[1] : null;
            } else {
                name = title;
            }
            if (name != null) {
                name = name.replaceAll("(?i)\\s*BOM\\s*$", "").strip();
                name = name.isEmpty() ? null : name;
            }
        }
        return new SheetData(index, sheet.getSheetName(), title, code, name, bom, lines, labor, laborNote, fees);
    }

    /** 一个费用块：title 是块名那一行的原字，amountCol 是表头上"金额"（没有用"单价"）那一列，items 是各项金额加起来，amount 是定下来的数 */
    private static final class FeeBlock {
        final String title;
        final int amountCol;
        BigDecimal items = BigDecimal.ZERO;
        BigDecimal amount;

        FeeBlock(String title, int amountCol) {
            this.title = title;
            this.amountCol = amountCol;
        }

        /** "冲裁及手工（加工）" → "冲裁及手工"，"针车价格（加工）：" → "针车" */
        String name() {
            return title.replaceAll("[（(]\\s*加工\\s*[)）]", "").replaceAll("价格$", "").strip();
        }

        boolean labor() {
            return title.contains("加工");
        }
    }

    /** 收一个块：小计行的数优先，没有按各项加；没名字的块（如下面的采购明细表）、数是 0 的不要。返回 null（块读完了） */
    private static FeeBlock closeBlock(FeeBlock block, BigDecimal subtotal, List<FeeBlock> blocks) {
        if (block == null || block.title == null) {
            return null;
        }
        BigDecimal amount = subtotal != null ? subtotal : block.items;
        if (amount != null && amount.signum() > 0 && !block.name().isEmpty()) {
            block.amount = amount;
            blocks.add(block);
        }
        return null;
    }

    /** 费用块的表头："项目名称"加上"金额"或"单价"（没有"物料名称"，那是物料表头）。返回金额那一列，不是表头返回 -1 */
    private static int feeHeader(Row row, DataFormatter f) {
        boolean item = false;
        int amount = -1;
        int price = -1;
        for (Cell cell : row) {
            String h = f.formatCellValue(cell).replaceAll("\\s+", "");
            if (h.contains("项目名称")) item = true;
            else if (h.contains("金额") && amount < 0) amount = cell.getColumnIndex();
            else if (h.contains("单价") && price < 0) price = cell.getColumnIndex();
        }
        return item ? (amount >= 0 ? amount : price) : -1;
    }

    /** 费用块的小计行：写着"价格…"（"价格/含税运（增值税13%）"）那格右边的数 */
    private static BigDecimal blockSubtotal(Row row, DataFormatter f) {
        for (Cell cell : row) {
            if (label(f.formatCellValue(cell)).startsWith("价格")) {
                return valueRightOf(row, cell.getColumnIndex(), -1, f);
            }
        }
        String first = firstText(row, f);
        if (first != null && label(first).contains("小计")) {
            for (Cell cell : row) {
                if (label(f.formatCellValue(cell)).contains("小计")) {
                    return valueRightOf(row, cell.getColumnIndex(), -1, f);
                }
            }
        }
        return null;
    }

    /** 这一行只写了一格字（块名、分段标题）：返回去掉冒号的字，不是返回 null */
    private static String singleText(Row row, DataFormatter f) {
        String only = null;
        for (Cell cell : row) {
            String v = f.formatCellValue(cell).strip();
            if (v.isEmpty()) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = v;
        }
        return only == null || only.matches("[\\d.\\-￥¥,]+") ? null : label(only);
    }

    /** 这一行是表头：认出各列；不是表头返回 null */
    private static Columns header(Row row, DataFormatter f) {
        Columns c = new Columns();
        for (Cell cell : row) {
            String h = f.formatCellValue(cell).replaceAll("\\s+", "");
            int i = cell.getColumnIndex();
            if (h.isEmpty()) {
                continue;
            }
            if (h.equals("项目")) c.section = i;
            else if (h.contains("类别")) c.category = i;
            else if (h.contains("物料名称") || h.equals("名称") || h.equals("品名")) c.name = i;
            else if (h.contains("规格")) c.spec = i;
            else if (h.contains("颜色") || h.contains("色号")) c.color = i;
            else if (h.contains("单价") || h.startsWith("价格")) c.price = i;
            else if (h.equals("单位")) c.unit = i;
            else if (h.contains("用量") && !h.contains("总")) c.usage = i;
            else if (h.contains("损耗")) c.loss = i;
            else if (h.contains("预估价") || h.contains("金额")) c.amount = i;
            else if (h.contains("工厂") || h.contains("供应商")) c.supplier = i;
            else if (h.contains("备注")) c.remark = i;
        }
        if (!c.valid()) {
            return null;
        }
        // "项目"后面没有表头字的那一列是类别（面料A、辅料）
        if (c.category < 0 && c.section >= 0 && c.section + 1 < c.name && text(row, c.section + 1, f).isEmpty()) {
            c.category = c.section + 1;
        }
        return c;
    }

    /** 汇总、小计这类行上的字：去掉空白和冒号以后在 TOTALS 里，或者带"合计""小计""成本""运费""加工费" */
    static boolean isTotal(String text) {
        String l = label(text);
        return TOTALS.contains(l) || l.contains("合计") || l.contains("小计") || l.endsWith("成本") || l.endsWith("运费")
                || l.contains("加工费");
    }

    private static String label(String text) {
        return text.replaceAll("\\s+", "").replaceAll("[：:]+$", "");
    }

    /** 标签格右边的数：表头里有"预估价"列、而且在标签右边，取那一格；不然取右边第一个是数的格子 */
    private static BigDecimal valueRightOf(Row row, int labelCol, int amountCol, DataFormatter f) {
        if (amountCol > labelCol) {
            BigDecimal v = number(row, amountCol, f);
            if (v != null) {
                return v;
            }
        }
        for (int c = labelCol + 1; c < row.getLastCellNum(); c++) {
            BigDecimal v = number(row, c, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String firstText(Row row, DataFormatter f) {
        for (Cell cell : row) {
            String t = f.formatCellValue(cell).strip();
            if (!t.isEmpty()) {
                return t.replaceAll("\\s{2,}", "  ");
            }
        }
        return null;
    }

    /** 格子里的字：换行、连着的空白并成一个空格；没有这一列、空格子是 "" */
    static String text(Row row, int col, DataFormatter f) {
        if (col < 0) {
            return "";
        }
        Cell cell = row.getCell(col);
        return cell == null ? "" : f.formatCellValue(cell).replaceAll("\\s+", " ").strip();
    }

    /** 格子里的数：数字格取存的值（百分比格式的乘 100），文字格去掉 ¥、元、%、千分位逗号再读；读不出是 null */
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
            BigDecimal v = BigDecimal.valueOf(cell.getNumericCellValue());
            String format = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
            if (format != null && format.contains("%")) {
                v = v.movePointRight(2);
            }
            return v.stripTrailingZeros();
        }
        String t = f.formatCellValue(cell).replaceAll("[\\s,¥￥元%]", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(t).stripTrailingZeros();
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }
}
