package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.excel.ExcelBook;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImage;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelOption;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.SupplierBrief;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.basic.mapper.MaterialSkuMapper;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.FieldPermissions;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 物料的 Excel 导入导出（RFC 003 §9、数据库设计附录 A.2）。模板就是领猫导出的样子：
 * "物料信息"一行一个物料，"物料信息SKU"一行一个 SKU，表头用领猫的列名；我们多出来的采购单位、换算系数、最小包装量接在"物料信息"后面。
 * 领猫的"物料信息多单位"有就读（定采购单位和换算系数）；两张"多供应商"表只提示有几行没导（归价格档案的导入）。
 * <p>
 * 只新增：编码已存在算错误。有一处不对整批不导（1003）；新建了颜色、客供改成自购这类，导完列在提示里。
 * 导完把每个分类码的流水推到已用的最大号，之后新建不撞（RFC 003 §7.1）。
 * 领猫导出的"图片"列（两张表都有）：浮在哪一行，就挂到那一行建的物料或 SKU 上（saveImages）。
 * 导出：两张表的列和模板一样，导出来改了能导回去（编码已存在的要先删）
 */
@Component
@RequiredArgsConstructor
public class MaterialImport {

    static final String MATERIAL_SHEET = "物料信息";
    static final String SKU_SHEET = "物料信息SKU";
    static final String UNIT_SHEET = "物料信息多单位";
    static final String MULTI_SUPPLIER_SHEET = "物料信息多供应商";
    static final String MULTI_SUPPLIER_UNIT_SHEET = "物料信息多供应商多单位";
    /** 领猫两张表里放图片的列：图片浮在这一列上，单元格是空的 */
    static final String IMAGE_COLUMN = "图片";
    /** FileService.save 报的这几种是这张图本身的问题（打不开、格式不对、太大）：跳过这张，别的照导 */
    private static final Set<Integer> IMAGE_ERRORS = Set.of(ErrorCodes.BIZ_ERROR, 400, 413);
    /** 领猫附加属性里放"物料分类"完整路径的键（领猫分了三级以上时） */
    static final String CATEGORY_PATH_KEY = "物料分类";

    /** 物料分类路径的分隔：领猫用 >，也认 / */
    private static final Pattern PATH_SEPARATOR = Pattern.compile("\\s*[>/]\\s*");
    private static final Pattern COLOR_CODE = Pattern.compile("^[A-Z0-9]{2,3}$");
    /** 一次 IN 最多带几个参数：导出最多 5 万个物料，分批查 */
    private static final int CHUNK = 1000;

    /** 领猫"物料信息"里认识、不导入的列（附录 A.2 写"丢弃"的） */
    private static final String[] MATERIAL_IGNORED = {"图片", "仓库", "供应商电话", "物料销售价", "调样预计到货时间",
            "创建人", "创建时间", "最后操作人", "最后操作时间"};
    /** 领猫"物料信息"里进 legacy_attrs 的列，有值才存 */
    private static final String[] MATERIAL_LEGACY = {"产地", "经缩", "纬缩", "安全库存", "战略备料", "卷重", "纸筒", "空差",
            "空差比", "标签", "其他价格", "倍率", "洗色"};
    private static final String[] SKU_IGNORED = {"图片", "年份", "季节", "单位", "类型", "分类", "经缩", "纬缩", "成本价",
            "物料销售价", "供应商编号", "供应商", "供应商电话", "物料成分", "SKU销售价", "物料备注",
            "创建人", "创建时间", "最后操作人", "最后操作时间"};
    private static final String[] UNIT_IGNORED = {"物料名称", "备注", "创建人", "创建时间", "最后操作人", "最后操作时间"};
    private static final String[] MULTI_SUPPLIER_IGNORED = {"物料名称", "状态", "供应商编码", "供应商", "供应商货号", "报价",
            "卷重", "纸筒", "空差", "空差比", "采购损耗", "门幅", "颜色名称", "供应商色号", "SKU成本价",
            "创建人", "创建时间", "最后操作人", "最后操作时间"};
    private static final String[] MULTI_SUPPLIER_UNIT_IGNORED = {"物料名称", "供应商编码", "供应商", "多单位", "换算系数",
            "换算类型", "价格基准", "成本价", "备注", "创建人", "创建时间", "最后操作人", "最后操作时间"};

    private final MaterialMapper materialMapper;
    private final MaterialSkuMapper materialSkuMapper;
    private final MaterialCategoryMapper materialCategoryMapper;
    private final ColorMapper colorMapper;
    private final DictDataService dictDataService;
    private final MaterialCodes materialCodes;
    private final TradeFeature tradeFeature;
    private final DocLogService docLogService;
    private final com.lingmao.scm.module.system.service.FileImportService imports;
    private final FileService fileService;
    private final AttachmentService attachmentService;

    // ---------- 行对象 ----------

    /** "物料信息"的一行 */
    @Data
    public static class MaterialRow {
        private String materialType;
        private String categoryPath;
        private Integer year;
        private String season;
        private String code;
        private String supplierItemNo;
        private String source;
        private String name;
        private String supplierCode;
        private String supplierName;
        private BigDecimal refPrice;
        private String stockUnit;
        private BigDecimal defaultLossPct;
        private BigDecimal reservePct;
        private BigDecimal moq;
        private Integer leadDays;
        /** 物料级的"最小库存"：SKU 表没填时作 SKU 的最小库存 */
        private BigDecimal safetyStock;
        private String frozen;
        private BigDecimal widthCm;
        private BigDecimal weightGsm;
        private String composition;
        private String remark;
        private String texture;
        /** 面料功能、是否带吊牌：11-18 以前进 legacy_attrs，RFC 012 §5.1 起是正式字段 */
        private String fabricFunction;
        private String withTag;
        /** 功能吊牌的名称（甲方 11-21，领猫没有这一列） */
        private String tagNames;
        private String purchaseUnit;
        private BigDecimal conversionFactor;
        private BigDecimal minPackQty;
        /** 领猫附加属性，列名 → 值 */
        private Map<String, String> legacy = new LinkedHashMap<>();
    }

    /** "物料信息SKU"的一行 */
    @Data
    public static class SkuRow {
        private String materialCode;
        private String materialName;
        private String code;
        private String colorCode;
        private String colorName;
        private String supplierColorNo;
        private String specCode;
        private String spec;
        private String supplierItemNo;
        private String status;
        private BigDecimal widthCm;
        private BigDecimal weightGsm;
        private BigDecimal safetyStock;
        private BigDecimal price;
        private String remark;
    }

    /** "物料信息多单位"的一行 */
    @Data
    public static class UnitRow {
        private String materialCode;
        private String unit;
        private BigDecimal factor;
        private String factorType;
        private String priceBasis;
        private BigDecimal price;
        private String isDefault;
    }

    /** 只数行数的表："物料信息多供应商"那两张 */
    @Data
    public static class CodeRow {
        private String materialCode;
    }

    // ---------- 模板、导出 ----------

    public byte[] template() {
        return Excel.template(materialSchema(), skuSchema());
    }

    /** ids 已经按编码排好、不超过导出上限 */
    public byte[] export(List<Long> ids) {
        Map<Long, Material> byId = chunked(ids, materialMapper::selectByIds).stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        List<Material> materials = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        List<MaterialSku> skus = chunked(ids, chunk -> materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                .in(MaterialSku::getMaterialId, chunk)));
        Map<Long, Color> colors = chunked(skus.stream().map(MaterialSku::getColorId).filter(Objects::nonNull).distinct().toList(),
                colorMapper::selectByIds).stream().collect(Collectors.toMap(Color::getId, Function.identity()));
        Map<Long, MaterialCategory> categories = materialCategoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(MaterialCategory::getId, Function.identity()));
        Map<Long, SupplierBrief> suppliers = chunked(materials.stream().map(Material::getDefaultSupplierId)
                .filter(Objects::nonNull).distinct().toList(), materialMapper::selectSuppliersByIds).stream()
                .collect(Collectors.toMap(SupplierBrief::getId, Function.identity()));
        Map<String, String> seasonLabels = labels(MaterialServiceImpl.SEASON_DICT);

        List<MaterialRow> materialRows = new ArrayList<>();
        Map<Long, Material> materialById = new HashMap<>();
        for (Material m : materials) {
            materialById.put(m.getId(), m);
            MaterialRow row = new MaterialRow();
            row.setMaterialType(m.getMaterialType());
            row.setCategoryPath(categoryPath(m, categories));
            row.setYear(m.getYear());
            row.setSeason(m.getSeason() == null ? null : seasonLabels.getOrDefault(m.getSeason(), m.getSeason()));
            row.setCode(m.getCode());
            row.setSupplierItemNo(m.getSupplierItemNo());
            row.setSource(MaterialSource.valueOf(m.getDefaultSource()).getLabel());
            row.setName(m.getName());
            SupplierBrief supplier = m.getDefaultSupplierId() == null ? null : suppliers.get(m.getDefaultSupplierId());
            row.setSupplierCode(supplier == null ? null : supplier.getCode());
            row.setSupplierName(supplier == null ? null : supplier.getName());
            row.setRefPrice(m.getRefPrice());
            row.setStockUnit(m.getStockUnit());
            row.setDefaultLossPct(m.getDefaultLossPct());
            row.setReservePct(m.getReservePct());
            row.setMoq(m.getMoq());
            row.setLeadDays(m.getLeadDays());
            row.setFrozen(Statuses.enabled(m.getStatus()) ? "否" : "是");
            row.setWidthCm(m.getWidthCm());
            row.setWeightGsm(m.getWeightGsm());
            row.setComposition(m.getComposition());
            row.setRemark(m.getRemark());
            row.setTexture(m.getTexture());
            row.setFabricFunction(m.getFabricFunction());
            row.setWithTag(m.getWithTag() == null ? null : m.getWithTag() ? "是" : "否");
            row.setTagNames(m.getTagNames());
            row.setPurchaseUnit(m.getPurchaseUnit());
            row.setConversionFactor(m.getConversionFactor());
            row.setMinPackQty(m.getMinPackQty());
            if (m.getLegacyAttrs() != null) {
                row.getLegacy().putAll(m.getLegacyAttrs());
            }
            materialRows.add(row);
        }

        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < materials.size(); i++) {
            order.put(materials.get(i).getId(), i);
        }
        List<SkuRow> skuRows = skus.stream()
                .sorted((a, b) -> {
                    int byMaterial = Integer.compare(order.get(a.getMaterialId()), order.get(b.getMaterialId()));
                    return byMaterial != 0 ? byMaterial : a.getCode().compareTo(b.getCode());
                })
                .map(k -> {
                    Material m = materialById.get(k.getMaterialId());
                    Color color = k.getColorId() == null ? null : colors.get(k.getColorId());
                    SkuRow row = new SkuRow();
                    row.setMaterialCode(m.getCode());
                    row.setMaterialName(m.getName());
                    row.setCode(k.getCode());
                    row.setColorCode(color == null ? null : color.getCode());
                    row.setColorName(color == null ? null : color.getName());
                    row.setSupplierColorNo(k.getSupplierColorNo());
                    row.setSpecCode(k.getSpecCode());
                    row.setSpec(k.getSpec());
                    row.setSupplierItemNo(k.getSupplierItemNo());
                    row.setStatus(Statuses.enabled(k.getStatus()) ? "启用" : "停用");
                    row.setWidthCm(m.getWidthCm());
                    row.setWeightGsm(m.getWeightGsm());
                    row.setSafetyStock(k.getSafetyStock());
                    row.setPrice(k.getPrice());
                    row.setRemark(k.getRemark());
                    return row;
                }).toList();

        boolean cost = FieldPermissions.has(FieldPermissions.COST);
        return Excel.exportBook()
                .sheet(cost ? materialSchema() : materialSchema().without("成本价", "其他价格", "倍率"), materialRows)
                .sheet(cost ? skuSchema() : skuSchema().without("SKU成本价"), skuRows)
                .toBytes();
    }

    // ---------- 导入 ----------

    /** 先解析、校验；颜色、物料、SKU、流水号和成功回执一起提交。 */
    public ImportResult importFile(MultipartFile file) {
        return imports.execute("material", "global", "basic:material:import", file, Map.of(), () -> parseFile(file), plan -> {
            write(plan); return plan.result;
        });
    }

    private Plan parseFile(MultipartFile file) {
        try (ExcelBook book = Excel.open(file)) {
            ExcelImport<MaterialRow> materials = book.sheet(materialSchema());
            ExcelImport<SkuRow> skus = book.optionalSheet(skuSchema());
            ExcelImport<UnitRow> units = book.optionalSheet(unitSchema());
            ExcelImport<CodeRow> multiSupplier = book.optionalSheet(codeOnlySchema(MULTI_SUPPLIER_SHEET, MULTI_SUPPLIER_IGNORED));
            ExcelImport<CodeRow> multiSupplierUnit = book.optionalSheet(
                    codeOnlySchema(MULTI_SUPPLIER_UNIT_SHEET, MULTI_SUPPLIER_UNIT_IGNORED));

            Plan plan = new Plan();
            checkMaterials(materials, plan);
            checkSkus(skus, plan);
            applyUnits(units, plan);
            notSkipped(multiSupplier, "多供应商的报价进价格档案，等价格档案的导入（数据库设计附录 A.2）");
            notSkipped(multiSupplierUnit, "一期一个物料一个采购单位，不分供应商换单位");
            book.throwIfInvalid();
            saveImages(book, materials, skus, plan);

            plan.result = book.result(plan.materials.size());
            return plan;
        }
    }

    /** 检查过、准备写进库的东西 */
    private static final class Plan {
        ImportResult result;
        /** 物料编码 → 要建的物料（还没有 id），按文件里的顺序 */
        final Map<String, Material> materials = new LinkedHashMap<>();
        /** 物料编码 → 分类（取分类码推流水） */
        final Map<String, MaterialCategory> categoryOf = new HashMap<>();
        /** 物料编码 → "物料信息"那一行（SKU 表核对、取物料级最小库存） */
        final Map<String, ExcelImport.Line<MaterialRow>> rowOf = new HashMap<>();
        /** 物料编码 → 要建的 SKU，materialId 写库时再填；colorCode 先记在 remark 外面 */
        final Map<String, List<PlannedSku>> skus = new LinkedHashMap<>();
        /** 颜色编码 → 库里有的颜色、或者要新建的颜色（新建的没有 id） */
        final Map<String, Color> colors = new HashMap<>();
        final List<Color> newColors = new ArrayList<>();
        /** "物料信息SKU"的行号 → 那一行要建的 SKU（挂图片时按行找） */
        final Map<Integer, MaterialSku> skuAtRow = new HashMap<>();
        /** 物料编码 → 存好的图片，写库时挂到物料上，第一张是主图 */
        final Map<String, List<AttachmentReq>> materialImages = new HashMap<>();
        /** 要挂图片的 SKU（还没有 id，按对象认）→ 存好的图片 */
        final Map<MaterialSku, List<AttachmentReq>> skuImages = new IdentityHashMap<>();
    }

    private record PlannedSku(MaterialSku sku, String colorCode) {
    }

    private void checkMaterials(ExcelImport<MaterialRow> sheet, Plan plan) {
        sheet.checkUnique(MaterialRow::getCode, "物料编码");
        List<ExcelImport.Line<MaterialRow>> lines = sheet.lines();
        Set<String> taken = existingLegacyCodes(lines.stream().map(l -> l.data().getCode()).filter(Objects::nonNull).toList());
        CategoryIndex categories = new CategoryIndex(materialCategoryMapper.selectList(null));
        List<DictOption> seasons = dictDataService.listByType(MaterialServiceImpl.SEASON_DICT);
        Map<String, SupplierBrief> suppliers = suppliersByCode(lines.stream().map(l -> l.data().getSupplierCode())
                .filter(Objects::nonNull).distinct().toList());
        List<Integer> customerSuppliedRows = new ArrayList<>();
        boolean lossFactors = factorColumn(lines.stream().map(l -> l.data().getDefaultLossPct()).toList());
        boolean reserveFactors = factorColumn(lines.stream().map(l -> l.data().getReservePct()).toList());

        for (ExcelImport.Line<MaterialRow> line : lines) {
            MaterialRow r = line.data();
            int row = line.row();
            Material m = new Material();
            if (r.getCode() != null && taken.contains(r.getCode())) {
                sheet.error(row, "物料编码", "领猫编码 " + r.getCode() + " 已经导过了：导入只新增。没用过的先在列表里删掉再导");
            }
            // 文件里的物料编码是领猫的，存成领猫编码；系统编码写库时按分类另发（甲方 11-18 答 Q05，RFC 012 §4.2）
            m.setLegacyCode(r.getCode());
            m.setName(r.getName());
            m.setMaterialType(r.getMaterialType());
            m.setStockUnit(r.getStockUnit());

            Map<String, String> legacy = new LinkedHashMap<>(r.getLegacy());
            if (r.getCategoryPath() != null) {
                MaterialCategory category = categories.match(r.getCategoryPath(), sheet, row);
                if (category != null) {
                    m.setCategoryId(category.getId());
                    plan.categoryOf.put(r.getCode(), category);
                    if (PATH_SEPARATOR.split(r.getCategoryPath().strip()).length > 2) {
                        legacy.put(CATEGORY_PATH_KEY, r.getCategoryPath().strip());
                    }
                }
            }
            if (r.getSeason() != null) {
                String season = matchSeason(r.getSeason(), seasons);
                if (season == null) {
                    sheet.error(row, "季节", "季节「" + r.getSeason() + "」不在字典里：先在 管理后台 › 字典 › 季节 里加");
                }
                m.setSeason(season);
            }
            String source = sourceOf(r.getSource());
            if (source == null) {
                sheet.error(row, "物料来源", "物料来源只认 自购、厂购、客供");
            } else if (MaterialSource.valueOf(source).isTrade() && !tradeFeature.enabled()) {
                source = MaterialSource.PURCHASE.name();
                customerSuppliedRows.add(row);
            }
            m.setDefaultSource(source);
            if (r.getSupplierCode() != null) {
                SupplierBrief supplier = suppliers.get(r.getSupplierCode());
                if (supplier == null) {
                    sheet.error(row, "供应商编号", "供应商 " + r.getSupplierCode() + " 在系统里没有：先导入供应商，再导物料");
                } else {
                    m.setDefaultSupplierId(supplier.getId());
                    if (r.getSupplierName() != null && !r.getSupplierName().equals(supplier.getName())
                            && !r.getSupplierName().equals(supplier.getShortName())) {
                        sheet.note(row, "供应商", "供应商 " + supplier.getCode() + " 在系统里叫「" + supplier.getName()
                                + "」，文件里写的「" + r.getSupplierName() + "」：按编号对上了");
                    }
                }
            }
            String purchaseUnit = r.getPurchaseUnit() == null ? r.getStockUnit() : r.getPurchaseUnit();
            BigDecimal factor = Objects.requireNonNullElse(r.getConversionFactor(), BigDecimal.ONE);
            if (purchaseUnit != null && purchaseUnit.equals(r.getStockUnit()) && factor.compareTo(BigDecimal.ONE) != 0) {
                sheet.error(row, "换算系数", "采购单位和单位一样时，换算系数只能是 1");
            }
            m.setPurchaseUnit(purchaseUnit);
            m.setConversionFactor(factor);
            m.setWidthCm(zeroToNull(r.getWidthCm()));
            m.setWeightGsm(zeroToNull(r.getWeightGsm()));
            m.setComposition(r.getComposition());
            m.setTexture(r.getTexture());
            m.setFabricFunction(MaterialServiceImpl.FABRIC_TYPES.contains(r.getMaterialType()) ? r.getFabricFunction() : null);
            if (r.getFabricFunction() != null && m.getFabricFunction() == null) {
                legacy.put("面料功能", r.getFabricFunction());   // 不是面料、里料的照原样留着看，不丢
            }
            if (r.getWithTag() != null) {
                m.setWithTag(switch (r.getWithTag()) {
                    case "是" -> true;
                    case "否" -> false;
                    default -> {
                        sheet.error(row, "是否带吊牌", "只认 是、否");
                        yield null;
                    }
                });
            }
            if (r.getTagNames() != null) {
                if (Boolean.TRUE.equals(m.getWithTag())) {
                    m.setTagNames(r.getTagNames());
                } else {
                    sheet.error(row, "吊牌名称", "是否带吊牌填\"是\"才填吊牌名称");
                }
            }
            m.setSupplierItemNo(r.getSupplierItemNo());
            m.setRefPrice(zeroToNull(FieldPermissions.price(FieldPermissions.COST, null, r.getRefPrice())));
            m.setMoq(zeroToNull(r.getMoq()));
            m.setMinPackQty(Objects.requireNonNullElse(r.getMinPackQty(), BigDecimal.ONE));
            m.setLeadDays(r.getLeadDays());
            m.setYear(r.getYear());
            m.setDefaultLossPct(lossPct(r.getDefaultLossPct(), lossFactors, sheet, row, "默认损耗"));
            m.setReservePct(lossPct(r.getReservePct(), reserveFactors, sheet, row, "采购损耗"));
            m.setStatus("是".equals(r.getFrozen()) ? Statuses.DISABLED : Statuses.ENABLED);
            m.setRemark(r.getRemark());
            for (String key : List.of("其他价格", "倍率")) FieldPermissions.preserve(FieldPermissions.COST, null, legacy.get(key));
            m.setLegacyAttrs(legacy.isEmpty() ? null : legacy);
            if (r.getCode() != null) {
                plan.materials.putIfAbsent(r.getCode(), m);
                plan.rowOf.putIfAbsent(r.getCode(), line);
            }
        }
        if (!customerSuppliedRows.isEmpty()) {
            sheet.note(customerSuppliedRows.getFirst(), "物料来源", rowsText(customerSuppliedRows)
                    + "的物料来源「客供」按「自购」导入：系统没有打开代工功能");
        }
        if (lossFactors) {
            sheet.note(0, "默认损耗", "「默认损耗」这一列的数都在 1 和 2 之间，按系数导入：1.03 = 3%");
        }
        if (reserveFactors) {
            sheet.note(0, "采购损耗", "「采购损耗」这一列的数都在 1 和 2 之间，按系数导入：1.03 = 3%");
        }
    }

    /** 领猫的损耗可能写成系数（PRD Q31），规则见 Num.lossFactorColumn */
    static boolean factorColumn(List<BigDecimal> values) {
        return Num.lossFactorColumn(values);
    }

    /** 系数换成百分数（1.035 → 3.5，1 → 0）；百分数原样。库里存 2 位小数，多了报错 */
    private static BigDecimal lossPct(BigDecimal value, boolean factor, ExcelImport<MaterialRow> sheet, int row, String column) {
        if (value == null || value.signum() == 0) {
            return value;
        }
        BigDecimal pct = factor ? value.subtract(BigDecimal.ONE).movePointRight(2) : value;
        if (pct.stripTrailingZeros().scale() > 2) {
            sheet.error(row, column, factor ? "按系数换成百分数是 " + pct.stripTrailingZeros().toPlainString() + "%，最多 2 位小数"
                    : "最多 2 位小数");
        }
        return pct;
    }

    private void checkSkus(ExcelImport<SkuRow> sheet, Plan plan) {
        sheet.checkUnique(SkuRow::getCode, "物料SKU编码");
        List<ExcelImport.Line<SkuRow>> lines = sheet.lines();
        Map<String, Color> existingColors = colorsByCode(lines.stream().map(l -> Texts.upper(l.data().getColorCode()))
                .filter(Objects::nonNull).distinct().toList());
        Map<String, Integer> comboRow = new HashMap<>();
        Map<String, Integer> codeRow = new HashMap<>();
        /** 领猫 SKU 编码 → 行；0 = 物料的默认 SKU（领猫编码 = 物料的领猫编码） */
        Map<String, Integer> legacyRow = new HashMap<>();
        Set<String> notedColorNames = new HashSet<>();

        for (ExcelImport.Line<SkuRow> line : lines) {
            SkuRow r = line.data();
            int row = line.row();
            ExcelImport.Line<MaterialRow> materialLine = r.getMaterialCode() == null ? null : plan.rowOf.get(r.getMaterialCode());
            if (r.getMaterialCode() != null && materialLine == null) {
                sheet.error(row, "物料编码", "「" + MATERIAL_SHEET + "」里没有物料 " + r.getMaterialCode());
                continue;
            }
            if (materialLine == null) {
                continue;
            }
            Material material = plan.materials.get(r.getMaterialCode());
            if (r.getMaterialName() != null && !r.getMaterialName().equals(material.getName())) {
                sheet.note(row, "物料名称", "和「" + MATERIAL_SHEET + "」里的「" + material.getName() + "」不一样，按物料编码对");
            }

            String colorCode = Texts.upper(r.getColorCode());
            if (colorCode == null && r.getColorName() != null) {
                sheet.error(row, "颜色编码", "填了颜色名称就要填颜色编码：颜色按编码对");
            }
            if (colorCode != null) {
                planColor(colorCode, r.getColorName(), existingColors, plan, sheet, row, notedColorNames);
            }
            String specCode = r.getSpecCode() != null ? r.getSpecCode() : SkuCodes.specCodeOf(r.getSpec());
            // 系统的 SKU 码 = 新发的物料编码-颜色-规格，写库时才拼；这里按分类码 + 4 位估长度
            MaterialCategory category = plan.categoryOf.get(r.getMaterialCode());
            String code = SkuCodes.compose(category == null ? r.getMaterialCode() : category.getCode() + "0".repeat(MaterialServiceImpl.CODE_WIDTH),
                    colorCode, specCode);
            if (code.length() > 64) {
                sheet.error(row, "规格编号", "拼出来的 SKU 码 " + code + " 超过 64 位：规格编号短一点");
            }
            if (specCode != null && specCode.length() > 16) {
                sheet.error(row, "规格编号", "规格编号最多 16 位");
            }
            Integer firstCombo = comboRow.putIfAbsent(r.getMaterialCode() + "|" + colorCode + "|" + specCode, row);
            if (firstCombo != null) {
                sheet.error(row, null, "和第 " + firstCombo + " 行是同一个物料的同一个颜色、规格");
            }
            Integer firstCode = codeRow.putIfAbsent(r.getMaterialCode() + "|" + SkuCodes.compose("", colorCode, specCode), row);
            if (firstCode != null && firstCombo == null) {
                sheet.error(row, "规格编号", "和第 " + firstCode + " 行拼出来的 SKU 码一样：改一个规格编号");
            }
            if (r.getCode() != null) {
                legacyRow.putIfAbsent(r.getCode(), row);
            }
            if (r.getWidthCm() != null && !sameNumber(zeroToNull(r.getWidthCm()), material.getWidthCm())) {
                sheet.note(row, "门幅", "和「" + MATERIAL_SHEET + "」里的门幅不一样，以物料信息的为准");
            }
            if (r.getWeightGsm() != null && !sameNumber(zeroToNull(r.getWeightGsm()), material.getWeightGsm())) {
                sheet.note(row, "克重", "和「" + MATERIAL_SHEET + "」里的克重不一样，以物料信息的为准");
            }

            MaterialSku sku = new MaterialSku();
            sku.setLegacyCode(r.getCode());
            sku.setSpec(r.getSpec());
            sku.setSpecCode(specCode);
            sku.setSupplierColorNo(r.getSupplierColorNo());
            sku.setSupplierItemNo(Objects.equals(r.getSupplierItemNo(), material.getSupplierItemNo()) ? null : r.getSupplierItemNo());
            sku.setPrice(zeroToNull(FieldPermissions.price(FieldPermissions.COST, null, r.getPrice())));
            BigDecimal safety = zeroToNull(r.getSafetyStock());
            sku.setSafetyStock(safety != null ? safety : zeroToNull(materialLine.data().getSafetyStock()));
            sku.setStatus(disabledText(r.getStatus()) ? Statuses.DISABLED : Statuses.ENABLED);
            sku.setRemark(r.getRemark());
            plan.skus.computeIfAbsent(r.getMaterialCode(), k -> new ArrayList<>()).add(new PlannedSku(sku, colorCode));
            plan.skuAtRow.put(row, sku);
        }

        // 没有 SKU 行的物料：建一个不带颜色、规格的，系统码 = 物料编码，领猫编码 = 物料的领猫编码
        for (Map.Entry<String, Material> e : plan.materials.entrySet()) {
            if (!plan.skus.containsKey(e.getKey())) {
                MaterialSku sku = new MaterialSku();
                sku.setLegacyCode(e.getKey());
                sku.setSafetyStock(zeroToNull(plan.rowOf.get(e.getKey()).data().getSafetyStock()));
                sku.setStatus(Statuses.ENABLED);
                plan.skus.put(e.getKey(), new ArrayList<>(List.of(new PlannedSku(sku, null))));
                Integer first = legacyRow.putIfAbsent(e.getKey(), 0);
                if (first != null && first > 0) {
                    sheet.error(first, "物料SKU编码", "SKU 编码 " + e.getKey() + " 和物料 " + e.getKey() + " 的默认 SKU 重复："
                            + "这个物料在「" + SKU_SHEET + "」里没有行，系统给它建一个领猫编码等于物料编码的 SKU");
                }
            }
        }

        Set<String> takenLegacy = existingSkuLegacyCodes(legacyRow.keySet());
        legacyRow.forEach((code, row) -> {
            if (!takenLegacy.contains(code)) {
                return;
            }
            if (row > 0) {
                sheet.error(row, "物料SKU编码", "领猫 SKU 编码 " + code + " 已经导过了：导入只新增");
            } else {
                ExcelImport.Line<MaterialRow> materialLine = plan.rowOf.get(code);
                sheet.error(materialLine == null ? 0 : materialLine.row(), null, "这个物料在「" + SKU_SHEET
                        + "」里没有行，默认 SKU 的领猫编码 " + code + " 已经被别的 SKU 用了：请在 SKU 表里给它填一行");
            }
        });
    }

    /**
     * 颜色按编码对（RFC 003 §9）：库里有就用，名称不一样列进提示；没有就用编码 + 名称新建一个，列进提示。
     * 新建的编码要 2-3 位字母数字，名称不能和别的颜色重名
     */
    private void planColor(String code, String name, Map<String, Color> existing, Plan plan, ExcelImport<SkuRow> sheet,
                           int row, Set<String> notedNames) {
        Color known = plan.colors.get(code);
        if (known == null) {
            known = existing.get(code);
            if (known != null) {
                plan.colors.put(code, known);
            }
        }
        if (known != null) {
            if (name != null && !name.equals(known.getName()) && notedNames.add(code + "|" + name)) {
                String where = known.getId() == null ? "这次新建的" : "系统里的";
                sheet.note(row, "颜色名称", "颜色 " + code + " " + where + "名称是「" + known.getName() + "」，文件里写的「"
                        + name + "」：按编码对，名称以" + (known.getId() == null ? "第一次出现的" : "系统的") + "为准");
            }
            return;
        }
        if (!COLOR_CODE.matcher(code).matches()) {
            sheet.error(row, "颜色编码", "颜色 " + code + " 系统里没有，要新建的话编码只能是 2-3 位字母或数字："
                    + "先在 资料 › 基础 › 颜色 里建好，把文件里的颜色编码改成它");
            return;
        }
        String colorName = name != null ? name : code;
        if (colorName.length() > 32) {
            sheet.error(row, "颜色名称", "颜色名称最长 32 个字");
            return;
        }
        boolean nameTaken = plan.newColors.stream().anyMatch(c -> c.getName().equals(colorName))
                || colorMapper.exists(new LambdaQueryWrapper<Color>().eq(Color::getName, colorName));
        if (nameTaken) {
            sheet.error(row, "颜色名称", "颜色 " + code + " 系统里没有，要新建，可名称「" + colorName
                    + "」已经给了别的颜色：颜色名称不能重复，改一个名称或者改成那个颜色的编码");
            return;
        }
        Color color = new Color();
        color.setCode(code);
        color.setName(colorName);
        plan.colors.put(code, color);
        plan.newColors.add(color);
        sheet.note(row, "颜色编码", "颜色 " + code + " 系统里没有，按「" + code + " " + colorName + "」新建了");
    }

    /**
     * "物料信息多单位"：每个物料"是否默认多单位 = 是"的那一行定采购单位和换算系数（附录 A.2），按"1 多单位 = 换算系数 × 单位"理解；
     * 那一行有成本价就当参考单价（已经是采购单位的价）。不是默认的行不导，合成一条提示
     */
    private void applyUnits(ExcelImport<UnitRow> sheet, Plan plan) {
        List<Integer> skipped = new ArrayList<>();
        Map<String, Integer> defaultRow = new HashMap<>();
        for (ExcelImport.Line<UnitRow> line : sheet.lines()) {
            UnitRow r = line.data();
            int row = line.row();
            if (r.getMaterialCode() == null) {
                continue;
            }
            Material m = plan.materials.get(r.getMaterialCode());
            if (m == null) {
                sheet.error(row, "物料编码", "「" + MATERIAL_SHEET + "」里没有物料 " + r.getMaterialCode());
                continue;
            }
            if (!"是".equals(r.getIsDefault())) {
                skipped.add(row);
                continue;
            }
            Integer first = defaultRow.putIfAbsent(r.getMaterialCode(), row);
            if (first != null) {
                sheet.error(row, "是否默认多单位", "和第 " + first + " 行都是这个物料的默认单位，只能有一个");
                continue;
            }
            if (r.getUnit() == null || r.getFactor() == null) {
                continue;
            }
            if (r.getUnit().equals(m.getStockUnit()) && r.getFactor().compareTo(BigDecimal.ONE) != 0) {
                sheet.error(row, "换算系数", "多单位和物料的单位一样时，换算系数只能是 1");
                continue;
            }
            m.setPurchaseUnit(r.getUnit());
            m.setConversionFactor(r.getFactor());
            if (zeroToNull(r.getPrice()) != null) {
                m.setRefPrice(FieldPermissions.price(FieldPermissions.COST, null, r.getPrice()));
            }
        }
        if (!defaultRow.isEmpty()) {
            sheet.note(0, "换算系数", "采购单位、换算系数按默认那一行导入，换算系数理解成「1 多单位 = 换算系数 × 单位」，请到物料详情里核对一条");
        }
        if (!skipped.isEmpty()) {
            sheet.note(skipped.getFirst(), null, rowsText(skipped) + "不是默认单位，没导：一期一个物料只有一个采购单位");
        }
    }

    private static void notSkipped(ExcelImport<CodeRow> sheet, String why) {
        if (!sheet.lines().isEmpty()) {
            sheet.note(0, null, "这张表有 " + sheet.lines().size() + " 行没导：" + why);
        }
    }

    /**
     * 写库：先建颜色，再一个个建物料（要 id）和它的 SKU。系统编码按分类码 + 流水另发，和新建的一样（RFC 012 §4.2），
     * SKU 码照新编码拼；领猫的编码存在 legacy_code 里
     */
    private void write(Plan plan) {
        plan.newColors.forEach(colorMapper::insert);
        for (Map.Entry<String, Material> e : plan.materials.entrySet()) {
            Material m = e.getValue();
            m.setCode(materialCodes.next(m.getMaterialType(), plan.categoryOf.get(e.getKey()).getCode()));
            materialMapper.insert(m);
            for (PlannedSku planned : plan.skus.getOrDefault(e.getKey(), List.of())) {
                MaterialSku sku = planned.sku();
                sku.setMaterialId(m.getId());
                sku.setColorId(planned.colorCode() == null ? null : plan.colors.get(planned.colorCode()).getId());
                sku.setCode(SkuCodes.compose(m.getCode(), planned.colorCode(), sku.getSpecCode()));
                materialSkuMapper.insert(sku);
                List<AttachmentReq> skuImages = plan.skuImages.get(sku);
                if (skuImages != null && !skuImages.isEmpty()) {
                    attachmentService.replace(BizType.MATERIAL_SKU, sku.getId(), Attachment.IMAGE, skuImages);
                }
            }
            List<AttachmentReq> images = plan.materialImages.get(e.getKey());
            if (images != null && !images.isEmpty()) {
                attachmentService.replace(BizType.MATERIAL, m.getId(), Attachment.IMAGE, images);
            }
            docLogService.add(BizType.MATERIAL, m.getId(), "import", null);
        }
    }

    // ---------- 图片 ----------

    /**
     * 领猫导出的"图片"列：图片浮在哪一行，就挂到那一行的物料（"物料信息"）或 SKU（"物料信息SKU"）上，同一行几张的第一张是主图。
     * 文件在这里就存好：解析不在写事务里，写库只挂引用；没导成的文件没人引用，24 小时后自动清掉（FileService.save）。
     * 同一个文件里一样的图只存一份。落在空行上的、不是 jpg/png/gif 的、图本身坏了的不导，进提示，不拦导入；
     * 只认图本身的错（IMAGE_ERRORS），存储忙（429）、空间不足（503）、登记冲突（409）这些是整批的事，照旧报错
     */
    private void saveImages(ExcelBook book, ExcelImport<MaterialRow> materials, ExcelImport<SkuRow> skus, Plan plan) {
        Map<Integer, String> codeAtRow = new HashMap<>();
        plan.rowOf.forEach((code, line) -> codeAtRow.put(line.row(), code));
        Map<String, Optional<FileVO>> saved = new HashMap<>();
        int count = attachImages(book.images(MATERIAL_SHEET, IMAGE_COLUMN), materials, saved, row -> {
            String code = codeAtRow.get(row);
            return code == null ? null : new ImageTarget(code, plan.materialImages.computeIfAbsent(code, k -> new ArrayList<>()));
        });
        count += attachImages(book.images(SKU_SHEET, IMAGE_COLUMN), skus, saved, row -> {
            MaterialSku sku = plan.skuAtRow.get(row);
            return sku == null ? null : new ImageTarget(sku.getLegacyCode() != null ? sku.getLegacyCode() : "SKU图片",
                    plan.skuImages.computeIfAbsent(sku, k -> new ArrayList<>()));
        });
        if (count > 0) {
            materials.note(0, IMAGE_COLUMN, "图片一起导入了 " + count + " 张，挂在各自那一行的物料或 SKU 上");
        }
    }

    /** 图片要挂到哪：文件名用领猫编码，images 是那条物料或 SKU 的图片列表 */
    private record ImageTarget(String name, List<AttachmentReq> images) {
    }

    private int attachImages(List<ExcelImage> images, ExcelImport<?> sheet, Map<String, Optional<FileVO>> saved,
                             IntFunction<ImageTarget> targetAt) {
        int count = 0;
        for (ExcelImage image : images) {
            ImageTarget target = targetAt.apply(image.row());
            if (target == null) {
                sheet.note(image.row(), IMAGE_COLUMN, "这一行没有数据，图片没导");
                continue;
            }
            if (!image.supported()) {
                sheet.note(image.row(), IMAGE_COLUMN, "图片不是 jpg、png、gif，没导：导完在物料页面上传");
                continue;
            }
            Optional<FileVO> stored = saved.computeIfAbsent(sha256(image.data()), k -> {
                try {
                    return Optional.of(fileService.save(target.name() + "." + image.extension(), image.data()));
                } catch (BizException broken) {
                    if (!IMAGE_ERRORS.contains(broken.getCode())) throw broken;
                    return Optional.empty();
                }
            });
            if (stored.isEmpty()) {
                sheet.note(image.row(), IMAGE_COLUMN, "图片打不开（坏了或者太大），没导：导完在物料页面上传");
                continue;
            }
            FileVO file = stored.get();
            AttachmentReq req = new AttachmentReq();
            req.setUrl(file.url());
            req.setThumbUrl(file.thumbUrl());
            req.setName(target.name() + "." + image.extension());
            req.setSize(file.size());
            target.images().add(req);
            count++;
        }
        return count;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---------- 列定义 ----------

    ExcelSchema<MaterialRow> materialSchema() {
        List<ExcelOption> types = options(MaterialServiceImpl.TYPE_DICT);
        List<ExcelOption> units = options(MaterialServiceImpl.UNIT_DICT);
        List<ExcelColumn<MaterialRow, ?>> columns = new ArrayList<>(List.of(
                ExcelColumn.choice("物料类型", types, MaterialRow::getMaterialType, MaterialRow::setMaterialType).required(),
                ExcelColumn.text("物料分类", MaterialRow::getCategoryPath, MaterialRow::setCategoryPath).required().maxLength(200)
                        .example("面料>梭织").note("用 > 连起来的路径，前两级按名称对系统里的物料分类，分类要先建好"),
                ExcelColumn.integer("年份", MaterialRow::getYear, MaterialRow::setYear)
                        .check(y -> y < 1900 || y > 2999 ? "年份不对" : null),
                ExcelColumn.text("季节", MaterialRow::getSeason, MaterialRow::setSeason).maxLength(64)
                        .note("填字典「季节」里的名称，「夏季」也认"),
                ExcelColumn.text("物料编码", MaterialRow::getCode, MaterialRow::setCode).required().code().maxLength(32)
                        .example("ML0001").note("领猫的编码：存成「领猫编码」，系统按分类另发编码；导过的不再导"),
                ExcelColumn.text("供应商货号", MaterialRow::getSupplierItemNo, MaterialRow::setSupplierItemNo).maxLength(64),
                ExcelColumn.text("物料来源", MaterialRow::getSource, MaterialRow::setSource).maxLength(32)
                        .note("自购 / 厂购 / 客供，不填 = 自购"),
                ExcelColumn.text("物料名称", MaterialRow::getName, MaterialRow::setName).required().maxLength(200),
                ExcelColumn.text("供应商编号", MaterialRow::getSupplierCode, MaterialRow::setSupplierCode).code().maxLength(32)
                        .note("默认供应商，按供应商编码对；供应商要先导入"),
                ExcelColumn.text("供应商", MaterialRow::getSupplierName, MaterialRow::setSupplierName).maxLength(100)
                        .note("只用来核对编号"),
                ExcelColumn.decimal("成本价", 4, MaterialRow::getRefPrice, MaterialRow::setRefPrice).nonNegative()
                        .note("参考单价，按采购单位、含税；0 当没填"),
                ExcelColumn.choice("单位", units, MaterialRow::getStockUnit, MaterialRow::setStockUnit).required()
                        .note("库存单位，建好不能改"),
                ExcelColumn.decimal("默认损耗", 4, MaterialRow::getDefaultLossPct, MaterialRow::setDefaultLossPct).percent()
                        .nonNegative().check(MaterialImport::atMost100).note("百分数（3 = 3%）或系数（1.03 = 3%），一列里统一一种"),
                ExcelColumn.decimal("采购损耗", 4, MaterialRow::getReservePct, MaterialRow::setReservePct).percent()
                        .nonNegative().check(MaterialImport::atMost100).note("当备料比；百分数或系数，同默认损耗"),
                ExcelColumn.decimal("起订量", 3, MaterialRow::getMoq, MaterialRow::setMoq).nonNegative().note("按采购单位；0 当没填"),
                ExcelColumn.integer("采购周期", MaterialRow::getLeadDays, MaterialRow::setLeadDays)
                        .check(d -> d < 0 || d > 3650 ? "采购周期是 0-3650 天" : null),
                ExcelColumn.decimal("最小库存", 3, MaterialRow::getSafetyStock, MaterialRow::setSafetyStock).nonNegative()
                        .note("SKU 表没填最小库存时，作 SKU 的最小库存"),
                ExcelColumn.text("是否冻结", MaterialRow::getFrozen, MaterialRow::setFrozen).maxLength(8).note("是 = 停用"),
                ExcelColumn.decimal("门幅", 2, MaterialRow::getWidthCm, MaterialRow::setWidthCm).nonNegative().note("cm；0 当没填"),
                ExcelColumn.decimal("克重", 2, MaterialRow::getWeightGsm, MaterialRow::setWeightGsm).nonNegative().note("g/㎡；0 当没填"),
                ExcelColumn.text("物料成分", MaterialRow::getComposition, MaterialRow::setComposition).maxLength(255),
                ExcelColumn.text("备注", MaterialRow::getRemark, MaterialRow::setRemark).maxLength(500),
                ExcelColumn.text("纹理", MaterialRow::getTexture, MaterialRow::setTexture).maxLength(64),
                ExcelColumn.text("面料功能", MaterialRow::getFabricFunction, MaterialRow::setFabricFunction).maxLength(255)
                        .note("面料、里料才存；别的类型的留在附加属性里"),
                ExcelColumn.text("是否带吊牌", MaterialRow::getWithTag, MaterialRow::setWithTag).maxLength(8)
                        .note("带不带功能性吊牌：是 / 否，不填 = 没填"),
                ExcelColumn.text("吊牌名称", MaterialRow::getTagNames, MaterialRow::setTagNames).maxLength(255)
                        .example("coolmax 吊牌").note("领猫没有这一列：功能吊牌叫什么，几个用顿号隔开；是否带吊牌填\"是\"才填"),
                ExcelColumn.choice("采购单位", units, MaterialRow::getPurchaseUnit, MaterialRow::setPurchaseUnit)
                        .note("领猫没有这一列；不填 = 单位"),
                ExcelColumn.decimal("换算系数", 6, MaterialRow::getConversionFactor, MaterialRow::setConversionFactor)
                        .check(MaterialImport::positive).note("领猫没有这一列：1 采购单位 = 几个单位，不填 = 1"),
                ExcelColumn.decimal("最小包装量", 3, MaterialRow::getMinPackQty, MaterialRow::setMinPackQty)
                        .check(MaterialImport::positive).note("领猫没有这一列：按采购单位，不填 = 1")));
        for (String header : MATERIAL_LEGACY) {
            columns.add(ExcelColumn.<MaterialRow>text(header, r -> r.getLegacy().get(header), (r, v) -> r.getLegacy().put(header, v))
                    .maxLength(500).note("领猫的附加属性：原样存着，物料详情里能看"));
        }
        @SuppressWarnings("unchecked")
        ExcelColumn<MaterialRow, ?>[] array = columns.toArray(new ExcelColumn[0]);
        return ExcelSchema.of(MATERIAL_SHEET, MaterialRow::new, array).ignore(MATERIAL_IGNORED);
    }

    ExcelSchema<SkuRow> skuSchema() {
        return ExcelSchema.of(SKU_SHEET, SkuRow::new,
                ExcelColumn.text("物料编码", SkuRow::getMaterialCode, SkuRow::setMaterialCode).required().code().maxLength(32)
                        .note("对「物料信息」里的物料编码"),
                ExcelColumn.text("物料名称", SkuRow::getMaterialName, SkuRow::setMaterialName).maxLength(200).note("只用来核对"),
                ExcelColumn.text("物料SKU编码", SkuRow::getCode, SkuRow::setCode).code().maxLength(64)
                        .example("ML0001-KQ").note("领猫的 SKU 编码，存成「领猫编码」；系统的 SKU 码按新物料编码-颜色编码-规格编号拼"),
                ExcelColumn.text("颜色编码", SkuRow::getColorCode, SkuRow::setColorCode).code().maxLength(32)
                        .note("按编码对系统里的颜色；没有的按编码 + 颜色名称新建"),
                ExcelColumn.text("颜色名称", SkuRow::getColorName, SkuRow::setColorName).maxLength(32),
                ExcelColumn.text("供应商色号", SkuRow::getSupplierColorNo, SkuRow::setSupplierColorNo).maxLength(64),
                ExcelColumn.text("规格编号", SkuRow::getSpecCode, SkuRow::setSpecCode).code().maxLength(16)
                        .note("不填由规格名称算：去掉空格和末尾的 cm、mm、码"),
                ExcelColumn.text("规格名称", SkuRow::getSpec, SkuRow::setSpec).maxLength(64),
                ExcelColumn.text("货号", SkuRow::getSupplierItemNo, SkuRow::setSupplierItemNo).maxLength(64)
                        .note("和物料的供应商货号一样时不存"),
                ExcelColumn.text("状态", SkuRow::getStatus, SkuRow::setStatus).maxLength(8).note("停用、禁用 = 停用，不填 = 启用"),
                ExcelColumn.decimal("门幅", 2, SkuRow::getWidthCm, SkuRow::setWidthCm).nonNegative()
                        .note("只用来核对：和「物料信息」的不一样时列在提示里"),
                ExcelColumn.decimal("克重", 2, SkuRow::getWeightGsm, SkuRow::setWeightGsm).nonNegative()
                        .note("只用来核对：和「物料信息」的不一样时列在提示里"),
                ExcelColumn.decimal("最小库存", 3, SkuRow::getSafetyStock, SkuRow::setSafetyStock).nonNegative()
                        .note("SKU 的最小库存（低于它提醒补货），按库存单位；0 时取「物料信息」的"),
                ExcelColumn.decimal("SKU成本价", 4, SkuRow::getPrice, SkuRow::setPrice).nonNegative()
                        .note("0 当没填 = 和物料同价"),
                ExcelColumn.text("SKU信息备注", SkuRow::getRemark, SkuRow::setRemark).maxLength(255)
        ).ignore(SKU_IGNORED);
    }

    ExcelSchema<UnitRow> unitSchema() {
        return ExcelSchema.of(UNIT_SHEET, UnitRow::new,
                ExcelColumn.text("物料编码", UnitRow::getMaterialCode, UnitRow::setMaterialCode).required().code().maxLength(32),
                ExcelColumn.choice("多单位", options(MaterialServiceImpl.UNIT_DICT), UnitRow::getUnit, UnitRow::setUnit).required(),
                ExcelColumn.decimal("换算系数", 6, UnitRow::getFactor, UnitRow::setFactor).required().check(MaterialImport::positive),
                ExcelColumn.text("换算类型", UnitRow::getFactorType, UnitRow::setFactorType).maxLength(64),
                ExcelColumn.text("价格基准", UnitRow::getPriceBasis, UnitRow::setPriceBasis).maxLength(64),
                ExcelColumn.decimal("成本价", 4, UnitRow::getPrice, UnitRow::setPrice).nonNegative(),
                ExcelColumn.text("是否默认多单位", UnitRow::getIsDefault, UnitRow::setIsDefault).maxLength(8)
        ).ignore(UNIT_IGNORED);
    }

    private static ExcelSchema<CodeRow> codeOnlySchema(String sheet, String[] ignored) {
        return ExcelSchema.of(sheet, CodeRow::new,
                ExcelColumn.text("物料编码", CodeRow::getMaterialCode, CodeRow::setMaterialCode).code().maxLength(64)
        ).ignore(ignored);
    }

    // ---------- 小工具 ----------

    private List<ExcelOption> options(String typeCode) {
        return dictDataService.listByType(typeCode).stream()
                .map(o -> new ExcelOption(o.label(), o.value(), o.enabled())).toList();
    }

    private Map<String, String> labels(String typeCode) {
        return dictDataService.listByType(typeCode).stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }

    /** 季节去掉末尾的"季"按名称对（"夏季" → "夏"），填值也认；只认启用的 */
    static String matchSeason(String text, List<DictOption> seasons) {
        String trimmed = text.strip();
        String bare = trimmed.endsWith("季") && trimmed.length() > 1 ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return seasons.stream().filter(DictOption::enabled)
                .filter(o -> o.label().equals(trimmed) || o.label().equals(bare) || o.value().equalsIgnoreCase(trimmed))
                .map(DictOption::value).findFirst().orElse(null);
    }

    /** 领猫的物料来源 → MaterialSource 的名字；认不出返回 null。不填 = 自购；老叫法"我方采购""工厂自备"也认（PRD Q12）*/
    static String sourceOf(String text) {
        if (text == null || text.isBlank()) {
            return MaterialSource.PURCHASE.name();
        }
        String t = text.strip();
        if (t.equals("自购") || t.equals("我方采购") || t.equals("采购") || t.equalsIgnoreCase(MaterialSource.PURCHASE.name())) {
            return MaterialSource.PURCHASE.name();
        }
        if (t.equals("厂购") || t.contains("自备") || t.contains("工厂") || t.equalsIgnoreCase(MaterialSource.FACTORY.name())) {
            return MaterialSource.FACTORY.name();
        }
        if (t.equals("客供") || t.equalsIgnoreCase(MaterialSource.CUSTOMER_SUPPLIED.name())) {
            return MaterialSource.CUSTOMER_SUPPLIED.name();
        }
        return null;
    }

    private static boolean disabledText(String status) {
        return status != null && (status.contains("停") || status.contains("禁") || status.contains("冻结"));
    }

    /** 领猫没填的数字导出 0：当没填 */
    private static BigDecimal zeroToNull(BigDecimal value) {
        return value == null || value.signum() == 0 ? null : value;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String atMost100(BigDecimal value) {
        return value.compareTo(BigDecimal.valueOf(100)) > 0 ? "最多 100%" : null;
    }

    private static String positive(BigDecimal value) {
        return value.signum() <= 0 ? "要大于 0" : null;
    }

    /** "第 3、8、12 行"，太多只列前 20 个 */
    private static String rowsText(List<Integer> rows) {
        String listed = rows.stream().limit(20).map(String::valueOf).collect(Collectors.joining("、"));
        return "第 " + listed + (rows.size() > 20 ? " 等 " + rows.size() + " " : " ") + "行";
    }

    private String categoryPath(Material m, Map<Long, MaterialCategory> categories) {
        if (m.getLegacyAttrs() != null && m.getLegacyAttrs().get(CATEGORY_PATH_KEY) != null) {
            return m.getLegacyAttrs().get(CATEGORY_PATH_KEY);
        }
        MaterialCategory category = categories.get(m.getCategoryId());
        MaterialCategory parent = category == null || category.getParentId() == null ? null : categories.get(category.getParentId());
        if (category == null) {
            return null;
        }
        return parent == null ? category.getName() : parent.getName() + ">" + category.getName();
    }

    /** 已经导过的领猫编码 */
    private Set<String> existingLegacyCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return materialMapper.selectList(new LambdaQueryWrapper<Material>().select(Material::getLegacyCode).in(Material::getLegacyCode, codes))
                .stream().map(Material::getLegacyCode).collect(Collectors.toSet());
    }

    private Set<String> existingSkuLegacyCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>().select(MaterialSku::getLegacyCode)
                        .in(MaterialSku::getLegacyCode, codes))
                .stream().map(MaterialSku::getLegacyCode).collect(Collectors.toSet());
    }

    private Map<String, Color> colorsByCode(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return colorMapper.selectList(new LambdaQueryWrapper<Color>().in(Color::getCode, codes)).stream()
                .collect(Collectors.toMap(Color::getCode, Function.identity()));
    }

    private Map<String, SupplierBrief> suppliersByCode(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return materialMapper.selectSuppliersByCodes(codes).stream()
                .collect(Collectors.toMap(SupplierBrief::getCode, Function.identity()));
    }

    /** 分批 IN 查：一条语句的参数个数有上限 */
    private static <T> List<T> chunked(List<Long> ids, Function<List<Long>, List<T>> query) {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            result.addAll(query.apply(ids.subList(i, Math.min(ids.size(), i + CHUNK))));
        }
        return result;
    }

    /** 物料分类按"一级名称 + 二级名称"对（领猫的路径是名称，不是编码） */
    private static final class CategoryIndex {
        private final Map<String, MaterialCategory> byPath = new HashMap<>();
        private final Map<Long, MaterialCategory> byId = new HashMap<>();

        CategoryIndex(List<MaterialCategory> all) {
            all.forEach(c -> byId.put(c.getId(), c));
            for (MaterialCategory c : all) {
                if (c.getLevel() == 2 && c.getParentId() != null && byId.containsKey(c.getParentId())) {
                    byPath.put(byId.get(c.getParentId()).getName() + "\u0000" + c.getName(), c);
                }
            }
        }

        /** 对不上、停用了：记一处问题，返回 null */
        MaterialCategory match(String path, ExcelImport<MaterialRow> sheet, int row) {
            String[] parts = PATH_SEPARATOR.split(path.strip());
            if (parts.length < 2) {
                sheet.error(row, "物料分类", "物料分类要写到二级，如「面料>梭织」");
                return null;
            }
            MaterialCategory category = byPath.get(parts[0] + "\u0000" + parts[1]);
            if (category == null) {
                sheet.error(row, "物料分类", "物料分类「" + parts[0] + ">" + parts[1] + "」系统里没有：先在 资料 › 基础 › 物料分类 里建好"
                        + "（二级分类的编码是物料编码的前缀，要人定，不自动建）");
                return null;
            }
            MaterialCategory parent = byId.get(category.getParentId());
            if (!Statuses.enabled(category.getStatus()) || !Statuses.enabled(parent.getStatus())) {
                sheet.error(row, "物料分类", "物料分类「" + parts[0] + ">" + parts[1] + "」已经停用了");
                return null;
            }
            return category;
        }
    }
}
