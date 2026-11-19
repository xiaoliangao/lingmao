package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.Line;
import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.SheetData;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
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
