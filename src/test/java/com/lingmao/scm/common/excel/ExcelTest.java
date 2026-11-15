package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Excel 导入导出（RFC 001 §9）：一份列定义驱动模板、导入、导出。测试里的文件都用 POI 现做，不放二进制文件。
 * 列定义照一个真实资源的样子：编码（必填、文本）、名称（必填）、分类（字典）、单价（4 位小数）、件数、交期、税率（百分比）、
 * 创建时间（只导出）
 */
class ExcelTest {

    private static final String[] HEADERS = {"*编码", "*名称", "分类", "单价", "件数", "交期", "税率"};

    /** 一行的数据：Excel 里一行 ↔ 这个对象 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Item {
        private String code;
        private String name;
        private String type;
        private BigDecimal price;
        private Integer qty;
        private LocalDate due;
        private BigDecimal tax;
        private String createdAt;
    }

    private static ExcelSchema<Item> schema() {
        List<ExcelOption> types = List.of(
                new ExcelOption("面料商", "fabric"),
                new ExcelOption("辅料商", "trim"),
                new ExcelOption("旧分类", "old", false));
        return ExcelSchema.of("供应商", Item::new,
                ExcelColumn.text("编码", Item::getCode, Item::setCode).required().code().maxLength(16).example("GYS0001"),
                ExcelColumn.text("名称", Item::getName, Item::setName).required().maxLength(10),
                ExcelColumn.choice("分类", types, Item::getType, Item::setType),
                ExcelColumn.decimal("单价", 4, Item::getPrice, Item::setPrice).nonNegative(),
                ExcelColumn.integer("件数", Item::getQty, Item::setQty),
                ExcelColumn.date("交期", Item::getDue, Item::setDue),
                ExcelColumn.decimal("税率", 2, Item::getTax, Item::setTax).percent(),
                ExcelColumn.text("创建时间", Item::getCreatedAt, null));
    }

    // ---------- 模板 ----------

    /**
     * 规则（§9.2）：第一页"数据"只有表头，必填的带 *，只导出的列不进模板；编码列整列是文本格式；
     * 分类带下拉，下拉只有启用的选项，放在隐藏的"选项"页；第二页"填写说明"每列一行
     */
    @Test
    void template_headersFormatsDropdownAndGuide() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Excel.template(schema())))) {
            XSSFSheet data = workbook.getSheet("数据");
            assertThat(cellTexts(data.getRow(0))).containsExactly(HEADERS);
            assertThat(data.getLastRowNum()).isZero();                                 // 不放示例行
            assertThat(data.getColumnStyle(0).getDataFormatString()).isEqualTo("@");   // 编码列是文本

            List<Dropdown> validations = data.getDataValidations().stream()
                    .map(v -> new Dropdown(v.getValidationConstraint().getFormula1(),
                            v.getRegions().getCellRangeAddress(0).getFirstColumn()))
                    .toList();
            assertThat(validations).containsExactly(new Dropdown("'选项'!$A$1:$A$2", 2));
            Sheet options = workbook.getSheet("选项");
            assertThat(workbook.isSheetHidden(workbook.getSheetIndex(options))).isTrue();
            assertThat(cellTexts(options, 0)).containsExactly("面料商", "辅料商");          // 停用的不进下拉

            Sheet guide = workbook.getSheet("填写说明");
            assertThat(guide.getRow(1).getCell(0).getStringCellValue()).isEqualTo("编码");
            assertThat(guide.getRow(1).getCell(1).getStringCellValue()).isEqualTo("必填");
            assertThat(guide.getRow(4).getCell(0).getStringCellValue()).isEqualTo("单价");
            assertThat(guide.getRow(4).getCell(2).getStringCellValue()).isEqualTo("数字，最多 4 位小数");
        }
    }

    /** 规则：下载的模板照着填（编码 0012 填在文本格式的列里），能原样导进来 */
    @Test
    void template_filledIn_importsAsTyped() throws IOException {
        byte[] filled;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Excel.template(schema())))) {
            Row row = workbook.getSheet("数据").createRow(1);
            row.createCell(0).setCellValue("0012");
            row.createCell(1).setCellValue("恒丰面料");
            row.createCell(2).setCellValue("面料商");
            filled = toBytes(workbook);
        }

        List<Item> items = Excel.read(schema(), xlsx(filled)).dataOrThrow();

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().getCode()).isEqualTo("0012");
        assertThat(items.getFirst().getType()).isEqualTo("fabric");
    }

    // ---------- 导入：整批校验 ----------

    /** 规则（§9.3）：有一处不对整批不导，抛 1003，data 是全部问题（第几行、哪一列、什么问题），按行号排 */
    @Test
    void read_anyBadCell_wholeFileRejectedWithAllErrors() {
        MockMultipartFile file = file(HEADERS,
                row("A001", "恒丰面料", "面料商", 12.5, 10, null, null),
                row("A002", null, null, "十二块", null, null, null),
                row("A003", "旧供应商", "旧分类", null, null, null, null));

        ExcelImport<Item> read = Excel.read(schema(), file);
        BizException e = catchThrowableOfType(BizException.class, read::dataOrThrow);

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        assertThat(e.getMessage()).isEqualTo("导入校验没通过，共 3 处问题，一行都没有导入。改好以后重新导入整个文件");
        assertThat(errors(e)).containsExactly(
                new ImportError(3, "名称", "必填"),
                new ImportError(3, "单价", "要填数字"),
                new ImportError(4, "分类", "「旧分类」已停用，不能再用"));
    }

    /** 规则：表头按名字对，不按位置；* 可有可无；导出文件里只导出的列（创建时间）带着也行，不读 */
    @Test
    void read_headerReorderedAndExportOnlyColumn_ok() {
        MockMultipartFile file = file(new String[]{"创建时间", "名称", "编码"},
                row("2026-11-05 10:00:00", "恒丰面料", "A001"));

        List<Item> items = Excel.read(schema(), file).dataOrThrow();

        assertThat(items).containsExactly(new Item("A001", "恒丰面料", null, null, null, null, null, null));
    }

    /** 规则：缺了必填的列、有不认识的列（多半是改了名），整个文件不读，1003，行号是 1 */
    @Test
    void read_missingRequiredOrUnknownColumn_1003AtRow1() {
        MockMultipartFile file = file(new String[]{"*编码", "供应商名"}, row("A001", "恒丰面料"));

        BizException e = catchThrowableOfType(BizException.class, () -> Excel.read(schema(), file));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        assertThat(errors(e)).containsExactlyInAnyOrder(
                new ImportError(1, "供应商名", "不认识这一列：表头要和模板里的一样，用不上的列请整列删掉"),
                new ImportError(1, "名称", "缺少这一列，它是必填的"));
    }

    // ---------- 导入：逐格解析 ----------

    /**
     * 规则：数字按显示的文字转 BigDecimal，不经过 double：0.1 还是 0.1；千分位逗号去掉；整数列填 5 是 5；
     * 百分比格式的 3% 读成 3；小数位超了报错，不偷偷四舍五入
     */
    @Test
    void read_numbers_exactWithoutDouble() throws IOException {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
            writeRow(sheet.createRow(0), (Object[]) HEADERS);
            CellStyle percent = workbook.createCellStyle();
            percent.setDataFormat(workbook.createDataFormat().getFormat("0%"));
            writeRow(sheet.createRow(1), "A001", "甲", null, 0.1, 5, null, 0.03);
            sheet.getRow(1).getCell(6).setCellStyle(percent);
            writeRow(sheet.createRow(2), "A002", "乙", null, "1,234.5", "7", null, "13");
            writeRow(sheet.createRow(3), "A003", "丙", null, 0.12345, 2.5, null, null);
            writeRow(sheet.createRow(4), "A004", "丁", null, -1, null, null, null);
            bytes = toBytes(workbook);
        }

        ExcelImport<Item> read = Excel.read(schema(), xlsx(bytes));

        Item first = read.lines().get(0).data();
        assertThat(first.getPrice()).isEqualByComparingTo("0.1");
        assertThat(first.getPrice().toPlainString()).isEqualTo("0.1");
        assertThat(first.getQty()).isEqualTo(5);
        assertThat(first.getTax().toPlainString()).isEqualTo("3");
        Item second = read.lines().get(1).data();
        assertThat(second.getPrice().toPlainString()).isEqualTo("1234.5");
        assertThat(second.getQty()).isEqualTo(7);
        assertThat(second.getTax().toPlainString()).isEqualTo("13");
        assertThat(read.errors()).containsExactly(
                new ImportError(4, "单价", "最多 4 位小数"),
                new ImportError(4, "件数", "要填整数"),
                new ImportError(5, "单价", "不能是负数"));
    }

    /** 规则：编码填成了数字（常规格式），13 位的条码 Excel 显示成 1.23457E+12，也要原样读出 13 位，不能丢位 */
    @Test
    void read_longNumberInGeneralFormat_keepsAllDigits() {
        MockMultipartFile file = file(HEADERS, row(6901234567892L, "条码", null, 123456789012345L, null, null, null));

        Item item = Excel.read(schema(), file).dataOrThrow().getFirst();

        assertThat(item.getCode()).isEqualTo("6901234567892");
        assertThat(item.getPrice().toPlainString()).isEqualTo("123456789012345");
    }

    /** 规则：日期认 Excel 的日期单元格，也认 2026/11/5 这样的文字；没有的日子、认不出的格式报错 */
    @Test
    void read_dates() {
        MockMultipartFile file = file(HEADERS,
                row("A001", "甲", null, null, null, LocalDate.of(2026, 12, 1), null),
                row("A002", "乙", null, null, null, "2026/11/5", null),
                row("A003", "丙", null, null, null, "2026-02-30", null),
                row("A004", "丁", null, null, null, "下周一", null));

        ExcelImport<Item> read = Excel.read(schema(), file);

        assertThat(read.lines().get(0).data().getDue()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(read.lines().get(1).data().getDue()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(read.errors()).containsExactly(
                new ImportError(4, "交期", "没有这一天: 2026-02-30"),
                new ImportError(5, "交期", "日期格式不对，填 2026-11-05 这样的"));
    }

    /** 规则：选项列填显示的文字或值都认，进库的是值；不在选项里的说清可选哪些（停用的不列） */
    @Test
    void read_choice_labelOrValueToValue() {
        MockMultipartFile file = file(HEADERS,
                row("A001", "甲", "面料商", null, null, null, null),
                row("A002", "乙", "trim", null, null, null, null),
                row("A003", "丙", "布料商", null, null, null, null));

        ExcelImport<Item> read = Excel.read(schema(), file);

        assertThat(read.lines()).extracting(line -> line.data().getType()).containsExactly("fabric", "trim", null);
        assertThat(read.errors()).containsExactly(new ImportError(4, "分类", "不在选项里，可选：面料商、辅料商"));
    }

    /** 规则：空行跳过（中间空几行分组），行号还是 Excel 里看到的行号；超长的文字报错 */
    @Test
    void read_blankRowsSkippedRowNumbersKept() {
        MockMultipartFile file = file(HEADERS,
                row("A001", "甲", null, null, null, null, null),
                row("", "  ", null, null, null, null, null),
                row("A002", "一二三四五六七八九十一", null, null, null, null, null));

        ExcelImport<Item> read = Excel.read(schema(), file);

        assertThat(read.lines()).extracting(ExcelImport.Line::row).containsExactly(2, 4);
        assertThat(read.errors()).containsExactly(new ImportError(4, "名称", "最多 10 个字，现在 11 个"));
    }

    /** 规则：同一个文件里编码重复，第一次出现的不算，后面的报"和第 n 行重复" */
    @Test
    void checkUnique_laterDuplicatesReported() {
        MockMultipartFile file = file(HEADERS,
                row("A001", "甲", null, null, null, null, null),
                row("A002", "乙", null, null, null, null, null),
                row("A001", "丙", null, null, null, null, null));
        ExcelImport<Item> read = Excel.read(schema(), file);

        read.checkUnique(Item::getCode, "编码");

        assertThat(read.errors()).containsExactly(new ImportError(4, "编码", "和第 2 行重复"));
    }

    /** 规则：问题太多时 1003 只带前 500 处，提示里说总数和"只列出前 500 处" */
    @Test
    void invalid_tooManyErrors_listsFirst500() {
        List<ImportError> errors = IntStream.rangeClosed(2, 601).mapToObj(r -> new ImportError(r, "名称", "必填")).toList();

        BizException e = ExcelImport.invalid(errors);

        assertThat(e.getMessage()).contains("共 600 处问题").endsWith("（下面只列出前 500 处）");
        assertThat(errors(e)).hasSize(500).first().isEqualTo(new ImportError(2, "名称", "必填"));
    }

    // ---------- 导入：文件本身 ----------

    /** 规则：超过 5000 行整个文件不收，提示拆开 */
    @Test
    void read_over5000Rows_rejected() {
        Object[][] rows = new Object[Excel.MAX_IMPORT_ROWS + 1][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row("A" + i, "甲", null, null, null, null, null);
        }
        MockMultipartFile file = file(HEADERS, rows);

        assertThatThrownBy(() -> Excel.read(schema(), file))
                .isInstanceOf(BizException.class)
                .hasMessage("超过 5000 行了，请拆成几个文件分批导入");
    }

    /** 规则：只收 .xlsx；扩展名对但打不开的、只有表头的，都说清原因 */
    @Test
    void read_badFiles_rejectedWithReason() {
        MockMultipartFile xls = new MockMultipartFile("file", "供应商.xls", Excel.CONTENT_TYPE, new byte[]{1});
        MockMultipartFile notExcel = new MockMultipartFile("file", "供应商.xlsx", Excel.CONTENT_TYPE, "不是表格".getBytes());
        MockMultipartFile headerOnly = file(HEADERS);

        assertThatThrownBy(() -> Excel.read(schema(), xls)).hasMessageStartingWith("只收 .xlsx 文件");
        assertThatThrownBy(() -> Excel.read(schema(), notExcel)).hasMessageStartingWith("文件打不开");
        assertThatThrownBy(() -> Excel.read(schema(), headerOnly)).hasMessageStartingWith("文件里没有数据");
    }

    // ---------- 一个文件几张表（领猫导出的样子） ----------

    /** 物料：一行一个物料 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Material {
        private String code;
        private String name;
    }

    /** SKU：一行一个 SKU，挂在物料编码下 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Sku {
        private String materialCode;
        private String color;
    }

    private static ExcelSchema<Material> materialSchema() {
        return ExcelSchema.of("物料信息", Material::new,
                ExcelColumn.text("物料编码", Material::getCode, Material::setCode).required().code(),
                ExcelColumn.text("物料名称", Material::getName, Material::setName).required())
                .ignore("创建人", "物料图片");                                     // 领猫导出里有、我们不要的列
    }

    private static ExcelSchema<Sku> skuSchema() {
        return ExcelSchema.of("物料信息SKU", Sku::new,
                ExcelColumn.text("物料编码", Sku::getMaterialCode, Sku::setMaterialCode).required().code(),
                ExcelColumn.text("颜色", Sku::getColor, Sku::setColor));
    }

    private static ExcelSchema<Material> unitSchema() {
        return ExcelSchema.of("物料信息多单位", Material::new,
                ExcelColumn.text("物料编码", Material::getCode, Material::setCode).required());
    }

    /**
     * 规则：按工作表的名字读，表的顺序无所谓；可选的表没有不报错；不认识的表（物料信息多供应商）不读；
     * ignore 过的列（领猫的"创建人"）认识、不读。提示不拦导入，和条数一起返回，带着工作表名
     */
    @Test
    void book_readsSheetsByName() {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息SKU", new Object[][]{{"物料编码", "颜色"}, {"M001", "藏青"}, {"M001", "米白"}, {"M002", null}});
        sheets.put("物料信息多供应商", new Object[][]{{"物料编码", "供应商"}, {"M001", "恒丰"}});
        sheets.put("物料信息", new Object[][]{{"物料编码", "创建人", "物料名称"}, {"M001", "张三", "全棉府绸"}, {"M002", "张三", "涤纶里布"}});

        try (ExcelBook book = Excel.open(book(sheets))) {
            ExcelImport<Material> materials = book.sheet(materialSchema());
            ExcelImport<Sku> skus = book.sheet(skuSchema());
            ExcelImport<Material> units = book.optionalSheet(unitSchema());
            skus.note(4, "颜色", "没填颜色，按「均色」导入");
            book.throwIfInvalid();

            assertThat(materials.lines()).extracting(ExcelImport.Line::data)
                    .containsExactly(new Material("M001", "全棉府绸"), new Material("M002", "涤纶里布"));
            assertThat(skus.lines()).hasSize(3);
            assertThat(units.lines()).isEmpty();
            assertThat(book.result(2)).isEqualTo(new ImportResult(2,
                    List.of(new ImportError("物料信息SKU", 4, "颜色", "没填颜色，按「均色」导入"))));
        }
    }

    /**
     * 规则：几张表的问题一起报（1003），每处带工作表名，按读表的顺序；必须有的表没有，算一处问题（行号 0）；
     * 一张表的表头不对，这张表不读，别的表照样查
     */
    @Test
    void book_errorsFromAllSheetsTogether() {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息", new Object[][]{{"物料编码", "物料名称", "乱写的列"}, {"M001", "全棉府绸", "x"}});
        sheets.put("物料信息多单位", new Object[][]{{"单位"}, {"米"}});

        BizException e;
        try (ExcelBook book = Excel.open(book(sheets))) {
            book.sheet(materialSchema());
            book.sheet(skuSchema());
            book.optionalSheet(unitSchema());
            e = catchThrowableOfType(BizException.class, book::throwIfInvalid);
        }

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        assertThat(errors(e)).containsExactly(
                new ImportError("物料信息", 1, "乱写的列", "不认识这一列：表头要和模板里的一样，用不上的列请整列删掉"),
                new ImportError("物料信息SKU", 0, null, "文件里没有「物料信息SKU」这张工作表：表名要和模板里的一样"),
                new ImportError("物料信息多单位", 1, "单位", "不认识这一列：表头要和模板里的一样，用不上的列请整列删掉"),
                new ImportError("物料信息多单位", 1, "物料编码", "缺少这一列，它是必填的"));
    }

    /** 规则：读到的表全是空的（只有表头），整个文件不收 */
    @Test
    void book_allSheetsEmpty_rejected() {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息", new Object[][]{{"物料编码", "物料名称"}});
        sheets.put("物料信息SKU", new Object[][]{{"物料编码", "颜色"}});

        try (ExcelBook book = Excel.open(book(sheets))) {
            book.sheet(materialSchema());
            book.sheet(skuSchema());
            assertThatThrownBy(book::throwIfInvalid).isInstanceOf(BizException.class).hasMessageStartingWith("文件里没有数据");
        }
    }

    /** 规则：几张表的模板，表名用列定义的 name，"填写说明"多一列"工作表"；几张表的导出按同样的表名，改了能原样导回来 */
    @Test
    void book_templateAndExportRoundTrip() throws IOException {
        try (XSSFWorkbook template = new XSSFWorkbook(new ByteArrayInputStream(Excel.template(materialSchema(), skuSchema())))) {
            assertThat(template.getSheetName(0)).isEqualTo("物料信息");
            assertThat(template.getSheetName(1)).isEqualTo("物料信息SKU");
            assertThat(template.getSheetName(2)).isEqualTo("填写说明");
            assertThat(cellTexts(template.getSheet("物料信息").getRow(0))).containsExactly("*物料编码", "*物料名称");
            Sheet guide = template.getSheet("填写说明");
            assertThat(cellTexts(guide.getRow(0))).containsExactly("工作表", "列", "必填", "怎么填", "例子");
            assertThat(guide.getRow(3).getCell(0).getStringCellValue()).isEqualTo("物料信息SKU");
        }

        byte[] exported = Excel.exportBook()
                .sheet(materialSchema(), List.of(new Material("0012", "全棉府绸")))
                .sheet(skuSchema(), List.of(new Sku("0012", "藏青"), new Sku("0012", "米白")))
                .toBytes();

        try (ExcelBook book = Excel.open(xlsx(exported))) {
            ExcelImport<Material> materials = book.sheet(materialSchema());
            ExcelImport<Sku> skus = book.sheet(skuSchema());
            book.throwIfInvalid();
            assertThat(materials.lines()).extracting(ExcelImport.Line::data).containsExactly(new Material("0012", "全棉府绸"));
            assertThat(skus.lines()).extracting(ExcelImport.Line::data)
                    .containsExactly(new Sku("0012", "藏青"), new Sku("0012", "米白"));
        }
    }

    /** 规则：提示最多列 500 条，多的合成最后一条"还有 N 条提示没有列出"（行号 0） */
    @Test
    void importResult_tooManyNotes_lastOneSummarizes() {
        List<ImportError> notes = IntStream.rangeClosed(2, 601).mapToObj(r -> new ImportError(r, "颜色", "新建了颜色")).toList();

        ImportResult result = ImportResult.of(600, notes);

        assertThat(result.notes()).hasSize(500);
        assertThat(result.notes().getLast()).isEqualTo(new ImportError(0, null, "还有 101 条提示没有列出"));
    }

    /** 规则：只读一张表的导入，问题清单里不带 sheet（JSON 里没有这个字段，前端就不显示"工作表"这一列） */
    @Test
    void importError_withoutSheet_notInJson() {
        JsonMapper json = JsonMapper.builder().build();

        assertThat(json.writeValueAsString(new ImportError(2, "名称", "必填")))
                .isEqualTo("{\"row\":2,\"column\":\"名称\",\"message\":\"必填\"}");
        assertThat(json.writeValueAsString(new ImportError("物料信息", 2, "名称", "必填")))
                .startsWith("{\"sheet\":\"物料信息\",");
    }

    // ---------- 导出 ----------

    /**
     * 规则（§9.4）：表头不带 *，只导出的列也在；数字是数字单元格，日期是日期单元格，选项写显示的文字（停用的也翻）；
     * 导出的文件改了还能原样导回来
     */
    @Test
    void export_roundTrip() throws IOException {
        Item item = new Item("0012", "恒丰面料", "old", new BigDecimal("12.3456"), 10,
                LocalDate.of(2026, 12, 1), new BigDecimal("13"), "2026-11-05 10:00:00");

        byte[] exported = Excel.export(schema(), List.of(item));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("供应商");
            assertThat(cellTexts(sheet.getRow(0)))
                    .containsExactly("编码", "名称", "分类", "单价", "件数", "交期", "税率", "创建时间");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("0012");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("旧分类");
            assertThat(row.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(5).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        }

        // 停用的分类导回来会被拦住（不能再用），换成启用的再导
        Item active = new Item("0012", "恒丰面料", "fabric", new BigDecimal("12.3456"), 10,
                LocalDate.of(2026, 12, 1), new BigDecimal("13"), "2026-11-05 10:00:00");
        List<Item> back = Excel.read(schema(), xlsx(Excel.export(schema(), List.of(active)))).dataOrThrow();
        active.setCreatedAt(null);                                   // 只导出的列不读
        assertThat(back).containsExactly(active);
    }

    /** 规则：一次最多导出 5 万行，超了提示缩小筛选范围 */
    @Test
    void export_over50000Rows_rejected() {
        List<Item> rows = Collections.nCopies(Excel.MAX_EXPORT_ROWS + 1, new Item());

        assertThatThrownBy(() -> Excel.export(schema(), rows))
                .isInstanceOf(BizException.class)
                .hasMessage("要导出的超过 5 万行了，请缩小筛选范围再导出");
    }

    /** 规则：下载的文件名是中文，放在 filename*=UTF-8''... 里；导出的文件名带日期 */
    @Test
    void download_utf8FileName() {
        String name = Excel.exportFileName("供应商", LocalDate.of(2026, 11, 5));

        ResponseEntity<byte[]> response = Excel.download(name, new byte[]{1});

        assertThat(name).isEqualTo("供应商-20261105.xlsx");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;")
                .contains("filename*=UTF-8''%E4%BE%9B%E5%BA%94%E5%95%86-20261105.xlsx");
        assertThat(response.getHeaders().getContentType()).hasToString(Excel.CONTENT_TYPE);
    }

    // ---------- 工具 ----------

    private record Dropdown(String formula, int column) {
    }

    private static Object[] row(Object... values) {
        return values;
    }

    /** 现做一个 .xlsx：第一页叫"数据"，第一行是表头 */
    private static MockMultipartFile file(String[] headers, Object[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
            writeRow(sheet.createRow(0), (Object[]) headers);
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i + 1);
                writeRow(row, rows[i]);
                for (Cell cell : row) {
                    if (rows[i][cell.getColumnIndex()] instanceof LocalDate) {
                        cell.setCellStyle(date);
                    }
                }
            }
            return xlsx(toBytes(workbook));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** null 不建单元格；数字写成数字单元格（和用户在 Excel 里直接敲数字一样） */
    private static void writeRow(Row row, Object... values) {
        for (int i = 0; i < values.length; i++) {
            switch (values[i]) {
                case null -> {
                }
                case Number number -> row.createCell(i).setCellValue(number.doubleValue());
                case LocalDate date -> row.createCell(i).setCellValue(date);
                default -> row.createCell(i).setCellValue(values[i].toString());
            }
        }
    }

    /** 现做一个几张表的 .xlsx：表名 → 行（第一行是表头） */
    private static MockMultipartFile book(Map<String, Object[][]> sheets) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            sheets.forEach((name, rows) -> {
                Sheet sheet = workbook.createSheet(name);
                for (int i = 0; i < rows.length; i++) {
                    writeRow(sheet.createRow(i), rows[i]);
                }
            });
            return xlsx(toBytes(workbook));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMultipartFile xlsx(byte[] bytes) {
        return new MockMultipartFile("file", "供应商.xlsx", Excel.CONTENT_TYPE, bytes);
    }

    private static byte[] toBytes(XSSFWorkbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }

    private static List<String> cellTexts(Row row) {
        List<String> texts = new ArrayList<>();
        row.forEach(cell -> texts.add(cell.getStringCellValue()));
        return texts;
    }

    private static List<String> cellTexts(Sheet sheet, int column) {
        List<String> texts = new ArrayList<>();
        sheet.forEach(row -> texts.add(row.getCell(column).getStringCellValue()));
        return texts;
    }

    @SuppressWarnings("unchecked")
    private static List<ImportError> errors(BizException e) {
        return (List<ImportError>) e.getData();
    }

    /** 下拉的出错提示是"停止"：不在选项里的 Excel 当场不让填 */
    @Test
    void template_dropdownStopsInvalidInput() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(Excel.template(schema())))) {
            DataValidation validation = workbook.getSheet("数据").getDataValidations().getFirst();
            assertThat(validation.getErrorStyle()).isEqualTo(DataValidation.ErrorStyle.STOP);
            assertThat(validation.getShowErrorBox()).isTrue();
            assertThat(validation.getRegions().getCellRangeAddress(0).getLastRow()).isEqualTo(Excel.MAX_IMPORT_ROWS);
        }
    }
}
