package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelOption;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.style.dto.LookupRow;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleColor;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import com.lingmao.scm.module.style.mapper.StyleColorMapper;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.style.mapper.StyleSkuMapper;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LineScope;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 款式的 Excel 导入导出（RFC 005 §8）。还没有领猫的款式导出样例，先用自己的模板：一张表一行一款，颜色逗号隔开。
 * <p>
 * 只新增：款号照用（老款号不受手填规则限制，只要求不含空格），已存在算错误；有一处不对整批不导（1003）。
 * 品牌字典里没有的新建一项、设计师对不上的留空，都进提示。有颜色的导完直接生成 SKU；状态填启用但没颜色的导成草稿。
 * 导入不推款号流水、不锁设计师编号：老款号不是自动编号的格式。
 * <p>
 * 导出：款式一张表（列和模板一样，导出来改了能导回去，款号已存在的要先删），另有"导出 SKU"一张（贴条码、对聚水潭用）
 */
@Component
@RequiredArgsConstructor
public class StyleExcel {

    static final String STYLE_SHEET = "款式";
    static final String SKU_SHEET = "导出 SKU";
    /** 进 legacy_attrs 的列，有值才存（RFC 005 §8） */
    static final String[] LEGACY = {"等级", "版型库单号", "财务成本价", "建议零售价", "原品牌", "选款日期"};

    /** 颜色的分隔：中英文逗号、顿号、分号 */
    private static final Pattern LIST_SEPARATOR = Pattern.compile("\\s*[,，、;；]\\s*");
    private static final Pattern HAS_SPACE = Pattern.compile(".*\\s.*");
    /** 一次 IN 最多带几个参数：导出最多 5 万款，分批查 */
    private static final int CHUNK = 1000;

    private final StyleMapper styleMapper;
    private final StyleColorMapper styleColorMapper;
    private final StyleSkuMapper styleSkuMapper;
    private final StyleDicts dicts;
    private final StyleCategories categories;
    private final SkuGenerator skuGenerator;
    private final CategoryService categoryService;
    private final ColorService colorService;
    private final SizeGroupService sizeGroupService;
    private final MaterialService materialService;
    private final UserService userService;
    private final DictDataService dictDataService;
    private final TradeFeature tradeFeature;
    private final DocLogService docLogService;
    private final com.lingmao.scm.module.system.service.FileImportService imports;

    // ---------- 行对象 ----------

    /** 款式表的一行 */
    @Data
    public static class StyleRow {
        private String code;
        private String name;
        private String aliasName;
        private String styleClass;
        private String brand;
        private String categoryPath;
        private Integer year;
        private String season;
        private String wave;
        private String styleTag;
        private String series;
        private String gender;
        private String source;
        private String designer;
        private String patternMaker;
        private String sizeGroup;
        private String colors;
        private String mainMaterial;
        private String defaultFactory;
        private BigDecimal tagPrice;
        private BigDecimal refCost;
        private LocalDate launchDate;
        private String productName;
        private String composition;
        private String execStandard;
        private String safetyCategory;
        private String sampleNo;
        private String status;
        private String remark;
        private Map<String, String> legacy = new LinkedHashMap<>();
    }

    /** "导出 SKU"的一行 */
    @Data
    public static class SkuRow {
        private String styleCode;
        private String code;
        private String colorCode;
        private String colorName;
        private String sizeCode;
        private String barcode;
        private BigDecimal tagPrice;
        private String status;
    }

    // ---------- 模板、导出 ----------

    byte[] template() {
        return Excel.template(styleSchema());
    }

    /** ids 已经按款号排好、不超过导出上限 */
    byte[] export(List<Long> ids) {
        Map<Long, Style> byId = chunked(ids, styleMapper::selectByIds).stream()
                .collect(Collectors.toMap(Style::getId, Function.identity()));
        List<Style> styles = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        List<StyleColor> styleColors = chunked(ids, chunk -> styleColorMapper.selectList(new LambdaQueryWrapper<StyleColor>()
                .in(StyleColor::getStyleId, chunk).orderByAsc(StyleColor::getStyleId, StyleColor::getSeq)));
        List<StyleSku> skus = chunked(ids, chunk -> styleSkuMapper.selectList(new LambdaQueryWrapper<StyleSku>()
                .in(StyleSku::getStyleId, chunk)));
        Map<Long, Color> colors = colorService.byIds(Stream.concat(styleColors.stream().map(StyleColor::getColorId),
                skus.stream().map(StyleSku::getColorId)).collect(Collectors.toSet()));
        StyleCategories.Index index = categories.index();
        Map<Long, String> users = userService.nicknames(styles.stream()
                .flatMap(s -> Stream.of(s.getDesignerId(), s.getPatternMakerId())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<Long, SizeGroupVO> groups = sizeGroupService.list().stream()
                .collect(Collectors.toMap(SizeGroupVO::id, Function.identity()));
        Map<Long, LookupRow> materials = chunked(styles.stream().map(Style::getMainMaterialId).filter(Objects::nonNull)
                .distinct().toList(), styleMapper::selectMaterialsByIds).stream()
                .collect(Collectors.toMap(LookupRow::getId, Function.identity()));
        Map<Long, LookupRow> factories = chunked(styles.stream().map(Style::getDefaultFactoryId).filter(Objects::nonNull)
                .distinct().toList(), styleMapper::selectSuppliersByIds).stream()
                .collect(Collectors.toMap(LookupRow::getId, Function.identity()));
        Map<String, String> brandLabels = dicts.labels(StyleDicts.BRAND);
        Map<String, String> seasonLabels = dicts.labels(StyleDicts.SEASON);

        Map<Long, List<StyleColor>> colorsOf = styleColors.stream()
                .collect(Collectors.groupingBy(StyleColor::getStyleId, LinkedHashMap::new, Collectors.toList()));
        List<StyleRow> rows = new ArrayList<>();
        for (Style s : styles) {
            StyleRow row = new StyleRow();
            row.setCode(s.getCode());
            row.setName(s.getName());
            row.setAliasName(s.getAliasName());
            row.setStyleClass(s.getStyleClass());
            row.setBrand(brandLabels.getOrDefault(s.getBrand(), s.getBrand()));
            row.setCategoryPath(index.path(s.getCategoryId()));
            row.setYear(s.getYear());
            row.setSeason(seasonLabels.getOrDefault(s.getSeason(), s.getSeason()));
            row.setWave(s.getWave());
            row.setStyleTag(s.getStyleTag());
            row.setSeries(s.getSeries());
            row.setGender(s.getGender());
            row.setSource(s.getSource());
            row.setDesigner(StyleServiceImpl.nameOf(users, s.getDesignerId()));
            row.setPatternMaker(StyleServiceImpl.nameOf(users, s.getPatternMakerId()));
            SizeGroupVO group = groups.get(s.getSizeGroupId());
            row.setSizeGroup(group == null ? null : group.name());
            row.setColors(colorsOf.getOrDefault(s.getId(), List.of()).stream()
                    .map(sc -> colors.get(sc.getColorId())).filter(Objects::nonNull).map(Color::getCode)
                    .collect(Collectors.joining(",")));
            LookupRow material = s.getMainMaterialId() == null ? null : materials.get(s.getMainMaterialId());
            row.setMainMaterial(material == null ? null : material.getCode());
            LookupRow factory = s.getDefaultFactoryId() == null ? null : factories.get(s.getDefaultFactoryId());
            row.setDefaultFactory(factory == null ? null : factory.getCode());
            row.setTagPrice(s.getTagPrice());
            row.setRefCost(s.getRefCost());
            row.setLaunchDate(s.getLaunchDate());
            row.setProductName(s.getProductName());
            row.setComposition(s.getComposition());
            row.setExecStandard(s.getExecStandard());
            row.setSafetyCategory(s.getSafetyCategory());
            row.setSampleNo(s.getSampleNo());
            row.setStatus(s.getStatus().getLabel());
            row.setRemark(s.getRemark());
            if (s.getLegacyAttrs() != null) {
                row.getLegacy().putAll(s.getLegacyAttrs());
            }
            rows.add(row);
        }

        // SKU 按款式的顺序，款式里按颜色顺序、尺码顺序
        Map<Long, Integer> styleOrder = new HashMap<>();
        for (int i = 0; i < styles.size(); i++) {
            styleOrder.put(styles.get(i).getId(), i);
        }
        Map<String, Integer> colorSeq = new HashMap<>();
        styleColors.forEach(sc -> colorSeq.put(sc.getStyleId() + "|" + sc.getColorId(), sc.getSeq()));
        Comparator<StyleSku> order = Comparator
                .comparing((StyleSku k) -> styleOrder.getOrDefault(k.getStyleId(), Integer.MAX_VALUE))
                .thenComparing(k -> colorSeq.getOrDefault(k.getStyleId() + "|" + k.getColorId(), Integer.MAX_VALUE))
                .thenComparing(k -> sizeIndex(groups.get(byId.get(k.getStyleId()).getSizeGroupId()), k.getSizeCode()))
                .thenComparing(StyleSku::getCode);
        List<SkuRow> skuRows = skus.stream().sorted(order).map(k -> {
            Color color = colors.get(k.getColorId());
            SkuRow row = new SkuRow();
            row.setStyleCode(byId.get(k.getStyleId()).getCode());
            row.setCode(k.getCode());
            row.setColorCode(color == null ? null : color.getCode());
            row.setColorName(color == null ? null : color.getName());
            row.setSizeCode(k.getSizeCode());
            row.setBarcode(k.getBarcode());
            row.setTagPrice(k.getTagPrice());
            row.setStatus(k.getStatus() != null && k.getStatus() == StyleServiceImpl.ENABLED_FLAG ? "启用" : "停用");
            return row;
        }).toList();

        return Excel.exportBook()
                .sheet(FieldPermissions.has(FieldPermissions.COST) ? styleSchema() : styleSchema().without("参考成本价", "财务成本价"), rows)
                .sheet(skuSchema(), skuRows)
                .toBytes();
    }

    // ---------- 导入 ----------

    /** 准备好、要写进库的一款 */
    private record Planned(Style style, List<Color> colors, List<String> sizes) {
    }

    private record ImportPlan(List<Planned> plans, Map<String, Integer> brands, ImportResult result) { }

    public ImportResult importFile(MultipartFile file) {
        return imports.execute("style", "global", "style:style:import", file, Map.of(), () -> parseFile(file), this::writeImport);
    }

    private ImportPlan parseFile(MultipartFile file) {
        ExcelImport<StyleRow> sheet = Excel.read(styleSchema(), file);
        sheet.checkUnique(StyleRow::getCode, "款号");
        List<ExcelImport.Line<StyleRow>> lines = sheet.lines();
        Lookups lookups = lookups(lines);
        Map<String, Integer> newBrands = new LinkedHashMap<>();
        List<Planned> plans = new ArrayList<>();

        for (ExcelImport.Line<StyleRow> line : lines) {
            StyleRow r = line.data();
            int row = line.row();
            Style s = new Style();
            if (r.getCode() != null) {
                if (HAS_SPACE.matcher(r.getCode()).matches()) {
                    sheet.error(row, "款号", "款号里不能有空格");
                } else if (lookups.takenCodes.contains(r.getCode())) {
                    sheet.error(row, "款号", "款号 " + r.getCode() + " 已经有了：导入只新增。没用过的先在列表里删掉再导");
                }
            }
            s.setCode(r.getCode());
            s.setName(r.getName());
            s.setAliasName(r.getAliasName());
            s.setStyleClass(r.getStyleClass());
            if (r.getStyleClass() != null) {
                String productLine = dicts.lineOf(r.getStyleClass());
                if (productLine == null) {
                    sheet.error(row, "大类", "大类「" + r.getStyleClass() + "」还没设品类线：先到 管理后台 › 字典 › 款式大类 里设");
                } else if (!LineScope.current().canSee(productLine)) {
                    sheet.error(row, "大类", "大类「" + r.getStyleClass() + "」属于你看不到的品类线，不能导这个大类的款");
                }
                s.setProductLine(productLine);
            }
            s.setBrand(matchBrand(r.getBrand(), lookups.brands, newBrands, sheet, row));
            s.setCategoryId(matchCategory(r.getCategoryPath(), lookups, sheet, row));
            s.setYear(r.getYear());
            if (r.getSeason() != null) {
                String season = matchSeason(r.getSeason(), lookups.seasons);
                if (season == null) {
                    sheet.error(row, "季节", "季节「" + r.getSeason() + "」不在字典里，或者已经停用了：先在 管理后台 › 字典 › 季节 里加");
                }
                s.setSeason(season);
            }
            s.setWave(r.getWave());
            s.setStyleTag(r.getStyleTag());
            s.setSeries(r.getSeries());
            s.setGender(r.getGender());
            s.setSource(Objects.requireNonNullElse(r.getSource(), StyleDicts.OWN));
            s.setDesignerId(matchUser(r.getDesigner(), "设计师", lookups.users, sheet, row));
            s.setPatternMakerId(matchUser(r.getPatternMaker(), "版师", lookups.users, sheet, row));
            SizeGroupVO group = matchSizeGroup(r.getSizeGroup(), lookups.sizeGroups, sheet, row);
            s.setSizeGroupId(group == null ? null : group.id());
            List<Color> colors = matchColors(r.getColors(), lookups, sheet, row);
            s.setMainMaterialId(matchMaterial(r.getMainMaterial(), lookups, sheet, row));
            s.setDefaultFactoryId(matchFactory(r.getDefaultFactory(), lookups, sheet, row));
            s.setTagPrice(r.getTagPrice());
            s.setRefCost(FieldPermissions.price(FieldPermissions.COST, null, r.getRefCost()));
            s.setLaunchDate(r.getLaunchDate());
            s.setProductName(r.getProductName());
            s.setComposition(r.getComposition());
            s.setExecStandard(r.getExecStandard());
            s.setSafetyCategory(r.getSafetyCategory());
            s.setSampleNo(r.getSampleNo());
            s.setRemark(r.getRemark());
            StyleStatus status = statusOf(r.getStatus());
            if (status == null) {
                sheet.error(row, "状态", "状态只认 启用、草稿、停用，不填 = 启用");
            } else if (status == StyleStatus.ENABLED && colors.isEmpty()) {
                status = StyleStatus.DRAFT;
                sheet.note(row, "状态", "没有颜色，生成不了 SKU，导成了草稿：在详情里加颜色、生成 SKU 后再启用");
            }
            s.setStatus(status);
            s.setTechPackStatus(TechPackStatus.NONE);
            Map<String, String> legacy = new LinkedHashMap<>();
            r.getLegacy().forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    legacy.put(k, v);
                }
            });
            FieldPermissions.preserve(FieldPermissions.COST, null, legacy.get("财务成本价"));
            s.setLegacyAttrs(legacy.isEmpty() ? null : legacy);
            plans.add(new Planned(s, colors, group == null ? List.of() : group.sizes()));
        }
        sheet.dataOrThrow();
        return new ImportPlan(plans, newBrands, ImportResult.of(plans.size(), sheet.notes()));
    }

    private ImportResult writeImport(ImportPlan imported) {
        // 都没问题了才建品牌：品牌是甲方的名字，不进公开仓库的迁移，导款式时建（数据库设计 §3.2）
        imported.brands().keySet().forEach(this::createBrand);
        for (Planned plan : imported.plans()) {
            Style s = plan.style();
            styleMapper.insert(s);
            int seq = 1;
            for (Color color : plan.colors()) {
                StyleColor sc = new StyleColor();
                sc.setStyleId(s.getId());
                sc.setColorId(color.getId());
                sc.setSeq(seq++);
                sc.setStatus(StyleServiceImpl.ENABLED_FLAG);
                styleColorMapper.insert(sc);
            }
            List<String> skus = skuGenerator.generate(s, plan.colors(), plan.sizes());
            docLogService.add(BizType.STYLE, s.getId(), "import", null, s.getStatus(),
                    skus.isEmpty() ? null : "生成 " + skus.size() + " 个 SKU");
        }
        return imported.result();
    }

    /** 导入要查的东西：一次查好，不每行查 */
    private static final class Lookups {
        Set<String> takenCodes;
        StyleCategories.Index categories;
        final Map<Long, String> categoryProblems = new HashMap<>();
        List<DictOption> brands;
        List<DictOption> seasons;
        Map<String, Long> users;
        Map<String, SizeGroupVO> sizeGroups;
        Map<String, ColorOption> colorsByCode;
        Map<String, ColorOption> colorsByName;
        Map<Long, Color> colorEntities;
        Map<String, LookupRow> materialsByCode;
        Map<Long, Integer> materialStatus;
        List<LookupRow> suppliers;
    }

    private Lookups lookups(List<ExcelImport.Line<StyleRow>> lines) {
        Lookups l = new Lookups();
        List<String> codes = lines.stream().map(x -> x.data().getCode()).filter(Objects::nonNull).distinct().toList();
        l.takenCodes = codes.isEmpty() ? Set.of() : chunkedText(codes, chunk -> styleMapper.selectList(
                new LambdaQueryWrapper<Style>().select(Style::getCode).in(Style::getCode, chunk)))
                .stream().map(Style::getCode).collect(Collectors.toSet());
        l.categories = categories.index();
        l.brands = dicts.options(StyleDicts.BRAND);
        l.seasons = dicts.options(StyleDicts.SEASON);
        l.users = userService.idsByNickname(lines.stream()
                .flatMap(x -> Stream.of(x.data().getDesigner(), x.data().getPatternMaker()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        l.sizeGroups = sizeGroupService.list().stream()
                .collect(Collectors.toMap(SizeGroupVO::name, Function.identity(), (a, b) -> a));
        List<ColorOption> colors = colorService.options();
        l.colorsByCode = colors.stream().collect(Collectors.toMap(c -> c.code().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
        l.colorsByName = colors.stream().collect(Collectors.toMap(ColorOption::name, Function.identity(), (a, b) -> a));
        l.colorEntities = colorService.byIds(colors.stream().map(ColorOption::id).toList());
        List<String> materialCodes = lines.stream().map(x -> x.data().getMainMaterial()).filter(Objects::nonNull).distinct().toList();
        l.materialsByCode = chunkedText(materialCodes, styleMapper::selectMaterialsByCodes).stream()
                .collect(Collectors.toMap(LookupRow::getCode, Function.identity(), (a, b) -> a));
        l.materialStatus = materialService.materialRefs(l.materialsByCode.values().stream().map(LookupRow::getId).toList())
                .stream().collect(Collectors.toMap(MaterialRef::id, m -> Objects.requireNonNullElse(m.status(), 0)));
        List<String> factoryKeys = lines.stream().map(x -> x.data().getDefaultFactory()).filter(Objects::nonNull).distinct().toList();
        l.suppliers = chunkedText(factoryKeys, styleMapper::selectSuppliersByKeys);
        return l;
    }

    /** 品牌按名称对（填值也认）；字典里没有的记下来，校验都过了再建，进提示。停用了的报错 */
    private static String matchBrand(String text, List<DictOption> brands, Map<String, Integer> newBrands,
                                     ExcelImport<StyleRow> sheet, int row) {
        if (text == null) {
            return null;
        }
        List<DictOption> matched = brands.stream().filter(o -> o.label().equals(text) || o.value().equals(text)).toList();
        if (matched.isEmpty()) {
            if (newBrands.putIfAbsent(text, row) == null) {
                sheet.note(row, "品牌", "品牌「" + text + "」字典里没有，导入时新建了一项");
            }
            return text;
        }
        return matched.stream().filter(DictOption::enabled).map(DictOption::value).findFirst().orElseGet(() -> {
            sheet.error(row, "品牌", "品牌「" + text + "」已经停用了：先在 管理后台 › 字典 › 品牌 里启用");
            return null;
        });
    }

    private void createBrand(String label) {
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode(StyleDicts.BRAND);
        req.setLabel(label);
        req.setValue(label);
        req.setRemark("导入款式时新建");
        dictDataService.create(req);
    }

    /** 品类写全路径，要是启用的最末一级（CategoryService.checkSelectable 的规则） */
    private Long matchCategory(String path, Lookups l, ExcelImport<StyleRow> sheet, int row) {
        if (path == null) {
            return null;
        }
        TreeNode node = l.categories.byPath(path);
        if (node == null) {
            sheet.error(row, "品类", "品类「" + path + "」系统里没有：写全路径，如 女装/上衣/衬衫");
            return null;
        }
        String problem = l.categoryProblems.computeIfAbsent(node.getId(), id -> {
            try {
                categoryService.checkSelectable(id);
                return "";
            } catch (BizException e) {
                return e.getMessage();
            }
        });
        if (!problem.isEmpty()) {
            sheet.error(row, "品类", problem);
            return null;
        }
        return node.getId();
    }

    /** 季节去掉末尾的"季"按名称对（"春季" → "春"），填值也认；只认启用的 */
    static String matchSeason(String text, List<DictOption> seasons) {
        String trimmed = text.strip();
        String bare = trimmed.endsWith("季") && trimmed.length() > 1 ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return seasons.stream().filter(DictOption::enabled)
                .filter(o -> o.label().equals(trimmed) || o.label().equals(bare) || o.value().equalsIgnoreCase(trimmed))
                .map(DictOption::value).findFirst().orElse(null);
    }

    /** 按显示名找人：只认启用的、不重名的；对不上的留空、进提示（RFC 005 §8） */
    private static Long matchUser(String name, String what, Map<String, Long> users, ExcelImport<StyleRow> sheet, int row) {
        if (name == null) {
            return null;
        }
        Long id = users.get(name);
        if (id == null) {
            sheet.note(row, what, what + "「" + name + "」对不上用户（没有这个人、停用了或者重名），留空了");
        }
        return id;
    }

    private static SizeGroupVO matchSizeGroup(String name, Map<String, SizeGroupVO> groups, ExcelImport<StyleRow> sheet, int row) {
        if (name == null) {
            return null;
        }
        SizeGroupVO group = groups.get(name);
        if (group == null) {
            sheet.error(row, "尺码组", "尺码组「" + name + "」系统里没有：先在 资料 › 基础 › 尺码组 里建");
            return null;
        }
        if (group.status() == null || group.status() != StyleServiceImpl.ENABLED_FLAG) {
            sheet.error(row, "尺码组", "尺码组「" + name + "」已经停用了");
            return null;
        }
        return group;
    }

    /** 颜色写编码或名称，逗号隔开；有一个对不上（没有、停用了）整批不导（RFC 005 §8） */
    private static List<Color> matchColors(String text, Lookups l, ExcelImport<StyleRow> sheet, int row) {
        if (text == null) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<Color> colors = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (String part : LIST_SEPARATOR.split(text.strip())) {
            if (part.isEmpty()) {
                continue;
            }
            ColorOption option = l.colorsByCode.getOrDefault(part.toUpperCase(Locale.ROOT), l.colorsByName.get(part));
            if (option == null) {
                unmatched.add(part);
            } else if (seen.add(option.id())) {
                colors.add(l.colorEntities.get(option.id()));
            }
        }
        if (!unmatched.isEmpty()) {
            sheet.error(row, "颜色", "颜色「" + String.join("、", unmatched) + "」系统里没有，或者已经停用了："
                    + "先在 资料 › 基础 › 颜色 里建，或者先导物料把颜色带进来");
        }
        return colors;
    }

    private static Long matchMaterial(String code, Lookups l, ExcelImport<StyleRow> sheet, int row) {
        if (code == null) {
            return null;
        }
        LookupRow material = l.materialsByCode.get(code);
        if (material == null) {
            sheet.error(row, "主力面料", "物料 " + code + " 系统里没有：先导物料，再导款式");
            return null;
        }
        if (l.materialStatus.getOrDefault(material.getId(), 0) != StyleServiceImpl.ENABLED_FLAG) {
            sheet.error(row, "主力面料", "物料 " + code + " " + material.getName() + " 已经停用了");
            return null;
        }
        return material.getId();
    }

    /** 默认加工厂：先按编码对，对不上再按简称；简称有几家同名的要写编码。只当默认值，不看状态（架构 A23） */
    private static Long matchFactory(String key, Lookups l, ExcelImport<StyleRow> sheet, int row) {
        if (key == null) {
            return null;
        }
        List<LookupRow> byCode = l.suppliers.stream().filter(s -> key.equals(s.getCode())).toList();
        if (!byCode.isEmpty()) {
            return byCode.getFirst().getId();
        }
        List<LookupRow> byName = l.suppliers.stream().filter(s -> key.equals(s.getName())).toList();
        if (byName.isEmpty()) {
            sheet.error(row, "默认加工厂", "供应商「" + key + "」系统里没有：写供应商编码或简称，供应商要先导入");
            return null;
        }
        if (byName.size() > 1) {
            sheet.error(row, "默认加工厂", "简称「" + key + "」有 " + byName.size() + " 家供应商，请写供应商编码");
            return null;
        }
        return byName.getFirst().getId();
    }

    /** 启用（默认）、草稿、停用；也认英文名。认不出返回 null */
    static StyleStatus statusOf(String text) {
        if (text == null || text.isBlank()) {
            return StyleStatus.ENABLED;
        }
        String t = text.strip();
        for (StyleStatus status : StyleStatus.values()) {
            if (status.getLabel().equals(t) || status.name().equalsIgnoreCase(t)) {
                return status;
            }
        }
        return null;
    }

    // ---------- 列定义 ----------

    ExcelSchema<StyleRow> styleSchema() {
        List<ExcelOption> sources = tradeFeature.filterDict(StyleDicts.SOURCE, options(StyleDicts.SOURCE), ExcelOption::value);
        List<ExcelColumn<StyleRow, ?>> columns = new ArrayList<>(List.of(
                ExcelColumn.text("款号", StyleRow::getCode, StyleRow::setCode).required().code().maxLength(32)
                        .example("FS2611080701").note("照用老款号（不受新建时的格式限制，不能有空格）；已经有的不导"),
                ExcelColumn.text("款式名称", StyleRow::getName, StyleRow::setName).required().maxLength(100),
                ExcelColumn.text("花名", StyleRow::getAliasName, StyleRow::setAliasName).maxLength(100)
                        .note("款式的俗称，选填，不查重"),
                ExcelColumn.choice("大类", options(StyleDicts.STYLE_CLASS), StyleRow::getStyleClass, StyleRow::setStyleClass).required(),
                ExcelColumn.text("品牌", StyleRow::getBrand, StyleRow::setBrand).required().maxLength(64)
                        .note("按字典「品牌」的名称对；字典里没有的导入时新建一项"),
                ExcelColumn.text("品类", StyleRow::getCategoryPath, StyleRow::setCategoryPath).required().maxLength(200)
                        .example("女装/上衣/衬衫").note("写全路径，用 / 连起来，要选到最末一级"),
                ExcelColumn.integer("年份", StyleRow::getYear, StyleRow::setYear).required()
                        .check(y -> y < 2000 || y > 2099 ? "年份要在 2000-2099 之间" : null),
                ExcelColumn.text("季节", StyleRow::getSeason, StyleRow::setSeason).required().maxLength(64)
                        .note("填字典「季节」里的名称，「春季」也认"),
                ExcelColumn.choice("波段", options(StyleDicts.WAVE), StyleRow::getWave, StyleRow::setWave),
                ExcelColumn.choice("风格", options(StyleDicts.STYLE_TAG), StyleRow::getStyleTag, StyleRow::setStyleTag),
                ExcelColumn.choice("系列", options(StyleDicts.SERIES), StyleRow::getSeries, StyleRow::setSeries),
                ExcelColumn.choice("性别", options(StyleDicts.GENDER), StyleRow::getGender, StyleRow::setGender),
                ExcelColumn.choice("来源", sources, StyleRow::getSource, StyleRow::setSource).note("不填 = 自主开发"),
                ExcelColumn.text("设计师", StyleRow::getDesigner, StyleRow::setDesigner).maxLength(64)
                        .note("用户的显示名；对不上的留空，导完列在提示里"),
                ExcelColumn.text("版师", StyleRow::getPatternMaker, StyleRow::setPatternMaker).maxLength(64)
                        .note("用户的显示名；对不上的留空"),
                ExcelColumn.text("尺码组", StyleRow::getSizeGroup, StyleRow::setSizeGroup).required().maxLength(64)
                        .note("尺码组的名称，要是启用的"),
                ExcelColumn.text("颜色", StyleRow::getColors, StyleRow::setColors).maxLength(500)
                        .example("KQ,黑色").note("颜色编码或名称，多个用逗号隔开；有颜色的导完按 颜色 × 尺码组 生成 SKU"),
                ExcelColumn.text("主力面料", StyleRow::getMainMaterial, StyleRow::setMainMaterial).code().maxLength(32)
                        .note("物料编码，物料要先导入"),
                ExcelColumn.text("默认加工厂", StyleRow::getDefaultFactory, StyleRow::setDefaultFactory).maxLength(100)
                        .note("供应商编码或简称，供应商要先导入"),
                ExcelColumn.decimal("吊牌价", 2, StyleRow::getTagPrice, StyleRow::setTagPrice).nonNegative().note("元"),
                ExcelColumn.decimal("参考成本价", 4, StyleRow::getRefCost, StyleRow::setRefCost).nonNegative().note("元/件，手填"),
                ExcelColumn.date("上市日期", StyleRow::getLaunchDate, StyleRow::setLaunchDate),
                ExcelColumn.text("品名", StyleRow::getProductName, StyleRow::setProductName).maxLength(50).note("吊牌上的品名"),
                ExcelColumn.text("成分", StyleRow::getComposition, StyleRow::setComposition).maxLength(255),
                ExcelColumn.choice("执行标准", options(StyleDicts.EXEC_STANDARD), StyleRow::getExecStandard, StyleRow::setExecStandard),
                ExcelColumn.choice("安全类别", options(StyleDicts.SAFETY_CATEGORY), StyleRow::getSafetyCategory, StyleRow::setSafetyCategory),
                ExcelColumn.text("样衣编号", StyleRow::getSampleNo, StyleRow::setSampleNo).code().maxLength(32),
                ExcelColumn.text("状态", StyleRow::getStatus, StyleRow::setStatus).maxLength(8)
                        .note("启用 / 草稿 / 停用，不填 = 启用；启用的要有颜色，没有的导成草稿"),
                ExcelColumn.text("备注", StyleRow::getRemark, StyleRow::setRemark).maxLength(500)));
        for (String header : LEGACY) {
            columns.add(ExcelColumn.<StyleRow>text(header, r -> r.getLegacy().get(header), (r, v) -> r.getLegacy().put(header, v))
                    .maxLength(500).note("领猫的附加属性：原样存着，款式详情里能看"));
        }
        @SuppressWarnings("unchecked")
        ExcelColumn<StyleRow, ?>[] array = columns.toArray(new ExcelColumn[0]);
        return ExcelSchema.of(STYLE_SHEET, StyleRow::new, array);
    }

    ExcelSchema<SkuRow> skuSchema() {
        return ExcelSchema.of(SKU_SHEET, SkuRow::new,
                ExcelColumn.text("款号", SkuRow::getStyleCode, SkuRow::setStyleCode).code().maxLength(32),
                ExcelColumn.text("SKU 码", SkuRow::getCode, SkuRow::setCode).code().maxLength(64),
                ExcelColumn.text("颜色编码", SkuRow::getColorCode, SkuRow::setColorCode).code().maxLength(3),
                ExcelColumn.text("颜色", SkuRow::getColorName, SkuRow::setColorName).maxLength(32),
                ExcelColumn.text("尺码", SkuRow::getSizeCode, SkuRow::setSizeCode).code().maxLength(16),
                ExcelColumn.text("条码", SkuRow::getBarcode, SkuRow::setBarcode).code().maxLength(32),
                ExcelColumn.decimal("吊牌价", 2, SkuRow::getTagPrice, SkuRow::setTagPrice).note("空 = 用款式的吊牌价"),
                ExcelColumn.text("状态", SkuRow::getStatus, SkuRow::setStatus).maxLength(8));
    }

    // ---------- 小工具 ----------

    private List<ExcelOption> options(String typeCode) {
        return dicts.options(typeCode).stream().map(o -> new ExcelOption(o.label(), o.value(), o.enabled())).toList();
    }

    private static int sizeIndex(SizeGroupVO group, String size) {
        int i = group == null ? -1 : group.sizes().indexOf(size);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 分批 IN 查：一条语句的参数个数有上限 */
    private static <T> List<T> chunked(List<Long> ids, Function<List<Long>, List<T>> query) {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            result.addAll(query.apply(ids.subList(i, Math.min(ids.size(), i + CHUNK))));
        }
        return result;
    }

    private static <T> List<T> chunkedText(List<String> keys, Function<Collection<String>, List<T>> query) {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += CHUNK) {
            result.addAll(query.apply(keys.subList(i, Math.min(keys.size(), i + CHUNK))));
        }
        return result;
    }
}
