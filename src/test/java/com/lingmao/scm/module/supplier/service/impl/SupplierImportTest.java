package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.DISABLED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 供应商导入（PRD §10、RFC 004 §9、数据库设计附录 A.1）。文件照领猫导出的样子在内存里现做：
 * "供应商管理" + "银行收付款信息"两张表，列名照领猫，带几列我们不要的（主体公司、创建时间）。数据都是编的
 */
@ExtendWith(MockitoExtension.class)
class SupplierImportTest {

    /** 19 位账号：xlsx 里存的是全号，Excel 显示成 6.22848E+18 */
    private static final String LONG_ACCOUNT = "6228480000000000018";

    private static final String[] MAIN_HEADERS = {"编码", "全称", "简称", "主体公司", "分类", "等级", "结算方式", "是否冻结",
            "付款账户名", "开户行", "账户", "税号", "发票类型", "联系人", "联系电话", "创建时间"};
    private static final String[] BANK_HEADERS = {"编码", "全称", "开户行", "账号", "名称", "状态", "默认排序", "备注"};

    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private DocLogService docLogService;
    @Mock
    private UserService userService;
    @Mock
    private SupplierDicts dicts;
    @Mock
    private SupplierLists lists;

    private SupplierExcelServiceImpl service;
    private final List<Supplier> inserted = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        SupplierFixtures.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        service = new SupplierExcelServiceImpl(supplierMapper, serialNoGenerator, docLogService, userService, dicts, lists,
                SupplierFixtures.CLOCK);
        lenient().when(dicts.options(SupplierDicts.SUPPLIER_CATEGORY)).thenReturn(List.of(
                new DictOption("面料商", "FABRIC", 1), new DictOption("辅料商", "TRIM", 1),
                new DictOption("成衣厂", "GARMENT", 1), new DictOption("加工厂", "PROCESSOR", 1),
                new DictOption("其他", "OTHER", 1)));
        lenient().when(dicts.options(SupplierDicts.SETTLE_METHOD)).thenReturn(List.of(
                new DictOption("月结", "MONTHLY", 1), new DictOption("预付", "PREPAID", 1)));
        lenient().when(dicts.options(SupplierDicts.INVOICE_TYPE)).thenReturn(List.of(
                new DictOption("专票", "SPECIAL", 1), new DictOption("普票", "GENERAL", 1)));
        AtomicLong ids = new AtomicLong(100);
        lenient().doAnswer(inv -> {
            Supplier s = inv.getArgument(0);
            s.setId(ids.incrementAndGet());
            inserted.add(s);
            return 1;
        }).when(supplierMapper).insert(any(Supplier.class));
    }

    @AfterEach
    void tearDown() {
        SupplierFixtures.logout();
    }

    /**
     * 规则：领猫的两张表能导。分类按 Q34 对照（面料 → 面料商，成品加工 → 成衣厂）；"是否冻结"为是的导成停用；
     * 主表没账户的从银行表取启用的、默认排序最前的那个；账号按 xlsx 里存的原文读（19 位不丢）；
     * 导成合作中的记准入人；GYS 流水推到导入的最大号；对不上的等级、占位的发票类型、没用上的账户进提示
     */
    @Test
    void import_lingmaoTwoSheets_ok() throws IOException {
        SupplierFixtures.loginAs(9, "import", "admit");

        ImportResult result = service.importExcel(file(true,
                new Object[]{"GYS0101", "测试面料有限公司甲", "测试甲", null, "面料", "A级", "月结", "否",
                        "测试面料有限公司甲", "测试银行甲支行", new RawNumber(LONG_ACCOUNT), null, "***", "联系人甲", 13800000000L, "2025-01-01"},
                new Object[]{"GYS0102", "测试成衣厂乙", "测试乙", null, "成品加工、辅料", "N级", "月结", "是",
                        null, null, null, "91000000MA0000001R", "专票", "联系人乙", null, null}), ACTIVE);

        assertThat(result.total()).isEqualTo(2);
        assertThat(inserted).hasSize(2);
        Supplier first = inserted.get(0);
        assertThat(first.getStatus()).isEqualTo(ACTIVE);
        assertThat(first.getBankAccount()).isEqualTo(LONG_ACCOUNT);
        assertThat(first.getLevel()).isEqualTo("A");
        assertThat(first.getSettleMethod()).isEqualTo("MONTHLY");
        assertThat(first.getInvoiceType()).isNull();
        assertThat(first.getPhone()).isEqualTo("13800000000");
        assertThat(first.getAdmitBy()).isEqualTo(9L);
        assertThat(first.getAdmitTime()).isNotNull();

        Supplier second = inserted.get(1);
        assertThat(second.getStatus()).isEqualTo(DISABLED);
        assertThat(second.getLevel()).isNull();
        assertThat(second.getCreditCode()).isEqualTo("91000000MA0000001R");
        assertThat(second.getInvoiceType()).isEqualTo("SPECIAL");
        assertThat(second.getBankName()).isEqualTo("测试银行乙二");
        assertThat(second.getBankAccount()).isEqualTo("6217000000000000001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SupplierCategoryRow>> categories = ArgumentCaptor.forClass(List.class);
        verify(supplierMapper, times(2)).insertCategories(categories.capture());
        assertThat(categories.getAllValues().get(0)).extracting(SupplierCategoryRow::getCategory).containsExactly("FABRIC");
        assertThat(categories.getAllValues().get(1)).extracting(SupplierCategoryRow::getCategory).containsExactly("GARMENT", "TRIM");
        verify(serialNoGenerator).raiseTo("GYS", 102);
        assertThat(result.notes()).extracting(ImportError::column).contains("发票类型", "等级", "账号");
    }

    /** 规则：选导成合作中，但没有准入权限 → 403，文件都不读 */
    @Test
    void import_activeWithoutAdmit_forbidden() {
        SupplierFixtures.loginAs(9, "import");

        assertThatThrownBy(() -> service.importExcel(file(false,
                new Object[]{"GYS0101", "测试面料有限公司甲", "测试甲", null, "面料"}), ACTIVE))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        verify(supplierMapper, never()).insert(any(Supplier.class));
    }

    /** 规则：分类对不上（领猫的"鞋材"不在对照里）→ 整批不导（1003），列出值 */
    @Test
    void import_unmatchedCategory_wholeBatchRejected() {
        SupplierFixtures.loginAs(9, "import", "admit");

        assertThatThrownBy(() -> service.importExcel(file(false,
                new Object[]{"GYS0101", "测试面料有限公司甲", "测试甲", null, "面料"},
                new Object[]{"GYS0102", "测试鞋材有限公司乙", "测试乙", null, "鞋材"}), ACTIVE))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
                    assertThat(e.getData().toString()).contains("鞋材");
                });
        verify(supplierMapper, never()).insert(any(Supplier.class));
        verify(supplierMapper, never()).insertCategories(anyList());
    }

    /** 规则：选导成待准入，全部待准入（冻结的也是），不记准入人；不要准入权限 */
    @Test
    void import_pendingAdmit_allPendingIgnoringFrozen() throws IOException {
        SupplierFixtures.loginAs(9, "import");

        service.importExcel(file(false,
                new Object[]{"GYS0101", "测试面料有限公司甲", "测试甲", null, "面料", null, null, "是"}), PENDING_ADMIT);

        assertThat(inserted).singleElement().satisfies(s -> {
            assertThat(s.getStatus()).isEqualTo(PENDING_ADMIT);
            assertThat(s.getAdmitBy()).isNull();
        });
    }

    // ---------------------------------------------------------------- 做文件

    /** xlsx 里存成这个原文的数字单元格（领猫导出的长账号就是这样） */
    private record RawNumber(String stored) {
    }

    /** "供应商管理"一行一个数组，按 MAIN_HEADERS 的顺序，短了的后面空着；withBank 时带上银行表（乙有两个账户） */
    private static MockMultipartFile file(boolean withBank, Object[]... rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet main = book.createSheet("供应商管理");
            fill(main, MAIN_HEADERS, rows);
            if (withBank) {
                fill(book.createSheet("银行收付款信息"), BANK_HEADERS, new Object[][]{
                        {"GYS0102", "测试成衣厂乙", "测试银行乙一", "6217000000000000002", "测试成衣厂乙", "启用", 2L, null},
                        {"GYS0102", "测试成衣厂乙", "测试银行乙二", "6217000000000000001", "测试成衣厂乙", "启用", 1L, null}});
            }
            book.write(out);
            return new MockMultipartFile("file", "供应商.xlsx", "application/octet-stream", out.toByteArray());
        }
    }

    private static void fill(XSSFSheet sheet, String[] headers, Object[][] rows) {
        XSSFRow header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        for (int r = 0; r < rows.length; r++) {
            XSSFRow row = sheet.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                Object value = rows[r][c];
                if (value == null) {
                    continue;
                }
                XSSFCell cell = row.createCell(c);
                if (value instanceof RawNumber raw) {
                    cell.setCellValue(Double.parseDouble(raw.stored()));
                    cell.getCTCell().setV(raw.stored());
                } else if (value instanceof Long number) {
                    cell.setCellValue(number.doubleValue());
                } else {
                    cell.setCellValue(value.toString());
                }
            }
        }
    }
}
