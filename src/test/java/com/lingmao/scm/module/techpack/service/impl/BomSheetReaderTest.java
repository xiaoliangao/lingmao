package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.Line;
import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.SheetData;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BomSheetReaderTest {

    /**
     * 规则（RFC 011 §4.1）：按表头的字认列（中间插了一列"图片"照样认）；第 1 行拆成款号、款名（去掉末尾的 BOM）；
     * 物料名称空着、只写了规格的行记成上一行的 variants；小计行不要；百分比格式的损耗 3% 读成 3；
     * 汇总块的加工费、运费按标签读，取标签右边"预估价"那一列的数。用的是 .xls（甲方的原件是这个格式）
     */
    @Test
    void readsBlocksByHeaderText() {
        try (Workbook wb = new HSSFWorkbook()) {
            CellStyle percent = wb.createCellStyle();
            percent.setDataFormat(wb.createDataFormat().getFormat("0%"));
            Sheet sheet = wb.createSheet("测试裤");
            cells(sheet.createRow(0), "AB123 测试裤 BOM");
            cells(sheet.createRow(2), "项目", "", "图片", "物料名称", "规格", "颜色", "单价", "单位", "单件用量", "损耗", "预估价", "工厂", "备注");
            Row fabric = sheet.createRow(3);
            cells(fabric, "A料", "面料A", "", "测试面料", "140cm", "卡其、黑色", 20, "米", 1.2, 0.03, 24.72, "测试厂", "含税运");
            fabric.getCell(9).setCellStyle(percent);
            cells(sheet.createRow(4), "小计", "", "", "", "", "", null, "", 1.2, null, 24.72);
            cells(sheet.createRow(6), "项目", "", "图片", "物料名称", "规格", "颜色", "单价", "单位", "单件用量", "损耗", "预估价", "工厂", "备注");
            cells(sheet.createRow(7), "辅料", "辅料", "", "门襟拉链", "16cm", "/", 1.5, "条", 1, null, 1.5);
            cells(sheet.createRow(8), "", "", "", "", "18cm", "", 1.6, "条", 1, null, 1.6);
            cells(sheet.createRow(10), "加工费：", "", "", "", "", "", null, "", null, null, 25.5);
            cells(sheet.createRow(11), "快递运费", "", "", "", "", "", null, "", null, null, 3);
            cells(sheet.createRow(12), "成本合计", "", "", "", "", "", null, "", null, null, 54.82);
            cells(wb.createSheet("说明").createRow(0), "这张不是 BOM");

            List<SheetData> sheets = BomSheetReader.read(wb);

            assertThat(sheets).extracting(SheetData::bom).containsExactly(true, false);
            SheetData s = sheets.getFirst();
            assertThat(s.code()).isEqualTo("AB123");
            assertThat(s.name()).isEqualTo("测试裤");
            assertThat(s.lines()).extracting(Line::name).containsExactly("测试面料", "门襟拉链");
            Line first = s.lines().getFirst();
            assertThat(first.row()).isEqualTo(4);
            assertThat(first.section()).isEqualTo("A料");
            assertThat(first.category()).isEqualTo("面料A");
            assertThat(first.color()).isEqualTo("卡其、黑色");
            assertThat(first.usage()).isEqualByComparingTo("1.2");
            assertThat(first.loss()).isEqualByComparingTo("3");
            assertThat(first.price()).isEqualByComparingTo("20");
            assertThat(first.supplier()).isEqualTo("测试厂");
            assertThat(first.remark()).isEqualTo("含税运");
            assertThat(s.lines().get(1).variants()).containsExactly("18cm");
            assertThat(s.laborPrice()).isEqualByComparingTo("25.5");
            assertThat(s.fees()).singleElement().satisfies(f -> {
                assertThat(f.name()).isEqualTo("快递运费");
                assertThat(f.amount()).isEqualByComparingTo("3");
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * 鞋类 BOM（PRD Q49）：物料表下面是费用块——块名一行、"项目名称 / 单价 / 金额"的表头、各项、小计行（"价格/含税运"右边的数）。
     * 带"加工"的几块合成加工费、明细进 laborNote；包装块带成其他费用（小计行左边另有一个 0，不取它）；
     * 最下面那张没块名的采购明细表不要，也不把它的表头当物料，小计行上的数不当成上一行物料的规格
     */
    @Test
    void readsShoeFeeBlocks() {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("测试鞋");
            cells(sheet.createRow(0), "测试鞋 BOM成本拆分表");
            cells(sheet.createRow(1), "项目", "", "物料名称", "规格（厚度）", "色号/颜色", "价格/含税", "下单数（双）", "总用量", "单位", "单件用量", "损耗",
                    "总单耗", "", "", "", "预估价", "工厂", "备注");
            cells(sheet.createRow(2), "鞋身", "面料", "测试超纤", "1.4mm", "黑色", 30, null, 0, "米", 0.2, null, null, null, null, null, null, "测试厂");
            cells(sheet.createRow(3), "鞋底", "底料", "大底", "大底", "黑色", 12, null, 0, "双", 1);
            cells(sheet.createRow(4), "小计");
            cells(sheet.createRow(6), "冲裁及手工（加工）");
            cells(sheet.createRow(7), "序号", "项目名称", "单位", "单价", "金额");
            cells(sheet.createRow(8), 1, "外头", "双");
            cells(sheet.createRow(9), "冲裁手工小计：", "", "", "", "", "价格/含税运（增值税13%）", "", "", 1.5);
            cells(sheet.createRow(11), "针车价格（加工）：");
            cells(sheet.createRow(12), "序号", "项目名称", "单位", "单价", "金额");
            cells(sheet.createRow(13), 1, "针车", "双", 3, 3);
            cells(sheet.createRow(14), 2, "电脑车", "双", 2.5, 2.5);
            cells(sheet.createRow(16), "包装资料");
            cells(sheet.createRow(17), "序号", "项目名称", "单位", "金额");
            cells(sheet.createRow(18), 1, "鞋盒", "双", null, 0);
            cells(sheet.createRow(19), "包装资料", "", "", 0, 0, "价格/含税运（增值税13%）", "", "", 2.4);
            cells(sheet.createRow(21), "序号", "项目名称/", "供应商", "规格（厚度）", "单位", "单件用量", "损耗", "总单耗", "订单数量", "", "", "", "",
                    "材料单价", "实际应付金额");
            cells(sheet.createRow(22), 1, "裁断", "测试厂", "加工", "双");
            cells(sheet.createRow(23), "小计汇总：");

            SheetData s = BomSheetReader.read(wb).getFirst();

            assertThat(s.bom()).isTrue();
            assertThat(s.name()).isEqualTo("测试鞋 BOM成本拆分表");
            assertThat(s.lines()).extracting(Line::name).containsExactly("测试超纤", "大底");
            assertThat(s.lines().getFirst().usage()).isEqualByComparingTo("0.2");
            assertThat(s.lines().getFirst().price()).isEqualByComparingTo("30");
            assertThat(s.lines().getFirst().supplier()).isEqualTo("测试厂");
            assertThat(s.lines().get(1).variants()).isEmpty();
            // 针车块没有小计行：到下一块的块名为止，按各项金额加 3 + 2.5
            assertThat(s.laborPrice()).isEqualByComparingTo("7");
            assertThat(s.laborNote()).isEqualTo("冲裁及手工 1.5 + 针车 5.5");
            assertThat(s.fees()).singleElement().satisfies(fee -> {
                assertThat(fee.name()).isEqualTo("包装资料");
                assertThat(fee.amount()).isEqualByComparingTo("2.4");
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 一行格子：数字写成数字格，null 和 "" 留空 */
    static void cells(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v instanceof Number n) {
                row.createCell(i).setCellValue(n.doubleValue());
            } else if (v instanceof String t && !t.isEmpty()) {
                row.createCell(i).setCellValue(t);
            }
        }
    }
}
