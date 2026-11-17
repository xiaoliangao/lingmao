package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.supplier.dto.CertSaveReq;
import com.lingmao.scm.module.supplier.dto.ChangeVO;
import com.lingmao.scm.module.supplier.dto.SupplierOption;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.dto.SupplierVO;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.service.SupplierCertService;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.supplier.service.SupplierService.Usage;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 供应商在真库上（RFC 004 §13、§15）：外键拦删除、信用代码唯一索引、待批改动的部分唯一索引、审批中的拦法、
 * 申请准入和删资质的行锁，还有列表、导入这些手写 SQL 能不能跑。
 * 每个测试自己建供应商（随机的名字和信用代码），不依赖表是空的。"工厂仓还在"等工厂仓接上（第 3 步）再补
 */
@IntegrationTest
class SupplierServiceImplIT {

    private static final long USER_A = 900_001L;
    private static final long USER_B = 900_002L;
    private static final String[] ALL = {"list", "add", "edit", "remove", "import", "export", "admit", "bank"};

    @Autowired
    private SupplierService supplierService;
    @Autowired
    private SupplierChangeService changeService;
    @Autowired
    private SupplierCertService certService;
    @Autowired
    private SupplierExcelService excelService;
    @Autowired
    private SupplierChangeMapper changeMapper;
    @Autowired
    private GlobalExceptionHandler handler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void login() {
        SupplierFixtures.loginAs(USER_A, ALL);
    }

    @AfterEach
    void logout() {
        SupplierFixtures.logout();
    }

    /**
     * 规则：加工厂新建时一起建工厂仓（GC + 编码里的数字）。被价格档案引用的删不掉，外键拦住返回 1005"已被价格档案引用"；
     * 整个回滚，分类、资质、操作记录、工厂仓都还在。价格删了以后能删，子表和工厂仓一起删干净
     */
    @Test
    void delete_referencedByPrice_returns1005AndRollsBack() {
        SupplierSaveReq req = newReq(true);
        req.setCategories(List.of("FABRIC", "PROCESSOR"));
        long id = create(req);
        String code = jdbcTemplate.queryForObject("SELECT code FROM sup_supplier WHERE id = ?", String.class, id);
        assertThat(jdbcTemplate.queryForObject("SELECT code || ' ' || name FROM bas_warehouse WHERE factory_id = ?", String.class, id))
                .isEqualTo("GC" + code.replaceAll("\\D", "") + " " + req.getShortName() + "仓");
        long certId = addCert(id, "LICENSE", null);
        String suffix = suffix();
        Long categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT分类" + suffix);
        Long materialId = jdbcTemplate.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                VALUES (?, ?, ?, 'FABRIC', 'METER', 'METER') RETURNING id""", Long.class, "ITM" + suffix, "IT 物料", categoryId);
        jdbcTemplate.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, 10, 'METER', 13, CURRENT_DATE)""", id, materialId);

        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> supplierService.delete(id));
        R<Void> r = handler.handleDataIntegrity(e, new MockHttpServletRequest("DELETE", "/api/suppliers/" + id));

        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(r.getMsg()).isEqualTo("已被价格档案引用，不能删除，只能停用");
        assertThat(count("sup_supplier WHERE id = ?", id)).isEqualTo(1);
        assertThat(count("sup_supplier_category WHERE supplier_id = ?", id)).isEqualTo(2);
        assertThat(count("sup_supplier_cert WHERE id = ?", certId)).isEqualTo(1);
        assertThat(count("bas_warehouse WHERE factory_id = ?", id)).isEqualTo(1);
        assertThat(count("biz_doc_log WHERE doc_type = 'SUPPLIER' AND doc_id = ?", id)).isGreaterThan(0);

        jdbcTemplate.update("DELETE FROM prc_price WHERE supplier_id = ?", id);
        jdbcTemplate.update("DELETE FROM bas_material WHERE id = ?", materialId);
        jdbcTemplate.update("DELETE FROM bas_material_category WHERE id = ?", categoryId);
        supplierService.delete(id);
        assertThat(count("sup_supplier WHERE id = ?", id)).isZero();
        assertThat(count("sup_supplier_cert WHERE supplier_id = ?", id)).isZero();
        assertThat(count("biz_doc_log WHERE doc_type = 'SUPPLIER' AND doc_id = ?", id)).isZero();
        assertThat(count("bas_warehouse WHERE factory_id = ?", id)).isZero();
    }

    /**
     * 规则：待批改动里的信用代码不占号；别家先用了这个号，批准时被拦住（1000，说清和哪家重复），
     * 整个回滚：改动还是待批，供应商的信用代码没变
     */
    @Test
    void approve_creditTakenMeanwhile_returns1000AndStaysPending() {
        long a = create(newReq(true));
        activate(a);
        String oldCredit = credit(a);
        String newCredit = randomCredit();
        SupplierSaveReq change = reqFrom(supplierService.detail(a));
        change.setCreditCode(newCredit);
        SupplierSaveResult saved = supplierService.update(a, change);
        assertThat(saved.pending()).extracting(ChangeItem::field).containsExactly("creditCode");

        SupplierSaveReq other = newReq(false);
        other.setCreditCode(newCredit);
        long b = create(other);
        String bCode = supplierService.detail(b).getCode();
        ChangeVO pending = changeService.pending(a);

        SupplierFixtures.loginAs(USER_B, ALL);
        assertThatThrownBy(() -> changeService.approve(a, pending.id()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).contains(bCode).contains("重复");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sup_supplier_change WHERE id = ?", String.class, pending.id()))
                .isEqualTo("PENDING");
        assertThat(credit(a)).isEqualTo(oldCredit);
    }

    /** 规则：信用代码全表唯一，新建时撞了说清和哪家重复（先查一次）；绕过查重直接写，唯一索引兜底 */
    @Test
    void create_duplicateCredit_rejected() {
        SupplierSaveReq first = newReq(true);
        long id = create(first);
        String code = supplierService.detail(id).getCode();
        SupplierSaveReq second = newReq(false);
        second.setCreditCode(first.getCreditCode().toLowerCase());

        assertThatThrownBy(() -> supplierService.create(second))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(code);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO sup_supplier (code, name, short_name, credit_code) VALUES (?, '测试', '测试', ?)",
                "IT" + suffix(), first.getCreditCode()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** 规则：准入审批中保存被拒（1000"先撤回"），资质也不能加；撤回以后又能改 */
    @Test
    void admitting_saveAndCertRejected_untilWithdrawn() {
        long id = create(newReq(true));
        addCert(id, "LICENSE", LocalDate.now().plusYears(1));
        supplierService.submit(id);
        assertThat(status(id)).isEqualTo("ADMITTING");
        SupplierSaveReq req = reqFrom(supplierService.detail(id));
        req.setPhone("13900000000");

        assertThatThrownBy(() -> supplierService.update(id, req))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCodes.BIZ_ERROR);
                    assertThat(e.getMessage()).isEqualTo("准入审批中不能改，先撤回");
                });
        assertThatThrownBy(() -> addCert(id, "OTHER", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("准入审批中");

        supplierService.withdraw(id);
        supplierService.update(id, req);
        assertThat(jdbcTemplate.queryForObject("SELECT phone FROM sup_supplier WHERE id = ?", String.class, id))
                .isEqualTo("13900000000");
    }

    /** 规则：一家只能有一份待批改动——Service 先查一次报 1000，绕过它直接插也被部分唯一索引 uk_sup_change_pending 拦住 */
    @Test
    void pendingChange_onlyOnePerSupplier() {
        long id = create(newReq(true));
        activate(id);
        SupplierSaveReq req = reqFrom(supplierService.detail(id));
        req.setCreditDays(60);
        supplierService.update(id, req);
        req.setCreditDays(90);

        assertThatThrownBy(() -> supplierService.update(id, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经有一份待批的改动");
        SupplierChange second = new SupplierChange();
        second.setSupplierId(id);
        second.setStatus(ChangeStatus.PENDING);
        second.setChanges(List.of(new ChangeItem("creditDays", "账期", "30", "90")));
        second.setApplyBy(USER_A);
        second.setApplyTime(OffsetDateTime.now());
        assertThatThrownBy(() -> changeMapper.insert(second)).isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * 规则：申请准入锁住供应商行（FOR UPDATE）以后，别人删营业执照要等（FOR SHARE）；等到申请准入提交，
     * 看到的已经是"审批中"，删不了（1000）。不会出现"查的时候有执照、提交时执照已经被删了"
     */
    @Test
    void submitHoldsLock_certDeleteWaitsThenRejected() throws Exception {
        long id = create(newReq(true));
        long certId = addCert(id, "LICENSE", null);
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> submit = pool.submit(() -> {
                SupplierFixtures.loginAs(USER_A, ALL);
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                        supplierService.submit(id);       // 行锁拿着，还没提交
                        submitted.countDown();
                        await(release);
                    });
                } finally {
                    SupplierFixtures.logout();
                }
            });
            assertThat(submitted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = pool.submit(() -> {
                SupplierFixtures.loginAs(USER_B, ALL);
                try {
                    certService.delete(id, certId);
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    SupplierFixtures.logout();
                }
            });

            assertThatThrownBy(() -> delete.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            submit.get(10, TimeUnit.SECONDS);
            assertThat(delete.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("准入审批中");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(status(id)).isEqualTo("ADMITTING");
        assertThat(count("sup_supplier_cert WHERE id = ?", certId)).isEqualTo(1);
    }

    /** 列表的筛选、详情的擅长品类、下拉、导出：手写的 SQL 在真库上能跑，筛得对 */
    @Test
    void listDetailOptionsExport_onRealSql() {
        String suffix = suffix();
        Long goodAt = jdbcTemplate.queryForObject(
                "INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT品类" + suffix);
        SupplierSaveReq req = newReq(true);
        req.setCategories(List.of("GARMENT", "FABRIC"));
        req.setGoodAtIds(List.of(goodAt));
        long id = create(req);
        addCert(id, "LICENSE", LocalDate.now().plusDays(10));

        SupplierVO vo = supplierService.detail(id);
        assertThat(vo.getCode()).matches("GYS\\d{4}");
        assertThat(vo.getCategories()).containsExactly("FABRIC", "GARMENT");
        assertThat(vo.getGoodAt()).extracting(SupplierVO.GoodAtVO::name).containsExactly("IT品类" + suffix);
        assertThat(vo.getBankAccount()).contains("****");

        SupplierQuery query = new SupplierQuery();
        query.setKeyword(req.getShortName());
        query.setCategory("GARMENT");
        query.setStatus(List.of(SupplierStatus.PENDING_ADMIT));
        query.setCertExpiring(true);
        List<SupplierRowVO> rows = supplierService.page(query).getList();
        assertThat(rows).extracting(SupplierRowVO::getId).containsExactly(id);
        assertThat(rows.getFirst().getCertCount()).isEqualTo(1);
        assertThat(rows.getFirst().getCertEarliestUntil()).isEqualTo(LocalDate.now().plusDays(10));
        query.setMissingCredit(true);
        assertThat(supplierService.page(query).getList()).isEmpty();
        query.setMissingCredit(null);
        // 几个编码一起查
        query.setKeyword(vo.getCode().toLowerCase() + " GYS-NONE");
        assertThat(supplierService.page(query).getList()).extracting(SupplierRowVO::getId).containsExactly(id);
        query.setKeyword(req.getShortName());
        query.setToApprove(true);
        assertThat(supplierService.page(query).getList()).isEmpty();

        assertThat(supplierService.options(List.of("GARMENT"), Usage.SAMPLE, req.getShortName(), null))
                .extracting(SupplierOption::id).containsExactly(id);
        assertThat(supplierService.options(List.of("PROCESSOR", "GARMENT"), Usage.SAMPLE, req.getShortName(), null))
                .extracting(SupplierOption::id).containsExactly(id);   // 几个分类任一个就算
        assertThat(supplierService.options(List.of("PROCESSOR"), Usage.SAMPLE, req.getShortName(), null)).isEmpty();
        assertThat(supplierService.options(null, Usage.BULK, req.getShortName(), null)).isEmpty();
        assertThat(supplierService.options(null, Usage.BULK, req.getShortName(), id))
                .extracting(SupplierOption::id).containsExactly(id);
        assertThat(excelService.export(query)).isNotEmpty();
        assertThat(supplierService.logs(id)).extracting(log -> log.action()).contains("create", "cert-add");
    }

    /**
     * 导入在真库上：查已有编码、按名称对品类、按显示名对采购员（重名的不算）的 SQL 能跑；编码已存在的整批不导
     */
    @Test
    void import_onRealSql() throws IOException {
        String suffix = suffix();
        jdbcTemplate.update("INSERT INTO bas_category (name, level) VALUES (?, 1)", "IT导入品类" + suffix);
        Long buyer = jdbcTemplate.queryForObject(
                "INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id", Long.class,
                "it_buyer_" + suffix, "IT采购" + suffix);
        jdbcTemplate.update("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?), (?, 'x', ?)",
                "it_dup1_" + suffix, "IT重名" + suffix, "it_dup2_" + suffix, "IT重名" + suffix);
        String code = "ITS" + suffix;
        String code2 = "ITT" + suffix;

        ImportResult result = excelService.importExcel(workbook(
                new String[]{code, "IT导入品类" + suffix, "IT采购" + suffix},
                new String[]{code2, null, "IT重名" + suffix}), SupplierStatus.ACTIVE);

        assertThat(result.total()).isEqualTo(2);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sup_supplier WHERE code = ?", Long.class, code);
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(count("sup_supplier_good_at WHERE supplier_id = ?", id)).isEqualTo(1);
        assertThat(count("sup_supplier_category WHERE supplier_id = ? AND category = 'FABRIC'", id)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT buyer_id FROM sup_supplier WHERE id = ?", Long.class, id)).isEqualTo(buyer);
        assertThat(jdbcTemplate.queryForObject("SELECT buyer_id FROM sup_supplier WHERE code = ?", Long.class, code2)).isNull();
        assertThat(result.notes()).anySatisfy(note -> assertThat(note.message()).contains("IT重名" + suffix));
        assertThatThrownBy(() -> excelService.importExcel(workbook(new String[]{code, null, null}), SupplierStatus.ACTIVE))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID));
    }

    // ---------------------------------------------------------------- 造数据

    private long create(SupplierSaveReq req) {
        return supplierService.create(req).id();
    }

    /** 新建用的请求：名字随机；complete 时带信用代码（随机的、校验码对的假号） */
    private static SupplierSaveReq newReq(boolean complete) {
        String suffix = suffix();
        SupplierSaveReq req = new SupplierSaveReq();
        req.setName("测试供应商有限公司" + suffix);
        req.setShortName("测试" + suffix);
        req.setCreditCode(complete ? randomCredit() : null);
        req.setCategories(List.of("FABRIC"));
        req.setContact("联系人甲");
        req.setPhone("13800000000");
        req.setSettleMethod("MONTHLY");
        req.setCreditDays(30);
        req.setInvoiceType("SPECIAL");
        req.setBankName("测试银行某某支行");
        req.setBankAccount("6222000011112222333");
        req.setBankAccountName("测试户名");
        return req;
    }

    /** 照详情填一份保存请求（前端打开编辑框就是这样：账号是遮过的） */
    private static SupplierSaveReq reqFrom(SupplierVO vo) {
        SupplierSaveReq req = new SupplierSaveReq();
        req.setName(vo.getName());
        req.setShortName(vo.getShortName());
        req.setCreditCode(vo.getCreditCode());
        req.setLevel(vo.getLevel());
        req.setCategories(vo.getCategories());
        req.setGoodAtIds(vo.getGoodAt().stream().map(SupplierVO.GoodAtVO::id).toList());
        req.setContact(vo.getContact());
        req.setPhone(vo.getPhone());
        req.setWechat(vo.getWechat());
        req.setAddress(vo.getAddress());
        req.setSettleMethod(vo.getSettleMethod());
        req.setCreditDays(vo.getCreditDays());
        req.setInvoiceType(vo.getInvoiceType());
        req.setTaxPct(vo.getTaxPct());
        req.setBankName(vo.getBankName());
        req.setBankAccount(vo.getBankAccount());
        req.setBankAccountName(vo.getBankAccountName());
        req.setBuyerId(vo.getBuyerId());
        req.setRemark(vo.getRemark());
        return req;
    }

    private long addCert(long supplierId, String type, LocalDate until) {
        CertSaveReq req = new CertSaveReq();
        req.setCertType(type);
        req.setFileUrl("/files/2026/11/it-" + suffix() + ".pdf");
        req.setFileName("证照.pdf");
        req.setValidUntil(until);
        return certService.add(supplierId, req);
    }

    /** 跳过准入流程直接改成合作中（造数据用） */
    private void activate(long id) {
        jdbcTemplate.update("UPDATE sup_supplier SET status = 'ACTIVE', admit_time = now() WHERE id = ?", id);
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM sup_supplier WHERE id = ?", String.class, id);
    }

    private String credit(long id) {
        return jdbcTemplate.queryForObject("SELECT credit_code FROM sup_supplier WHERE id = ?", String.class, id);
    }

    private int count(String fromWhere, Object... args) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String suffix() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
    }

    /** 随机的假信用代码：91 + 15 位随机字符 + 算出来的校验码 */
    static String randomCredit() {
        StringBuilder code = new StringBuilder("91");
        while (code.length() < CreditCode.LENGTH - 1) {
            code.append(CreditCode.CHARSET.charAt(ThreadLocalRandom.current().nextInt(CreditCode.CHARSET.length())));
        }
        return code.toString() + CreditCode.checkChar(code.toString());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等太久了");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** "供应商管理"，一行一个 {编码, 擅长类目, 采购员}；全称、简称由编码拼，分类都是面料 */
    private static MockMultipartFile workbook(String[]... rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("供应商管理");
            String[] headers = {"编码", "全称", "简称", "分类", "擅长类目", "采购员"};
            XSSFRow header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                String code = rows[r][0];
                String[] values = {code, "测试导入有限公司" + code, "测试导入" + code, "面料", rows[r][1], rows[r][2]};
                XSSFRow row = sheet.createRow(r + 1);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] != null) {
                        row.createCell(i).setCellValue(values[i]);
                    }
                }
            }
            book.write(out);
            return new MockMultipartFile("file", "供应商.xlsx", "application/octet-stream", out.toByteArray());
        }
    }
}
