package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomImportVO;
import com.lingmao.scm.module.techpack.dto.BomItemVO;
import com.lingmao.scm.module.techpack.dto.BomSkuVO;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.Line;
import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.SheetData;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;
import com.lingmao.scm.security.FieldPermissions;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 甲方 BOM 表 → 资料包物料清单的行（RFC 011 §4.2）。BomSheetReader 读出表里写了什么，这里和物料库、供应商、款式颜色对上：
 * <ul>
 *   <li>物料：在启用的物料里按名称找，同名的用规格去对 SKU 的规格；人工挑过的行（picks：Excel 行号 → 物料 id）直接用挑的</li>
 *   <li>配料：颜色写"/""-"或空着 → 固定；写一个颜色 → 固定，配这个颜色的 SKU；写几个（顿号、斜杠、逗号分开）或"顺色" → 随款色，
 *       款式每个颜色配同色的 SKU（物料 SKU 和款式用同一张颜色表）</li>
 *   <li>单位：表里是厘米、物料按米管的，用量 ÷ 100、单价 × 100；别的不一样标出来让人改</li>
 *   <li>损耗：整列是系数（Num.lossFactorColumn）就 1.03 → 3%</li>
 *   <li>供应商按简称、全称找，找不到用物料的默认供应商；备注"含税运""含运不含税"换成含税、含运两个标记，别的字进备注</li>
 *   <li>部位：A 料 → 大身，B 料 → 里布；辅料按物料名称里带的部位字（"YKK 拉链" → 拉链、"主唛" → 主唛）</li>
 * </ul>
 * 其余的默认值（来源、档案价、含税含运）和页面上加一行一样取 BomPricing。没有看成本权限的人：单价、加工费、运费都不出、不带。
 */
@Component
@RequiredArgsConstructor
class BomImporter {

    static final String OK = "OK";
    static final String CHECK = "CHECK";
    static final String UNMATCHED = "UNMATCHED";

    /** 颜色格里写这些 = 不分颜色 */
    private static final Set<String> NO_COLOR = Set.of("", "/", "-", "—", "无", "通用");
    private static final String METER = "METER";
    private static final String CM = "CM";

    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final DictDataService dictDataService;
    private final BomPricing bomPricing;

    BomImportVO parse(Workbook workbook, Ctx ctx, Integer wanted, Map<Integer, Long> picks) {
        boolean cost = FieldPermissions.has(FieldPermissions.COST);
        List<SheetData> sheets = BomSheetReader.read(workbook);
        SheetData sheet = choose(sheets, ctx.owner(), wanted);
        List<BomImportVO.Sheet> options = sheets.stream()
                .map(s -> new BomImportVO.Sheet(s.index(), s.sheetName(), s.title(), s.bom())).toList();
        List<BomImportVO.Row> rows = rows(sheet, ctx, picks, cost);
        return new BomImportVO(options, sheet.index(), rows, cost ? sheet.laborPrice() : null,
                cost ? sheet.fees().stream().map(f -> new BomImportVO.Fee(f.name(), Num.money(f.amount()))).toList() : List.of());
    }

    /** 指定了用指定的；不然按第 1 行的款号对上这个款，再按款名，都对不上用第一张能认的 */
    static SheetData choose(List<SheetData> sheets, PackOwner owner, Integer wanted) {
        if (wanted != null) {
            SheetData s = wanted >= 0 && wanted < sheets.size() ? sheets.get(wanted) : null;
            if (s == null) {
                throw new BizException("文件里没有第 " + (wanted + 1) + " 张表");
            }
            if (!s.bom()) {
                throw new BizException("「" + s.sheetName() + "」不是物料清单：找不到写着「物料名称」「单件用量」的表头，换一张");
            }
            return s;
        }
        List<SheetData> boms = sheets.stream().filter(SheetData::bom).toList();
        if (boms.isEmpty()) {
            throw new BizException("认不出这张表：找不到写着「物料名称」「单件用量」的表头。物料清单照常手工填");
        }
        return boms.stream().filter(s -> s.code() != null && s.code().equalsIgnoreCase(owner.code())).findFirst()
                .or(() -> boms.stream().filter(s -> same(s.name(), owner.name()) || same(s.sheetName(), owner.name())).findFirst())
                .orElse(boms.getFirst());
    }

    private List<BomImportVO.Row> rows(SheetData sheet, Ctx ctx, Map<Integer, Long> picks, boolean cost) {
        List<Line> lines = sheet.lines();
        Map<String, List<MaterialSkuRef>> byName = materialService.enabledSkusByNames(lines.stream()
                .filter(l -> !picks.containsKey(l.row())).map(Line::name).toList());
        Map<Long, List<MaterialSkuRef>> picked = materialService.enabledSkusOf(picks.values());
        Map<String, List<SupplierRef>> suppliers = supplierService.refsByNames(lines.stream()
                .flatMap(l -> supplierNames(l.supplier()).stream()).toList());
        Map<String, String> unitByLabel = new HashMap<>();
        Map<String, String> unitLabels = new HashMap<>();
        for (DictOption o : dictDataService.listByType("unit")) {
            unitByLabel.putIfAbsent(o.label().strip(), o.value());
            unitLabels.put(o.value(), o.label());
        }
        List<DictOption> parts = dictDataService.listByType("bom_part").stream().filter(DictOption::enabled).toList();
        boolean factor = Num.lossFactorColumn(lines.stream().map(Line::loss).toList());

        List<Draft> drafts = new ArrayList<>();
        for (Line line : lines) {
            Draft d = new Draft(line);
            drafts.add(d);
            Long pick = picks.get(line.row());
            List<MaterialSkuRef> candidates = pick != null ? picked.getOrDefault(pick, List.of())
                    : byName.getOrDefault(key(line.name()), List.of());
            if (pick != null && candidates.isEmpty()) {
                d.notes.add("挑的物料停用了或者没有启用的 SKU，换一个");
                continue;
            }
            List<MaterialSkuRef> skus = material(candidates, line, d);
            if (skus == null) {
                continue;
            }
            d.material = skus.getFirst().material();
            d.supplier = supplier(line.supplier(), suppliers, d);
            d.part = part(line, d.material.name(), parts);
            if (d.part == null) {
                d.notes.add("部位要选");
            }
            pickSkus(d, skus, line, ctx.colors());
            units(d, line, unitByLabel, unitLabels);
            loss(d, line, factor);
            remark(d, line.remark());
            if (!line.variants().isEmpty()) {
                d.notes.add("下面还有 " + line.variants().size() + " 行只写了规格（" + String.join("、", line.variants())
                        + "），可能是按尺码配不同的 SKU：导进来以后在配料里选「随尺码」");
            }
        }

        List<Draft> matched = drafts.stream().filter(d -> d.material != null).toList();
        List<BomDefaultVO> defaults = matched.isEmpty() ? List.of() : bomPricing.defaults(matched.stream().map(d -> {
            BomDefaultReq req = new BomDefaultReq();
            req.setMaterialId(d.material.id());
            req.setSupplierId(d.supplier);
            req.setSkuIds(d.skus.stream().map(BomSkuVO::getMaterialSkuId).distinct().toList());
            return req;
        }).toList());
        for (int i = 0; i < matched.size(); i++) {
            matched.get(i).item = item(matched.get(i), defaults.get(i), cost);
        }

        return drafts.stream().map(d -> new BomImportVO.Row(d.line.row(), source(d.line, cost), d.item,
                d.item == null ? UNMATCHED : d.notes.isEmpty() ? OK : CHECK, List.copyOf(d.notes))).toList();
    }

    /** 一行在识别中的状态 */
    private static final class Draft {
        final Line line;
        final List<String> notes = new ArrayList<>();
        MaterialRef material;
        Long supplier;
        String part;
        BomSkuMode skuMode = BomSkuMode.FIXED;
        final List<BomSkuVO> skus = new ArrayList<>();
        BigDecimal usage;
        BigDecimal price;
        BigDecimal loss;
        Boolean taxIncluded;
        Boolean freightIncluded;
        String remark;
        BomItemVO item;

        Draft(Line line) {
            this.line = line;
            this.usage = line.usage();
            this.price = line.price();
        }
    }

    /** 同名的几个物料里挑一个：只有一个就是它，几个的用规格对 SKU 的规格。对不上返回 null（没认出来，notes 写原因） */
    private static List<MaterialSkuRef> material(List<MaterialSkuRef> candidates, Line line, Draft d) {
        Map<Long, List<MaterialSkuRef>> byMaterial = candidates.stream()
                .collect(Collectors.groupingBy(s -> s.material().id(), LinkedHashMap::new, Collectors.toList()));
        if (byMaterial.isEmpty()) {
            d.notes.add("物料库里没有「" + line.name() + "」：从物料库挑一个，或者按这一行新建");
            return null;
        }
        if (byMaterial.size() == 1) {
            return byMaterial.values().iterator().next();
        }
        List<List<MaterialSkuRef>> bySpec = byMaterial.values().stream()
                .filter(skus -> skus.stream().anyMatch(s -> same(s.spec(), line.spec()))).toList();
        if (bySpec.size() == 1) {
            return bySpec.getFirst();
        }
        d.notes.add("物料库里叫「" + line.name() + "」的有 " + byMaterial.size() + " 个（"
                + byMaterial.values().stream().map(s -> s.getFirst().material().code()).collect(Collectors.joining("、"))
                + "），规格对不上，挑一个");
        return null;
    }

    /** 配料方式和配哪些 SKU（类注释里的规则）。同一个颜色有几个 SKU 时用规格挑，再不行取第一个 */
    private static void pickSkus(Draft d, List<MaterialSkuRef> skus, Line line, List<ColorOption> styleColors) {
        String text = line.color().strip();
        if (NO_COLOR.contains(text)) {
            MaterialSkuRef sku = skus.size() == 1 ? skus.getFirst()
                    : best(skus.stream().filter(s -> s.colorId() == null).toList(), line.spec());
            if (sku == null) {
                sku = best(skus, line.spec(), true);
            }
            if (sku == null) {
                d.notes.add("物料有 " + skus.size() + " 个 SKU，配哪一个要选");
            } else {
                d.skus.add(skuVO(sku, null));
            }
            return;
        }
        List<String> names = colorNames(text, skus, styleColors);
        if (names.size() == 1) {
            MaterialSkuRef sku = best(skus.stream().filter(s -> sameColor(s.colorName(), names.getFirst())).toList(), line.spec());
            if (sku == null) {
                d.notes.add("物料里没有「" + names.getFirst() + "」的 SKU，配料要选");
            } else {
                d.skus.add(skuVO(sku, null));
            }
            return;
        }
        // 几个颜色、顺色：随款色
        d.skuMode = BomSkuMode.BY_COLOR;
        List<String> missing = new ArrayList<>();
        for (ColorOption color : styleColors) {
            MaterialSkuRef sku = best(skus.stream().filter(s -> Objects.equals(s.colorId(), color.id())
                    || s.colorId() == null && sameColor(s.colorName(), color.name())).toList(), line.spec());
            if (sku == null) {
                missing.add(color.name());
            } else {
                d.skus.add(skuVO(sku, color));
            }
        }
        if (!missing.isEmpty()) {
            d.notes.add(String.join("、", missing) + " 没配料：物料里没有这个颜色的 SKU");
        }
        List<String> extra = names.stream().filter(n -> styleColors.stream().noneMatch(c -> sameColor(c.name(), n))).toList();
        if (!extra.isEmpty()) {
            d.notes.add("表里的 " + String.join("、", extra) + " 不是这个款的颜色，没配");
        }
    }

    /**
     * 颜色格里写的几个颜色。整格就是一个颜色名（物料 SKU 或者款式里有）就不拆，颜色名里可能有空格；
     * 不然按顿号、斜杠、逗号拆，拆不开再按空白拆。写"顺色"的是空列表（随款色）
     */
    private static List<String> colorNames(String text, List<MaterialSkuRef> skus, List<ColorOption> styleColors) {
        if (text.contains("顺色")) {
            return List.of();
        }
        if (skus.stream().anyMatch(s -> sameColor(s.colorName(), text)) || styleColors.stream().anyMatch(c -> sameColor(c.name(), text))) {
            return List.of(text);
        }
        List<String> parts = split(text, "[、/,，;；]+");
        return parts.size() > 1 ? parts : split(text, "\\s+");
    }

    private static List<String> split(String text, String regex) {
        return Arrays.stream(text.split(regex)).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** 规格对得上的那个；对不上的、或者 strict = false 时只有一个就是它 */
    private static MaterialSkuRef best(List<MaterialSkuRef> skus, String spec) {
        return best(skus, spec, false);
    }

    private static MaterialSkuRef best(List<MaterialSkuRef> skus, String spec, boolean strict) {
        List<MaterialSkuRef> bySpec = skus.stream().filter(s -> same(s.spec(), spec)).toList();
        if (bySpec.size() == 1) {
            return bySpec.getFirst();
        }
        if (strict) {
            return null;
        }
        return skus.isEmpty() ? null : bySpec.isEmpty() ? skus.getFirst() : bySpec.getFirst();
    }

    /** 表里的单位和物料的库存单位：一样的不管；厘米、米互换；别的不一样标出来 */
    private static void units(Draft d, Line line, Map<String, String> unitByLabel, Map<String, String> unitLabels) {
        String stock = d.material.stockUnit();
        String text = line.unit().strip();
        if (text.isEmpty() || NO_COLOR.contains(text)) {
            return;
        }
        String unit = unitByLabel.getOrDefault(text, text);
        if (unit.equals(stock)) {
            return;
        }
        String stockLabel = unitLabels.getOrDefault(stock, stock);
        if (CM.equals(unit) && METER.equals(stock) || METER.equals(unit) && CM.equals(stock)) {
            BigDecimal rate = CM.equals(unit) ? new BigDecimal("0.01") : new BigDecimal(100);
            d.usage = d.usage == null ? null : d.usage.multiply(rate);
            d.price = d.price == null ? null : d.price.divide(rate, 6, RoundingMode.HALF_UP);
            d.notes.add("表里按" + text + "写，物料按" + stockLabel + "管：用量、单价换算过了");
            return;
        }
        d.notes.add("表里的单位是「" + text + "」，物料按「" + stockLabel + "」管：用量、单价要按" + stockLabel + "改");
    }

    private static void loss(Draft d, Line line, boolean factor) {
        BigDecimal v = line.loss();
        if (v == null) {
            if (!line.lossText().isEmpty()) {
                d.notes.add("损耗「" + line.lossText() + "」不是数，用了物料的默认损耗");
            }
            return;
        }
        if (factor) {
            d.loss = v.signum() == 0 ? BigDecimal.ZERO : v.subtract(BigDecimal.ONE).movePointRight(2);
        } else {
            d.loss = v;
            if (v.compareTo(BigDecimal.ONE) >= 0 && v.compareTo(BigDecimal.TWO) < 0) {
                d.notes.add("损耗 " + v.toPlainString() + " 按 " + v.toPlainString() + "% 算的；要是系数（" + v.toPlainString()
                        + " 倍），改成 " + v.subtract(BigDecimal.ONE).movePointRight(2).stripTrailingZeros().toPlainString());
            }
        }
        d.loss = d.loss.setScale(2, RoundingMode.HALF_UP);
        if (d.loss.compareTo(new BigDecimal(1000)) >= 0) {
            d.notes.add("损耗 " + line.lossText() + " 太大了，没导，用了物料的默认损耗");
            d.loss = null;
        }
    }

    /** "含税运""含运不含税"这类换成两个标记；认不出来的字原样进备注 */
    private static void remark(Draft d, String text) {
        String t = text.replaceAll("\\s+", "");
        if (t.isEmpty()) {
            return;
        }
        Boolean tax = t.contains("不含税") ? Boolean.FALSE : t.contains("含税") ? Boolean.TRUE : null;
        Boolean freight = t.contains("不含运") ? Boolean.FALSE : t.contains("含税运") || t.contains("含运") ? Boolean.TRUE : null;
        if (tax == null && freight == null) {
            d.remark = text.strip();
            return;
        }
        d.taxIncluded = tax;
        d.freightIncluded = freight;
    }

    /** 表里的工厂：整格、或者拆开的几个名字（空格、顿号分开）里只对上一家就用它；对不上用物料的默认供应商（返回 null） */
    private static Long supplier(String text, Map<String, List<SupplierRef>> suppliers, Draft d) {
        List<String> names = supplierNames(text);
        if (names.isEmpty()) {
            return null;
        }
        List<SupplierRef> hits = names.stream().flatMap(n -> suppliers.getOrDefault(key(n), List.of()).stream())
                .collect(Collectors.toMap(SupplierRef::id, Function.identity(), (a, b) -> a, LinkedHashMap::new)).values().stream().toList();
        if (hits.size() == 1) {
            return hits.getFirst().id();
        }
        d.notes.add("表里的工厂写的是「" + text.strip() + "」，"
                + (hits.isEmpty() ? "供应商里没有" : "对上了 " + hits.size() + " 家") + "，先用了物料的默认供应商");
        return null;
    }

    private static List<String> supplierNames(String text) {
        String whole = text.strip();
        if (NO_COLOR.contains(whole)) {
            return List.of();
        }
        List<String> parts = Arrays.stream(whole.split("[、/,，;；\\s]+")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        return parts.size() > 1 ? java.util.stream.Stream.concat(java.util.stream.Stream.of(whole), parts.stream()).toList() : List.of(whole);
    }

    /** 部位：A 料大身、B 料里布；别的按物料名称（先看名称是不是以部位字结尾，再看带不带，长的优先） */
    private static String part(Line line, String materialName, List<DictOption> parts) {
        String section = line.section().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String byLabel = section.startsWith("A料") ? "大身" : section.startsWith("B料") ? "里布" : null;
        if (byLabel != null) {
            return parts.stream().filter(p -> p.label().equals(byLabel)).map(DictOption::value).findFirst().orElse(null);
        }
        for (String name : List.of(line.name(), materialName)) {
            String n = name.replaceAll("\\s+", "");
            DictOption hit = parts.stream().filter(p -> n.endsWith(p.label()))
                    .max((a, b) -> a.label().length() - b.label().length())
                    .orElse(parts.stream().filter(p -> n.contains(p.label()))
                            .max((a, b) -> a.label().length() - b.label().length()).orElse(null));
            if (hit != null) {
                return hit.value();
            }
        }
        return null;
    }

    /** 认出来的一行 → 和资料包详情一样的物料行（没有 id）。表里写了的盖过默认值 */
    private static BomItemVO item(Draft d, BomDefaultVO def, boolean cost) {
        BomItemVO vo = new BomItemVO();
        MaterialRef m = d.material;
        vo.setPart(d.part);
        vo.setMaterialId(m.id());
        vo.setMaterialCode(m.code());
        vo.setMaterialName(m.name());
        vo.setMaterialType(m.materialType());
        vo.setMaterialStatus(m.status());
        vo.setSkuMode(d.skuMode);
        vo.setUsageMode(BomUsageMode.UNIFORM);
        if (d.usage == null) {
            d.notes.add("单件用量「" + d.line.usageText() + "」不是数，要填");
        } else {
            vo.setUsageQty(d.usage.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros());
        }
        vo.setSizeUsages(Map.of());
        vo.setUnit(def.getUnit());
        vo.setLossPct(d.loss != null ? d.loss : def.getLossPct());
        vo.setSource(def.getSource());
        vo.setSupplierId(def.getSupplierId());
        vo.setSupplierName(def.getSupplierName());
        vo.setRemark(d.remark);
        vo.setSkus(d.skus);
        if (cost) {
            BigDecimal archive = def.getRefPrice();
            BigDecimal price = d.price != null ? Num.price(d.price) : archive;
            vo.setRefPrice(price);
            vo.setPriceSourceId(d.price != null && archive != null && archive.compareTo(price) != 0 ? null : def.getPriceSourceId());
            vo.setPriceTaxIncluded(d.taxIncluded != null ? d.taxIncluded : def.isPriceTaxIncluded());
            vo.setPriceFreightIncluded(d.freightIncluded != null ? d.freightIncluded : def.isPriceFreightIncluded());
            if (d.price != null && archive != null && archive.compareTo(price) != 0 && BomPricing.FROM_ARCHIVE.equals(def.getPriceFrom())) {
                d.notes.add("表里的单价 " + price.stripTrailingZeros().toPlainString() + "，价格档案是 "
                        + archive.stripTrailingZeros().toPlainString() + "：导进去的是表里的");
            }
            if (d.price == null && !d.line.priceText().isEmpty()) {
                d.notes.add("单价「" + d.line.priceText() + "」不是数，用了默认单价");
            }
            // 表里没写单价时，和页面上加一行一样：哪个 SKU 单独有价（档案、SKU 价和行上的不一样）就配上；表里写了就都用表里的
            if (d.price == null && def.getSkuPrices() != null) {
                Map<Long, BigDecimal> prices = new HashMap<>();
                def.getSkuPrices().forEach(sp -> prices.put(sp.materialSkuId(), sp.refPrice()));
                d.skus.forEach(sku -> sku.setRefPrice(prices.get(sku.getMaterialSkuId())));
            }
        } else {
            vo.setPriceTaxIncluded(true);
            vo.setPriceFreightIncluded(true);
        }
        return vo;
    }

    private static BomSkuVO skuVO(MaterialSkuRef sku, ColorOption color) {
        BomSkuVO vo = new BomSkuVO();
        if (color != null) {
            vo.setColorId(color.id());
            vo.setColorCode(color.code());
            vo.setColorName(color.name());
            vo.setColorHex(color.hex());
        }
        vo.setMaterialSkuId(sku.id());
        vo.setSkuCode(sku.code());
        vo.setSkuColorName(sku.colorName());
        vo.setSkuSpec(sku.spec());
        vo.setSkuStatus(sku.status());
        return vo;
    }

    private static BomImportVO.Source source(Line l, boolean cost) {
        return new BomImportVO.Source(l.section(), l.name(), l.spec(), l.color(), cost ? l.priceText() : null, l.unit(),
                l.usageText(), l.lossText(), l.supplier(), l.remark());
    }

    private static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    /** 去掉空白、不分大小写相同；有一个是空的不算相同 */
    private static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return !x.isEmpty() && x.equals(b.replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
    }

    /** 颜色名：去掉末尾的"色"再比（黑色 = 黑） */
    private static boolean sameColor(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return same(a.replaceAll("色$", ""), b.replaceAll("色$", ""));
    }
}
