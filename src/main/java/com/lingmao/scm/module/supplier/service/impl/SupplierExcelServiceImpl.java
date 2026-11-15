package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelBook;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelOption;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.SupplierBankExcelRow;
import com.lingmao.scm.module.supplier.dto.SupplierExcelRow;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.entity.CategoryName;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.entity.SupplierGoodAtRow;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.DISABLED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;

/**
 * 供应商的导入导出（RFC 004 §9、数据库设计附录 A.1）。模板就是领猫导出的样子，领猫导出的文件不改就能导。
 */
@Service
@RequiredArgsConstructor
public class SupplierExcelServiceImpl implements SupplierExcelService {

    static final String MAIN_SHEET = "供应商管理";
    static final String BANK_SHEET = "银行收付款信息";

    /** 领猫的分类 → 我们的分类（Q34 的默认做法）。我们自己的分类名、value 也认 */
    static final Map<String, String> LINGMAO_CATEGORIES = Map.of(
            "面料", "FABRIC",
            "辅料", "TRIM",
            "成品加工", SupplierDicts.GARMENT,
            "物料采购", "OTHER",
            "质检机构", "OTHER");

    /** 一格里填几个值（分类、擅长类目）的分隔符 */
    private static final Pattern SPLIT = Pattern.compile("[,，、/;；\\s]+");

    /** 领猫编码里能推流水的：GYS + 4 位数字 */
    private static final Pattern SERIAL_CODE = Pattern.compile("^GYS(\\d{4})$");

    /** 领猫导出账号没有权限时，发票类型导出成一串 * */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\*+$");

    /** "是否冻结"填这些的导成停用 */
    private static final Set<String> FROZEN = Set.of("是", "冻结");

    /** 银行收付款信息里"状态"是这些的不导 */
    private static final Set<String> BANK_DISABLED = Set.of("停用", "禁用", "否");

    /** 领猫的自定义列，原样进领猫附加属性（Q36） */
    private static final List<String> ATT_COLUMNS = List.of("Att01", "Att02", "Att03", "Att04", "Att05",
            "Att06", "Att07", "Att08", "Att09", "Att10");

    /** 领猫有、我们不要的列（附录 A.1 写"丢弃"的） */
    private static final String[] IGNORED = {"主体公司", "操作人", "操作时间", "创建人", "创建时间"};

    /** 导出时一次补多少家的分类、品类、采购员（IN 的参数个数有上限） */
    private static final int EXPORT_CHUNK = 1000;

    private final SupplierMapper supplierMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final DocLogService docLogService;
    private final UserService userService;
    private final SupplierDicts dicts;
    private final SupplierLists lists;
    private final Clock clock;

    // ------------------------------------------------------------------ 列定义

    /** "供应商管理"：领猫的列 + 我们多的账期、税率、微信；最后一列状态只导出 */
    ExcelSchema<SupplierExcelRow> schema() {
        return ExcelSchema.of(MAIN_SHEET, SupplierExcelRow::new, mainColumns(true).toArray(ExcelColumn[]::new))
                .ignore(IGNORED);
    }

    /** 导出：不出账号、户名、开户行（RFC 004 §5） */
    private ExcelSchema<SupplierExcelRow> exportSchema() {
        return ExcelSchema.of(MAIN_SHEET, SupplierExcelRow::new, mainColumns(false).toArray(ExcelColumn[]::new));
    }

    private List<ExcelColumn<SupplierExcelRow, ?>> mainColumns(boolean withBank) {
        List<ExcelColumn<SupplierExcelRow, ?>> columns = new ArrayList<>();
        columns.add(ExcelColumn.text("编码", SupplierExcelRow::getCode, SupplierExcelRow::setCode)
                .required().code().maxLength(32).example("GYS0001").note("领猫的供应商编码原样填。只新增，已有的编码算错"));
        columns.add(ExcelColumn.text("全称", SupplierExcelRow::getName, SupplierExcelRow::setName).required().maxLength(100));
        columns.add(ExcelColumn.text("简称", SupplierExcelRow::getShortName, SupplierExcelRow::setShortName)
                .maxLength(50).note("空着就用全称"));
        columns.add(ExcelColumn.text("分类", SupplierExcelRow::getCategories, SupplierExcelRow::setCategories)
                .required().maxLength(200).example("面料商、辅料商")
                .note("几个用逗号或顿号隔开。可选：" + labels(SupplierDicts.SUPPLIER_CATEGORY)
                        + "；领猫的面料、辅料、成品加工、物料采购、质检机构也认"));
        columns.add(ExcelColumn.text("擅长类目大类", SupplierExcelRow::getGoodAtTop, SupplierExcelRow::setGoodAtTop)
                .maxLength(500).note("按名称对品类的第一级，对不上的存进领猫附加属性"));
        columns.add(ExcelColumn.text("擅长类目", SupplierExcelRow::getGoodAt, SupplierExcelRow::setGoodAt)
                .maxLength(500).note("按名称对品类，对不上的存进领猫附加属性"));
        columns.add(ExcelColumn.text("等级", SupplierExcelRow::getLevel, SupplierExcelRow::setLevel)
                .maxLength(10).note("A、B、C、D，带\"级\"字也行"));
        columns.add(ExcelColumn.choice("结算方式", options(SupplierDicts.SETTLE_METHOD),
                SupplierExcelRow::getSettleMethod, SupplierExcelRow::setSettleMethod));
        columns.add(ExcelColumn.text("是否指定", SupplierExcelRow::getDesignated, SupplierExcelRow::setDesignated).maxLength(100));
        columns.add(ExcelColumn.text("采购员", SupplierExcelRow::getBuyer, SupplierExcelRow::setBuyer)
                .maxLength(50).note("填用户的显示名，对不上的留空"));
        columns.add(ExcelColumn.text("是否冻结", SupplierExcelRow::getFrozen, SupplierExcelRow::setFrozen)
                .maxLength(10).note("填\"是\"的导成停用（导成待准入时不看这一列）"));
        if (withBank) {
            columns.add(ExcelColumn.text("付款账户名", SupplierExcelRow::getBankAccountName, SupplierExcelRow::setBankAccountName)
                    .maxLength(100));
            columns.add(ExcelColumn.text("开户行", SupplierExcelRow::getBankName, SupplierExcelRow::setBankName).maxLength(100));
            columns.add(ExcelColumn.text("账户", SupplierExcelRow::getBankAccount, SupplierExcelRow::setBankAccount)
                    .code().raw().maxLength(40).note("领猫导出后不要在 Excel 里另存，长账号的后几位会变成 0"));
        }
        columns.add(ExcelColumn.text("税号", SupplierExcelRow::getCreditCode, SupplierExcelRow::setCreditCode)
                .code().maxLength(40).note("统一社会信用代码，18 位。格式不对的这一格不导，其余照导"));
        columns.add(ExcelColumn.text("发票类型", SupplierExcelRow::getInvoiceType, SupplierExcelRow::setInvoiceType)
                .maxLength(64).note("可选：" + labels(SupplierDicts.INVOICE_TYPE)));
        columns.add(ExcelColumn.text("地址电话", SupplierExcelRow::getInvoiceAddressPhone, SupplierExcelRow::setInvoiceAddressPhone)
                .maxLength(255));
        columns.add(ExcelColumn.text("联系人", SupplierExcelRow::getContact, SupplierExcelRow::setContact).maxLength(50));
        columns.add(ExcelColumn.text("联系电话", SupplierExcelRow::getPhone, SupplierExcelRow::setPhone).code().raw().maxLength(32));
        columns.add(ExcelColumn.text("传真", SupplierExcelRow::getFax, SupplierExcelRow::setFax).maxLength(64));
        columns.add(ExcelColumn.text("公司地址", SupplierExcelRow::getAddress, SupplierExcelRow::setAddress).maxLength(255));
        columns.add(ExcelColumn.text("备注", SupplierExcelRow::getRemark, SupplierExcelRow::setRemark).maxLength(500));
        for (int i = 0; i < ATT_COLUMNS.size(); i++) {
            int index = i;
            columns.add(ExcelColumn.<SupplierExcelRow>text(ATT_COLUMNS.get(i), row -> att(row, index),
                    (row, value) -> setAtt(row, index, value)).maxLength(255));
        }
        columns.add(ExcelColumn.text("外部供应商编码", SupplierExcelRow::getExternalCode, SupplierExcelRow::setExternalCode)
                .code().maxLength(64));
        columns.add(ExcelColumn.integer("账期", SupplierExcelRow::getCreditDays, SupplierExcelRow::setCreditDays)
                .nonNegative().check(days -> days > 3650 ? "最多 3650 天" : null).note("天数"));
        columns.add(ExcelColumn.decimal("税率", 2, SupplierExcelRow::getTaxPct, SupplierExcelRow::setTaxPct)
                .percent().nonNegative().check(pct -> pct.compareTo(BigDecimal.valueOf(100)) > 0 ? "不能超过 100%" : null));
        columns.add(ExcelColumn.text("微信", SupplierExcelRow::getWechat, SupplierExcelRow::setWechat).maxLength(64));
        columns.add(ExcelColumn.text("状态", SupplierExcelRow::getStatus, null));
        return columns;
    }

    /** "银行收付款信息"（领猫的第二张表，可以没有） */
    ExcelSchema<SupplierBankExcelRow> bankSchema() {
        return ExcelSchema.of(BANK_SHEET, SupplierBankExcelRow::new,
                ExcelColumn.text("编码", SupplierBankExcelRow::getCode, SupplierBankExcelRow::setCode).required().code().maxLength(32),
                ExcelColumn.text("全称", SupplierBankExcelRow::getName, SupplierBankExcelRow::setName).maxLength(100),
                ExcelColumn.text("开户行", SupplierBankExcelRow::getBankName, SupplierBankExcelRow::setBankName).maxLength(100),
                ExcelColumn.text("账号", SupplierBankExcelRow::getBankAccount, SupplierBankExcelRow::setBankAccount)
                        .code().raw().maxLength(40),
                ExcelColumn.text("名称", SupplierBankExcelRow::getAccountName, SupplierBankExcelRow::setAccountName).maxLength(100),
                ExcelColumn.text("状态", SupplierBankExcelRow::getStatus, SupplierBankExcelRow::setStatus).maxLength(10),
                ExcelColumn.integer("默认排序", SupplierBankExcelRow::getSort, SupplierBankExcelRow::setSort),
                ExcelColumn.text("备注", SupplierBankExcelRow::getRemark, SupplierBankExcelRow::setRemark).maxLength(255));
    }

    private List<ExcelOption> options(String type) {
        return dicts.options(type).stream().map(o -> new ExcelOption(o.label(), o.value(), o.enabled())).toList();
    }

    private String labels(String type) {
        return dicts.options(type).stream().filter(DictOption::enabled).map(DictOption::label).collect(Collectors.joining("、"));
    }

    private static String att(SupplierExcelRow row, int index) {
        return switch (index) {
            case 0 -> row.getAtt01();
            case 1 -> row.getAtt02();
            case 2 -> row.getAtt03();
            case 3 -> row.getAtt04();
            case 4 -> row.getAtt05();
            case 5 -> row.getAtt06();
            case 6 -> row.getAtt07();
            case 7 -> row.getAtt08();
            case 8 -> row.getAtt09();
            default -> row.getAtt10();
        };
    }

    private static void setAtt(SupplierExcelRow row, int index, String value) {
        List<BiConsumer<SupplierExcelRow, String>> setters = List.of(SupplierExcelRow::setAtt01, SupplierExcelRow::setAtt02,
                SupplierExcelRow::setAtt03, SupplierExcelRow::setAtt04, SupplierExcelRow::setAtt05, SupplierExcelRow::setAtt06,
                SupplierExcelRow::setAtt07, SupplierExcelRow::setAtt08, SupplierExcelRow::setAtt09, SupplierExcelRow::setAtt10);
        setters.get(index).accept(row, value);
    }

    // ------------------------------------------------------------------ 模板、导入

    @Override
    public byte[] importTemplate() {
        return Excel.template(schema(), bankSchema());
    }

    /** 准备好要写的一家 */
    record Prepared(Supplier supplier, List<String> categories, List<Long> goodAt) {
    }

    @Override
    @Transactional
    public ImportResult importExcel(MultipartFile file, SupplierStatus status) {
        SupplierStatus target = status == null ? ACTIVE : status;
        if (target != ACTIVE && target != PENDING_ADMIT) {
            throw new BizException(400, "导入只能导成合作中或者待准入");
        }
        if (target == ACTIVE && !SupplierPerms.has(SupplierPerms.ADMIT)) {
            throw new BizException(403, "导成合作中要有准入审批的权限；没有的话请选\"待准入\"");
        }
        try (ExcelBook book = Excel.open(file)) {
            ExcelImport<SupplierExcelRow> main = book.sheet(schema());
            ExcelImport<SupplierBankExcelRow> banks = book.optionalSheet(bankSchema());
            List<Prepared> prepared = prepare(main, banks, target);
            book.throwIfInvalid();
            write(prepared);
            return book.result(prepared.size());
        }
    }

    /** 逐行翻成实体，问题记进 main / banks（拦导入），提示合成几条（不拦） */
    List<Prepared> prepare(ExcelImport<SupplierExcelRow> main, ExcelImport<SupplierBankExcelRow> banks, SupplierStatus target) {
        List<ExcelImport.Line<SupplierExcelRow>> lines = main.lines();
        Notes notes = new Notes();
        main.checkUnique(SupplierExcelRow::getCode, "编码");
        Set<String> codes = lines.stream().map(l -> l.data().getCode()).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> existing = codes.isEmpty() ? Set.of() : Set.copyOf(supplierMapper.selectExistingCodes(codes));
        Map<String, String> categoryMap = categoryMap();
        Map<String, String> invoiceMap = dictMap(SupplierDicts.INVOICE_TYPE);
        CategoryMatcher goodAt = new CategoryMatcher(supplierMapper.selectEnabledCategories());
        Map<String, Long> buyers = userService.idsByNickname(lines.stream().map(l -> l.data().getBuyer())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String, SupplierBankExcelRow> pickedBanks = pickBanks(main, banks);

        List<Prepared> result = new ArrayList<>();
        for (ExcelImport.Line<SupplierExcelRow> line : lines) {
            SupplierExcelRow r = line.data();
            int row = line.row();
            if (r.getCode() != null && existing.contains(r.getCode())) {
                main.error(row, "编码", "已经有这个编码的供应商了：导入只新增，不改已有的");
            }
            Supplier s = new Supplier();
            Map<String, String> legacy = new LinkedHashMap<>();
            s.setCode(r.getCode());
            s.setName(r.getName());
            s.setShortName(r.getShortName());
            if (s.getShortName() == null && r.getName() != null) {
                s.setShortName(r.getName().length() > 50 ? r.getName().substring(0, 50) : r.getName());
                notes.add(row, "简称", "的简称是空的，用了全称", null);
            }

            List<String> categories = new ArrayList<>();
            List<String> unmatched = new ArrayList<>();
            for (String part : split(r.getCategories())) {
                String value = categoryMap.get(part);
                if (value == null) {
                    unmatched.add(part);
                } else if (!categories.contains(value)) {
                    categories.add(value);
                }
            }
            if (!unmatched.isEmpty()) {
                main.error(row, "分类", "「" + String.join("、", unmatched) + "」对不上分类。可选："
                        + labels(SupplierDicts.SUPPLIER_CATEGORY) + "；领猫的面料、辅料、成品加工、物料采购、质检机构也认");
            }

            List<Long> goodAtIds = new ArrayList<>();
            goodAt.match(split(r.getGoodAtTop()), true, goodAtIds, legacy, "擅长类目大类");
            goodAt.match(split(r.getGoodAt()), false, goodAtIds, legacy, "擅长类目");
            for (String key : List.of("擅长类目大类", "擅长类目")) {
                if (legacy.containsKey(key)) {
                    notes.add(row, key, "有对不上品类的，存进了领猫附加属性", legacy.get(key));
                }
            }

            String level = r.getLevel() == null ? null : r.getLevel().replace("级", "").strip().toUpperCase(Locale.ROOT);
            if (level != null && !level.matches("[ABCD]")) {
                notes.add(row, "等级", "的等级不是 A–D，没导", r.getLevel());
                level = null;
            }
            s.setLevel(level);
            s.setSettleMethod(r.getSettleMethod());

            if (r.getBuyer() != null) {
                s.setBuyerId(buyers.get(r.getBuyer()));
                if (s.getBuyerId() == null) {
                    notes.add(row, "采购员", "的采购员对不上用户（没有这个显示名、重名或者停用了），留空了", r.getBuyer());
                }
            }
            boolean frozen = r.getFrozen() != null && FROZEN.contains(r.getFrozen());
            s.setStatus(target == ACTIVE && frozen ? DISABLED : target);

            String credit = CreditCode.normalize(r.getCreditCode());
            String problem = CreditCode.problem(credit);
            if (problem != null) {
                main.note(row, "税号", problem + "，这一格没导");
                credit = null;
            }
            r.setCreditCode(credit);
            s.setCreditCode(credit);

            if (r.getInvoiceType() != null) {
                if (PLACEHOLDER.matcher(r.getInvoiceType()).matches()) {
                    notes.add(row, "发票类型", "的发票类型是一串 *（领猫导出的账号没有权限看），没导", null);
                } else if (invoiceMap.containsKey(r.getInvoiceType())) {
                    s.setInvoiceType(invoiceMap.get(r.getInvoiceType()));
                } else {
                    main.error(row, "发票类型", "「" + r.getInvoiceType() + "」不在选项里，可选：" + labels(SupplierDicts.INVOICE_TYPE));
                }
            }

            s.setBankAccountName(r.getBankAccountName());
            s.setBankName(r.getBankName());
            s.setBankAccount(BankAccounts.normalize(r.getBankAccount()));
            SupplierBankExcelRow bank = r.getCode() == null ? null : pickedBanks.get(r.getCode());
            if (s.getBankAccount() == null && bank != null) {
                s.setBankName(bank.getBankName());
                s.setBankAccount(BankAccounts.normalize(bank.getBankAccount()));
                s.setBankAccountName(bank.getAccountName());
            }
            s.setContact(r.getContact());
            s.setPhone(r.getPhone());
            s.setWechat(r.getWechat());
            s.setAddress(r.getAddress());
            s.setRemark(r.getRemark());
            s.setCreditDays(r.getCreditDays());
            s.setTaxPct(r.getTaxPct());

            putIfPresent(legacy, "是否指定", r.getDesignated());
            putIfPresent(legacy, "地址电话", r.getInvoiceAddressPhone());
            putIfPresent(legacy, "传真", r.getFax());
            for (int i = 0; i < ATT_COLUMNS.size(); i++) {
                putIfPresent(legacy, ATT_COLUMNS.get(i), att(r, i));
            }
            putIfPresent(legacy, "外部供应商编码", r.getExternalCode());
            s.setLegacyAttrs(legacy.isEmpty() ? null : legacy);
            result.add(new Prepared(s, categories, goodAtIds));
        }

        // 信用代码：同一个文件里重复、和库里的重复，整批不导（RFC 004 §9）
        main.checkUnique(SupplierExcelRow::getCreditCode, "税号");
        Set<String> credits = lines.stream().map(l -> l.data().getCreditCode()).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!credits.isEmpty()) {
            Map<String, Supplier> taken = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                            .select(Supplier::getCode, Supplier::getShortName, Supplier::getCreditCode)
                            .in(Supplier::getCreditCode, credits))
                    .stream().collect(Collectors.toMap(Supplier::getCreditCode, Function.identity()));
            for (ExcelImport.Line<SupplierExcelRow> line : lines) {
                Supplier other = taken.get(line.data().getCreditCode());
                if (other != null) {
                    main.error(line.row(), "税号", "和已有的 " + other.getCode() + " " + other.getShortName() + " 重复");
                }
            }
        }
        notes.flushTo(main);
        return result;
    }

    /**
     * 银行收付款信息：一期一家一个账户（附录 A.1）。主表"账户"有值用主表；没有的，取这家启用的、默认排序最前的一条。
     * 没用上的列进提示；编码在主表里没有的进提示；全称和主表对不上的算错。返回 编码 → 选中的那一条
     */
    private Map<String, SupplierBankExcelRow> pickBanks(ExcelImport<SupplierExcelRow> main, ExcelImport<SupplierBankExcelRow> banks) {
        Map<String, SupplierExcelRow> mainByCode = new HashMap<>();
        main.lines().forEach(l -> {
            if (l.data().getCode() != null) {
                mainByCode.putIfAbsent(l.data().getCode(), l.data());
            }
        });
        Map<String, List<ExcelImport.Line<SupplierBankExcelRow>>> byCode = banks.lines().stream()
                .filter(l -> l.data().getCode() != null)
                .collect(Collectors.groupingBy(l -> l.data().getCode(), LinkedHashMap::new, Collectors.toList()));
        Notes notes = new Notes();
        Map<String, SupplierBankExcelRow> picked = new HashMap<>();
        byCode.forEach((code, accounts) -> {
            SupplierExcelRow owner = mainByCode.get(code);
            if (owner == null) {
                accounts.forEach(l -> notes.add(l.row(), "编码", "的编码在「供应商管理」里没有，没导", code));
                return;
            }
            for (ExcelImport.Line<SupplierBankExcelRow> l : accounts) {
                if (l.data().getName() != null && owner.getName() != null && !l.data().getName().equals(owner.getName())) {
                    banks.error(l.row(), "全称", "和「供应商管理」里 " + code + " 的全称「" + owner.getName() + "」对不上");
                }
            }
            List<ExcelImport.Line<SupplierBankExcelRow>> usable = accounts.stream()
                    .filter(l -> l.data().getBankAccount() != null)
                    .filter(l -> l.data().getStatus() == null || !BANK_DISABLED.contains(l.data().getStatus()))
                    .sorted(Comparator.comparing((ExcelImport.Line<SupplierBankExcelRow> l) -> l.data().getSort(),
                            Comparator.nullsLast(Comparator.naturalOrder())).thenComparingInt(ExcelImport.Line::row))
                    .toList();
            ExcelImport.Line<SupplierBankExcelRow> chosen = owner.getBankAccount() != null || usable.isEmpty() ? null : usable.getFirst();
            if (chosen != null) {
                picked.put(code, chosen.data());
            }
            for (ExcelImport.Line<SupplierBankExcelRow> l : accounts) {
                if (l != chosen) {
                    notes.add(l.row(), "账号", "的账户没导（一期一家只存一个账户：主表有账户的用主表，没有的取启用的、默认排序最前的一个）", code);
                }
            }
        });
        notes.flushTo(banks);
        return picked;
    }

    /** 分类的对照：我们的分类名、value（启用的），再加领猫的五个分类名 */
    private Map<String, String> categoryMap() {
        Map<String, String> map = dictMap(SupplierDicts.SUPPLIER_CATEGORY);
        Set<String> enabled = new LinkedHashSet<>(map.values());
        LINGMAO_CATEGORIES.forEach((name, value) -> {
            if (enabled.contains(value)) {
                map.putIfAbsent(name, value);
            }
        });
        return map;
    }

    /** 字典的显示文字、value → value，只要启用的 */
    private Map<String, String> dictMap(String type) {
        Map<String, String> map = new HashMap<>();
        for (DictOption option : dicts.options(type)) {
            if (option.enabled()) {
                map.putIfAbsent(option.label(), option.value());
                map.putIfAbsent(option.value(), option.value());
            }
        }
        return map;
    }

    private static List<String> split(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(SPLIT.split(text.strip())).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** 写库：一家一家插，分类、擅长品类跟着插，每家一条"导入"记录；最后把 GYS 流水推到导入的最大号 */
    private void write(List<Prepared> prepared) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Long me = LoginUser.currentUserId();
        int maxSerial = 0;
        for (Prepared p : prepared) {
            Supplier s = p.supplier();
            if (s.getStatus() != PENDING_ADMIT) {
                // 导成合作中、停用的：准入人、准入时间记导入人、导入时间（RFC 004 §9）
                s.setAdmitBy(me);
                s.setAdmitTime(now);
            }
            supplierMapper.insert(s);
            long id = s.getId();
            if (!p.categories().isEmpty()) {
                supplierMapper.insertCategories(p.categories().stream().map(c -> new SupplierCategoryRow(id, c)).toList());
            }
            if (!p.goodAt().isEmpty()) {
                supplierMapper.insertGoodAt(p.goodAt().stream().map(c -> new SupplierGoodAtRow(id, c)).toList());
            }
            docLogService.add(BizType.SUPPLIER, id, "import", null, s.getStatus(), null);
            // 第 3 步：工厂仓。分类含成衣厂、加工厂的，warehouseService.syncFactoryWarehouse(id, code, shortName, true)
            Matcher serial = SERIAL_CODE.matcher(s.getCode());
            if (serial.matches()) {
                maxSerial = Math.max(maxSerial, Integer.parseInt(serial.group(1)));
            }
        }
        if (maxSerial > 0) {
            serialNoGenerator.raiseTo(BizType.SUPPLIER.getPrefix(), maxSerial);
        }
    }

    // ------------------------------------------------------------------ 导出

    @Override
    public byte[] export(SupplierQuery query) {
        List<Supplier> suppliers = supplierMapper.selectList(lists.wrapper(query).last("LIMIT " + (Excel.MAX_EXPORT_ROWS + 1)));
        if (suppliers.size() > Excel.MAX_EXPORT_ROWS) {
            // 和 Excel.export 的提示一样；先拦住，不用为了报错把五万家的分类、品类都查一遍
            throw new BizException("要导出的超过 " + Excel.MAX_EXPORT_ROWS / 10_000 + " 万行了，请缩小筛选范围再导出");
        }
        List<SupplierExcelRow> rows = new ArrayList<>();
        for (int from = 0; from < suppliers.size(); from += EXPORT_CHUNK) {
            List<Supplier> chunk = suppliers.subList(from, Math.min(from + EXPORT_CHUNK, suppliers.size()));
            List<Long> ids = chunk.stream().map(Supplier::getId).toList();
            Map<Long, List<String>> categories = lists.categoriesOf(ids);
            Map<Long, List<String>> goodAt = lists.goodAtNamesOf(ids);
            Map<Long, String> buyers = lists.buyerNames(chunk);
            for (Supplier s : chunk) {
                rows.add(toExcelRow(s, categories.getOrDefault(s.getId(), List.of()),
                        goodAt.getOrDefault(s.getId(), List.of()), buyers.get(s.getBuyerId())));
            }
        }
        return Excel.export(exportSchema(), rows);
    }

    private SupplierExcelRow toExcelRow(Supplier s, List<String> categories, List<String> goodAt, String buyer) {
        SupplierExcelRow row = new SupplierExcelRow();
        row.setCode(s.getCode());
        row.setName(s.getName());
        row.setShortName(s.getShortName());
        row.setCategories(categories.stream().map(c -> dicts.labelOf(SupplierDicts.SUPPLIER_CATEGORY, c))
                .collect(Collectors.joining("、")));
        row.setGoodAt(goodAt.isEmpty() ? null : String.join("、", goodAt));
        row.setLevel(s.getLevel());
        row.setSettleMethod(s.getSettleMethod());
        row.setBuyer(buyer);
        row.setFrozen(s.getStatus() == DISABLED ? "是" : "否");
        row.setCreditCode(s.getCreditCode());
        row.setInvoiceType(dicts.labelOf(SupplierDicts.INVOICE_TYPE, s.getInvoiceType()));
        row.setContact(s.getContact());
        row.setPhone(s.getPhone());
        row.setAddress(s.getAddress());
        row.setRemark(s.getRemark());
        Map<String, String> legacy = s.getLegacyAttrs() == null ? Map.of() : s.getLegacyAttrs();
        row.setDesignated(legacy.get("是否指定"));
        row.setInvoiceAddressPhone(legacy.get("地址电话"));
        row.setFax(legacy.get("传真"));
        for (int i = 0; i < ATT_COLUMNS.size(); i++) {
            setAtt(row, i, legacy.get(ATT_COLUMNS.get(i)));
        }
        row.setExternalCode(legacy.get("外部供应商编码"));
        row.setCreditDays(s.getCreditDays());
        row.setTaxPct(s.getTaxPct() == null ? null : s.getTaxPct().stripTrailingZeros());
        row.setWechat(s.getWechat());
        row.setStatus(s.getStatus() == null ? null : s.getStatus().getLabel());
        return row;
    }

    // ------------------------------------------------------------------ 小工具

    /** 擅长类目按名称对品类：同名的不止一个（不同上级下）不知道是哪个，算对不上 */
    static final class CategoryMatcher {
        private final Map<String, List<CategoryName>> byName;

        CategoryMatcher(List<CategoryName> categories) {
            this.byName = categories.stream().collect(Collectors.groupingBy(CategoryName::getName));
        }

        /** 对上的 id 加进 ids；对不上的拼起来放进 legacy[key] */
        void match(List<String> names, boolean topOnly, List<Long> ids, Map<String, String> legacy, String key) {
            List<String> unmatched = new ArrayList<>();
            for (String name : names) {
                List<CategoryName> found = byName.getOrDefault(name, List.of()).stream()
                        .filter(c -> !topOnly || Integer.valueOf(1).equals(c.getLevel())).toList();
                if (found.size() == 1) {
                    if (!ids.contains(found.getFirst().getId())) {
                        ids.add(found.getFirst().getId());
                    }
                } else {
                    unmatched.add(name);
                }
            }
            if (!unmatched.isEmpty()) {
                legacy.put(key, String.join("、", unmatched));
            }
        }
    }

    /**
     * 同一种提示合成一条（ImportResult 的约定）："第 3、8、12 行的采购员对不上用户，留空了：张三、李四"。
     * 行太多只列前 20 个
     */
    static final class Notes {
        private record Key(String column, String message) {
        }

        private static final int LISTED = 20;
        private final Map<Key, List<Integer>> rows = new LinkedHashMap<>();
        private final Map<Key, Set<String>> values = new LinkedHashMap<>();

        void add(int row, String column, String message, String value) {
            Key key = new Key(column, message);
            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            Set<String> set = values.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (value != null && !value.isBlank()) {
                set.add(value);
            }
        }

        void flushTo(ExcelImport<?> sheet) {
            rows.forEach((key, list) -> {
                String text = "第 " + join(list) + " 行" + key.message();
                Set<String> vals = values.get(key);
                if (!vals.isEmpty()) {
                    text += "：" + join(vals);
                }
                sheet.note(list.getFirst(), key.column(), text);
            });
        }

        private static String join(Collection<?> items) {
            String listed = items.stream().limit(LISTED).map(String::valueOf).collect(Collectors.joining("、"));
            return items.size() > LISTED ? listed + " 等 " + items.size() + " 个" : listed;
        }
    }
}
