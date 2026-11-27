package com.lingmao.scm.module.settle.service.impl;

import com.lingmao.scm.module.settle.dto.CompareResult.Outcome;
import com.lingmao.scm.module.settle.dto.CompareResult.Row;
import com.lingmao.scm.module.settle.entity.StatementItem;
import com.lingmao.scm.module.settle.entity.StatementSourceType;
import com.lingmao.scm.module.settle.service.impl.TheirSheetReader.TheirRow;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 对方 Excel 对账单的读取和逐单比对（RFC 019 §5.3）：格式不固定，规则都在这两个类里 */
class StatementComparerTest {

    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    /**
     * 标题行在表头上面、表头带单位、"纽扣"不是扣款、写了"退货"的正数记负数、扣款行、合计行跳过、
     * 没写年份的日期按对账期间补、金额空着按数量 × 单价算
     */
    @Test
    void readsFreeFormSheet() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("10月");
            row(sh, 0, "某某辅料 10 月对账单");
            row(sh, 2, "送货日期", "送货单号", "品名", "规格", "数量", "单价", "金额（元）", "备注");
            row(sh, 3, "2026-10-05", "SH1005", "树脂纽扣", "18L", 100, 0.5, 50);
            row(sh, 4, "10/8", "SH1008", "织带", "", 20, 3, null);
            row(sh, 5, "10.12", "TH1012", "织带", "", 2, 3, 6, "退货");
            row(sh, 6, "10-20", "", "质量扣款", "", null, null, -30);
            row(sh, 7, "合计", "", "", "", null, null, 86);
            TheirSheetReader.Parsed p = TheirSheetReader.read(wb, END);

            assertThat(p.sheetName()).isEqualTo("10月");
            assertThat(p.rows()).extracting(TheirRow::rowNo, TheirRow::no, TheirRow::date, TheirRow::description, TheirRow::qty,
                    TheirRow::amount, TheirRow::deduct).containsExactly(
                    tuple(4, "SH1005", LocalDate.of(2026, 10, 5), "树脂纽扣 18L", new BigDecimal("100"), new BigDecimal("50"), false),
                    tuple(5, "SH1008", LocalDate.of(2026, 10, 8), "织带", new BigDecimal("20"), new BigDecimal("60"), false),
                    tuple(6, "TH1012", LocalDate.of(2026, 10, 12), "织带", new BigDecimal("-2"), new BigDecimal("-6"), false),
                    tuple(7, "", LocalDate.of(2026, 10, 20), "质量扣款", null, new BigDecimal("30"), true));
        }
    }

    /**
     * agy 复查 T-adeaba #2、#3、#6、#8、#9：合计写在单号列跳过；单号、日期纵向合并的取合并区第一行；
     * "扣款原因"是备注、"扣款金额"才是扣款，"Note""客户编号"不当单号；金额负、数量正的退货数量记负；
     * 有单号、有数量、金额 0 的行留着；公式算出来的金额按分落地
     */
    @Test
    void readsTotalsInNoColumn_mergedCells_oddHeaders() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("对账");
            row(sh, 0, "送货单号", "送货日期", "品名", "数量", "金额", "扣款原因", "扣款金额", "Note", "客户编号");
            row(sh, 1, "SH1", "2026-10-05", "面料", 10, 200, null, null, "first", "C001");
            row(sh, 2, null, null, "里布", 5, 150, null, null, null, "C001");
            sh.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));
            sh.addMergedRegion(new CellRangeAddress(1, 2, 1, 1));
            row(sh, 3, "TH2", "10-20", "面料 退回", 2, -40);
            row(sh, 4, "SH3", "10-25", "延期", null, null, "延期两天", 30);
            row(sh, 5, "RK9", "10-26", "缺单价的料", 3, 0);
            row(sh, 6, "SH4", "10-27", "辅料", 3, 0.1 + 0.2);
            row(sh, 7, "合计", "", "", 23, 310.3, null, 30);
            TheirSheetReader.Parsed p = TheirSheetReader.read(wb, END);

            assertThat(p.rows()).extracting(TheirRow::rowNo, TheirRow::no, TheirRow::date, TheirRow::description, TheirRow::qty,
                    TheirRow::amount, TheirRow::deduct).containsExactly(
                    tuple(2, "SH1", LocalDate.of(2026, 10, 5), "面料", new BigDecimal("10"), new BigDecimal("200"), false),
                    tuple(3, "SH1", LocalDate.of(2026, 10, 5), "里布", new BigDecimal("5"), new BigDecimal("150"), false),
                    tuple(4, "TH2", LocalDate.of(2026, 10, 20), "面料 退回", new BigDecimal("-2"), new BigDecimal("-40"), false),
                    tuple(5, "SH3", LocalDate.of(2026, 10, 25), "延期", null, new BigDecimal("30"), true),
                    tuple(6, "RK9", LocalDate.of(2026, 10, 26), "缺单价的料", new BigDecimal("3"), new BigDecimal("0"), false),
                    tuple(7, "SH4", LocalDate.of(2026, 10, 27), "辅料", new BigDecimal("3"), new BigDecimal("0.3"), false));
        }
    }

    /** 没写年份的日期取离对账期间止最近的一年：12 月的账里"1-2"是下一年，1 月的账里"12-30"是上一年 */
    @Test
    void shortDate_takesNearestYear() {
        assertThat(TheirSheetReader.parseDate("1-2", LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2027, 1, 2));
        assertThat(TheirSheetReader.parseDate("12月30日", LocalDate.of(2027, 1, 31))).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(TheirSheetReader.parseDate("10.5", END)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(TheirSheetReader.parseDate("2-29", LocalDate.of(2028, 3, 31))).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    /**
     * agy 复查 T-adeaba #7：对方单号写的是我方采购单号，一张采购单分三次送：按单号 + 日期分开，三张入库单各自对上，
     * 不合成一张只对上第一张
     */
    @Test
    void orderNoAcrossDeliveries_matchesEachReceipt() {
        List<StatementItem> ours = List.of(
                ordered(item(StatementSourceType.PURCHASE_IN, 1, "RK1", null, LocalDate.of(2026, 10, 5), "10", "1000", "0")),
                ordered(item(StatementSourceType.PURCHASE_IN, 2, "RK2", null, LocalDate.of(2026, 10, 12), "10", "1000", "0")),
                ordered(item(StatementSourceType.PURCHASE_IN, 3, "RK3", null, LocalDate.of(2026, 10, 20), "10", "1000", "0")));
        List<TheirRow> theirs = List.of(
                their(2, "CG1", LocalDate.of(2026, 10, 5), "10", "1000", false),
                their(3, "CG1", LocalDate.of(2026, 10, 12), "10", "1000", false),
                their(4, "CG1", LocalDate.of(2026, 10, 20), "10", "1000", false));

        StatementComparer.Compared c = StatementComparer.compare(theirs, ours);

        assertThat(c.rows()).extracting(Row::outcome, r -> r.ours().no())
                .containsExactly(tuple(Outcome.MATCH, "RK1"), tuple(Outcome.MATCH, "RK2"), tuple(Outcome.MATCH, "RK3"));
    }

    private static StatementItem ordered(StatementItem i) {
        i.setOrderNo("CG1");
        return i;
    }

    @Test
    void noHeader_saysWhatItNeeds() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("x");
            row(sh, 0, "随便写的", "没有表头");
            assertThatThrownBy(() -> TheirSheetReader.read(wb, END)).hasMessageContaining("认不出对方对账单的表头");
        }
    }

    /**
     * 我方：入库单 RK1（两行，送货单号 SH1）、回货 HH1（3800，次品扣 76）、入库单 RK2（只有我方有）、扣款登记 100。
     * 对方：SH1 两行合起来多 10 元；HH1 按扣了次品的 3724 写（算一致，次品那 76 不再进扣款合计）；
     * 一张我方没有的 SH9；扣款写了 100。全部差额加起来 = 对方合计 − 我方实付
     */
    @Test
    void comparesByDocument() {
        LocalDate d5 = LocalDate.of(2026, 10, 5);
        List<StatementItem> ours = List.of(
                item(StatementSourceType.PURCHASE_IN, 1, "RK1", "SH1", d5, "10", "200", "0"),
                item(StatementSourceType.PURCHASE_IN, 1, "RK1", "SH1", d5, "5", "150", "0"),
                item(StatementSourceType.PRODUCTION_RECEIPT, 2, "HH1", null, LocalDate.of(2026, 10, 15), "100", "3800", "76"),
                item(StatementSourceType.PURCHASE_IN, 3, "RK2", null, LocalDate.of(2026, 10, 18), "7", "70", "0"),
                item(StatementSourceType.DEDUCTION, 4, null, null, LocalDate.of(2026, 10, 25), null, "0", "100"));
        List<TheirRow> theirs = List.of(
                their(2, "SH1", d5, "10", "200", false),
                their(3, "sh-1", d5, "5", "160", false),
                their(4, "", LocalDate.of(2026, 10, 16), "100", "3724", false),
                their(5, "SH9", LocalDate.of(2026, 10, 22), "3", "50", false),
                their(6, "", LocalDate.of(2026, 10, 25), null, "100", true));

        StatementComparer.Compared c = StatementComparer.compare(theirs, ours);

        assertThat(c.rows()).extracting(Row::key, Row::outcome, r -> r.ours() == null ? null : r.ours().no(), Row::diffAmount)
                .containsExactly(
                        tuple("1", Outcome.DIFF, "RK1", new BigDecimal("10")),
                        tuple("2", Outcome.MATCH, "HH1", new BigDecimal("0")),
                        tuple("3", Outcome.OURS_ONLY, "RK2", new BigDecimal("-70")),
                        tuple("4", Outcome.THEIRS_ONLY, null, new BigDecimal("50")),
                        tuple("5", Outcome.MATCH, null, new BigDecimal("0")));
        assertThat(c.theirTotal()).isEqualByComparingTo("4034");
        BigDecimal ourPayable = new BigDecimal("4220").subtract(new BigDecimal("176"));
        assertThat(c.rows().stream().map(Row::diffAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(c.theirTotal().subtract(ourPayable));
    }

    private static StatementItem item(StatementSourceType type, long sourceId, String no, String delivery, LocalDate date, String qty,
                                      String amount, String deduct) {
        StatementItem i = new StatementItem();
        i.setSourceType(type);
        i.setSourceId(sourceId);
        i.setSourceNo(no);
        i.setDeliveryNo(delivery);
        i.setBizDate(date);
        i.setQty(qty == null ? null : new BigDecimal(qty));
        i.setAmount(new BigDecimal(amount));
        i.setDeductAmount(new BigDecimal(deduct));
        return i;
    }

    private static TheirRow their(int rowNo, String no, LocalDate date, String qty, String amount, boolean deduct) {
        return new TheirRow(rowNo, no, "", date, null, qty == null ? null : new BigDecimal(qty), null, new BigDecimal(amount), deduct);
    }

    private static void row(Sheet sh, int r, Object... values) {
        org.apache.poi.ss.usermodel.Row row = sh.createRow(r);
        for (int c = 0; c < values.length; c++) {
            Object v = values[c];
            if (v == null) {
                continue;
            }
            var cell = row.getCell(c, MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellValue(v.toString());
            }
        }
    }
}
