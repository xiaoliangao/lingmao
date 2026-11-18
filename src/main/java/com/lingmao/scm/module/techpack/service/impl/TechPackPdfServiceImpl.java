package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.pdf.PdfRenderer;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.techpack.dto.CostRowVO;
import com.lingmao.scm.module.techpack.dto.CostSummary;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import com.lingmao.scm.module.techpack.entity.CraftText;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import com.lingmao.scm.module.techpack.service.TechPackPdfService;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.module.techpack.service.impl.PackContent.Content;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TechPackPdfServiceImpl implements TechPackPdfService {

    static final String LOG_EXPORT = "export-pdf";
    static final String SHEET_TEMPLATE = "techpack-sheet";
    static final String APPENDIX_TEMPLATE = "techpack-appendix";

    /** 能放进 PDF 的图片（Java 自带的 ImageIO 认得的）；webp 认不得，在原位写一句 */
    private static final Map<String, String> IMAGE_TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "gif", "image/gif");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String NONE = "—";
    /** 车间缝制那块下面放工序工价（原型 TpPrintBody） */
    private static final String PROCESS_GROUP = CraftSection.SEWING.getGroup();

    private final TechPackMapper techPackMapper;
    private final PackOwners packOwners;
    private final PackContent packContent;
    private final CostCalculator costCalculator;
    private final CraftStandardService craftStandardService;
    private final MaterialService materialService;
    private final AttachmentService attachmentService;
    private final FileService fileService;
    private final DictDataService dictDataService;
    private final DocLogService docLogService;
    private final PdfRenderer pdfRenderer;
    private final Clock clock;

    @Override
    public PdfFile export(long id, Long colorId, boolean price, boolean appendix) {
        TechPack pack = techPackMapper.selectById(id);
        if (pack == null) {
            throw new BizException(404, "这一版资料包不存在，可能已经被删除了");
        }
        Ctx ctx = packOwners.context(pack);
        List<ColorOption> colors = ctx.colors();
        if (colorId != null) {
            colors = colors.stream().filter(c -> c.id().equals(colorId)).toList();
            if (colors.isEmpty()) {
                throw new BizException("这个颜色不在这一版本的展示范围内，请刷新后再选");
            }
        }
        PdfSheet sheet = sheet(pack, ctx, colors, price);
        List<byte[]> parts = new ArrayList<>();
        parts.add(pdfRenderer.render(SHEET_TEMPLATE, Map.of("sheet", sheet)));
        if (appendix) {
            parts.addAll(appendix());
        }
        byte[] pdf = PdfRenderer.merge(parts);
        docLogService.add(BizType.TECH_PACK, id, LOG_EXPORT, "颜色：" + (colorId == null ? "全部" : colors.getFirst().name())
                + "；" + (price ? "含价格" : "不含价格") + "；" + (appendix ? "附工艺标准" : "不附工艺标准"));
        return new PdfFile(fileName(ctx.owner(), pack), pdf);
    }

    /** 拼工艺单：选项（颜色、含不含价格）在这里套进去，模板不再判断 */
    PdfSheet sheet(TechPack pack, Ctx ctx, List<ColorOption> colors, boolean price) {
        PackOwner owner = ctx.owner();
        Content content = packContent.load(pack.getId());
        Map<Long, MaterialRef> materials = materials(content.bom());
        Map<Long, MaterialSkuRef> skus = skus(content.bom());
        Map<String, String> units = labels("unit");

        PdfSheet.Meta meta = new PdfSheet.Meta(orNone(owner.sampleNo()), owner.code(), owner.name(), orNone(owner.aliasName()),
                (pack.getStatus() == PackStatus.EFFECTIVE && pack.getPublishTime() != null
                        ? pack.getPublishTime().atZoneSameInstant(clock.getZone()).toLocalDate() : LocalDate.now(clock)).format(DATE),
                pack.getStage().getLabel() + " V" + pack.getVersion(),
                colors.stream().map(ColorOption::name).collect(Collectors.joining("、")),
                String.join(" / ", ctx.sizes()),
                orNone(owner.productName()),
                pack.getUtilPct() == null ? NONE : qty(pack.getUtilPct()) + "%",
                orNone(owner.composition()),
                StringUtils.hasText(pack.getVersionNote()) ? pack.getVersionNote() : null);

        Map<Long, TechPackCostVO> costs = new HashMap<>();
        if (price) {
            colors.forEach(c -> costs.put(c.id(), costCalculator.compute(pack, ctx, c.id(), false)));
        }
        List<PdfSheet.BomTable> boms = colors.stream().map(c -> new PdfSheet.BomTable(c.name(), c.code(),
                bomRows(content.bom(), c, ctx, materials, skus, units, price ? costs.get(c.id()) : null))).toList();

        return new PdfSheet(title(owner), statusMark(pack.getStatus()), meta, image(packOwners.mainImage(pack)),
                spec(content, ctx), fabricUsage(content.bom(), ctx, materials, units), ctx.sizes(), boms, price,
                price ? cost(pack, ctx, colors) : null, craftGroups(pack, content, price));
    }

    // ---------------------------------------------------------------- 各块

    private String title(PackOwner owner) {
        String season = owner.season() == null ? "" : labels("season").getOrDefault(owner.season(), owner.season()) + "季";
        return (owner.year() == null ? "" : owner.year().toString()) + season + "工艺单";
    }

    private static String statusMark(PackStatus status) {
        return switch (status) {
            case DRAFT -> "草稿";
            case VOIDED -> "已作废";
            case EFFECTIVE -> null;
        };
    }

    /** 没填尺寸指示就整块不出 */
    private static PdfSheet.Spec spec(Content content, Ctx ctx) {
        if (content.specs().isEmpty()) {
            return null;
        }
        List<PdfSheet.SpecRow> rows = content.specs().stream().map(r -> {
            Map<String, BigDecimal> values = new HashMap<>();
            r.values().forEach(v -> values.put(v.getSizeCode(), v.getValueCm()));
            return new PdfSheet.SpecRow(r.point().getPoint(), orEmpty(r.point().getMethod()),
                    ctx.sizes().stream().map(z -> values.get(z) == null ? "" : qty(values.get(z))).toList(),
                    r.point().getToleranceCm() == null ? "" : qty(r.point().getToleranceCm()), orEmpty(r.point().getRemark()));
        }).toList();
        return new PdfSheet.Spec(ctx.baseSize(), rows);
    }

    /** 面料用量不单独填：物料清单里面料、里料的行带出部位、物料、基码用量（PRD §6.4.2 规则 4） */
    private static String fabricUsage(List<BomRow> bom, Ctx ctx, Map<Long, MaterialRef> materials, Map<String, String> units) {
        List<String> parts = new ArrayList<>();
        for (BomRow row : bom) {
            MaterialRef m = materials.get(row.item().getMaterialId());
            BigDecimal usage = CostCalculator.usageOf(row, ctx.baseSize());
            if (m != null && CostCalculator.FABRIC_TYPES.contains(m.materialType()) && usage != null) {
                parts.add(row.item().getPart() + " " + m.name() + " " + qty(usage) + " " + unit(units, row.item().getUnit()) + "/件");
            }
        }
        return parts.isEmpty() ? null : String.join("；", parts) + "（" + ctx.baseSize() + " 码）";
    }

    private static List<PdfSheet.BomRow> bomRows(List<BomRow> bom, ColorOption color, Ctx ctx, Map<Long, MaterialRef> materials,
                                                 Map<Long, MaterialSkuRef> skus, Map<String, String> units, TechPackCostVO cost) {
        Map<Long, CostRowVO> costRows = new HashMap<>();
        if (cost != null) {
            cost.getFabricRows().forEach(r -> costRows.put(r.getBomItemId(), r));
            cost.getTrimRows().forEach(r -> costRows.put(r.getBomItemId(), r));
        }
        List<PdfSheet.BomRow> rows = new ArrayList<>();
        for (BomRow row : bom) {
            BomItem item = row.item();
            MaterialRef m = materials.get(item.getMaterialId());
            // 这个颜色用到的配料：按款色分的只看这个颜色的，按尺码分的按尺码顺序排
            List<MaterialSkuRef> used = row.skus().stream()
                    .filter(s -> !item.getSkuMode().isByColor() || Objects.equals(s.getColorId(), color.id()))
                    .sorted((a, b) -> Integer.compare(sizeIndex(ctx, a), sizeIndex(ctx, b)))
                    .map(s -> skus.get(s.getMaterialSkuId())).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
            String materialName = m == null ? "" : m.name();
            String unit = unit(units, item.getUnit());
            CostRowVO c = costRows.get(item.getId());
            rows.add(new PdfSheet.BomRow(item.getSeq(), item.getPart(),
                    used.isEmpty() ? "未配料" : used.stream().map(MaterialSkuRef::code).collect(Collectors.joining("、")),
                    used.size() == 1 ? skuName(materialName, used.getFirst()) : materialName,
                    unit,
                    ctx.sizes().stream().map(z -> {
                        BigDecimal u = CostCalculator.usageOf(row, z);
                        return u == null ? "" : qty(u);
                    }).toList(),
                    qty(item.getLossPct() == null ? BigDecimal.ZERO : item.getLossPct()) + "%",
                    item.getSource().getLabel(),
                    orEmpty(item.getRemark()),
                    c == null || c.getPrice() == null ? (cost == null ? null : "") : price(c.getPrice()) + "/" + unit,
                    c == null ? null : c.isCounted() ? money(c.getCost())
                            : item.getSource() == MaterialSource.FACTORY ? "含在加工价里" : "客户出"));
        }
        return rows;
    }

    /** 核价按导出的第一个颜色算（没有颜色按款式第一个） */
    private PdfSheet.Cost cost(TechPack pack, Ctx ctx, List<ColorOption> colors) {
        ColorOption color = colors.isEmpty() ? null : colors.getFirst();
        TechPackCostVO vo = costCalculator.compute(pack, ctx, color == null ? ctx.firstColorId() : color.id(), false);
        CostSummary s = vo.getSummary();
        List<CostItem> items = packContent.loadCostItems(pack.getId());
        String other = items.isEmpty() ? null : items.stream()
                .map(i -> i.getName() + " " + money(i.getAmount()) + (StringUtils.hasText(i.getRemark()) ? "（" + i.getRemark() + "）" : ""))
                .collect(Collectors.joining("；"));
        return new PdfSheet.Cost("按 " + (color == null ? "" : color.name() + "、") + ctx.baseSize() + " 码用量",
                money(s.fabric()), money(s.trim()), money(s.labor()), money(s.other()), money(s.total()), other);
    }

    /** 九段按三块分；没字、没图（包装要求还要没选包装方式）的段不出，一段都没有的那块不出。工序工价只在含价格时放车间缝制下面 */
    private List<PdfSheet.CraftGroup> craftGroups(TechPack pack, Content content, boolean price) {
        Map<CraftSection, String> texts = new HashMap<>();
        for (CraftText t : content.texts()) {
            texts.put(t.getSection(), t.getContent());
        }
        String packingMode = pack.getPackingMode() == null ? null
                : labels("packing_mode").getOrDefault(pack.getPackingMode(), pack.getPackingMode());
        Map<String, List<PdfSheet.CraftItem>> groups = new LinkedHashMap<>();
        for (CraftSection section : CraftSection.values()) {
            String text = StringUtils.hasText(texts.get(section)) ? texts.get(section) : null;
            String mode = section == CraftSection.PACKING ? packingMode : null;
            List<String> images = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (AttachmentVO a : attachmentService.list(BizType.TECH_PACK, pack.getId(), section.name())) {
                String data = dataUri(a.url());
                if (data != null) {
                    images.add(data);
                } else {
                    skipped.add("（图片「" + a.name() + "」放不进 PDF，在系统里看；要放进来请换成 jpg、png）");
                }
            }
            groups.computeIfAbsent(section.getGroup(), g -> new ArrayList<>());
            if (text != null || mode != null || !images.isEmpty() || !skipped.isEmpty()) {
                groups.get(section.getGroup()).add(new PdfSheet.CraftItem(section.getLabel(), text, mode, images, skipped));
            }
        }
        List<PackContent.ProcessRow> processes = price ? content.processes() : List.of();
        Map<String, String> categories = processes.isEmpty() ? Map.of() : labels("process_category");
        List<PdfSheet.CraftGroup> result = new ArrayList<>();
        groups.forEach((name, items) -> {
            boolean withProcesses = name.equals(PROCESS_GROUP) && !processes.isEmpty();
            if (items.isEmpty() && !withProcesses) {
                return;
            }
            List<PdfSheet.ProcessRow> rows = new ArrayList<>();
            if (withProcesses) {
                for (PackContent.ProcessRow p : processes) {
                    var pr = p.process();
                    rows.add(new PdfSheet.ProcessRow(pr.getSeq(),
                            pr.getCategory() == null ? "" : categories.getOrDefault(pr.getCategory(), pr.getCategory()),
                            pr.getName(), orEmpty(pr.getRequirement()), pr.getStdSeconds() == null ? "" : pr.getStdSeconds().toString(),
                            pr.getPrice() == null ? "" : money(pr.getPrice()), orEmpty(pr.getRemark())));
                }
            }
            int seconds = processes.stream().map(p -> p.process().getStdSeconds()).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
            result.add(new PdfSheet.CraftGroup(name, items, rows, String.valueOf(seconds),
                    money(CostCalculator.laborTotal(processes.stream().map(PackContent.ProcessRow::process).toList()))));
        });
        return result;
    }

    /** 工艺标准文件按顺序：PDF 直接拼，图片一张一页；文件不在了、打不开的跳过（记 warn），不让整份导出失败 */
    private List<byte[]> appendix() {
        List<byte[]> parts = new ArrayList<>();
        int n = 0;
        for (AttachmentVO file : craftStandardService.files()) {
            Optional<Path> path = fileService.localPath(file.url());
            String ext = extension(file.url());
            if (path.isEmpty()) {
                log.warn("工艺标准文件不在了，导出时跳过：{}", file.name());
                continue;
            }
            try {
                if ("pdf".equals(ext)) {
                    byte[] pdf = Files.readAllBytes(path.get());
                    if (PdfRenderer.readable(pdf)) {
                        parts.add(pdf);
                    } else {
                        log.warn("工艺标准文件打不开，导出时跳过：{}", file.name());
                    }
                } else if (IMAGE_TYPES.containsKey(ext)) {
                    String title = "附页 " + (++n) + " · " + file.name().replaceAll("\\.[^.]+$", "");
                    parts.add(pdfRenderer.render(APPENDIX_TEMPLATE, Map.of("title", title, "image", dataUri(path.get(), ext))));
                }
            } catch (IOException e) {
                log.warn("读工艺标准文件失败，导出时跳过：{}，原因：{}", file.name(), e.toString());
            }
        }
        return parts;
    }

    // ---------------------------------------------------------------- 小工具

    private String image(AttachmentVO image) {
        return image == null ? null : dataUri(image.url());
    }

    /** 上传的图片读成 data: 地址（openhtmltopdf 取不到带签名、要登录的地址）；不是能放进 PDF 的图、文件不在了是 null */
    private String dataUri(String url) {
        String ext = extension(url);
        if (!IMAGE_TYPES.containsKey(ext)) {
            return null;
        }
        Optional<Path> path = fileService.localPath(url);
        if (path.isEmpty()) {
            return null;
        }
        try {
            return dataUri(path.get(), ext);
        } catch (IOException e) {
            log.warn("读图片失败，PDF 里不放：{}，原因：{}", path.get(), e.toString());
            return null;
        }
    }

    private static String dataUri(Path path, String ext) throws IOException {
        return "data:" + IMAGE_TYPES.get(ext) + ";base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }

    private static String extension(String url) {
        String path = url == null ? "" : url.replaceAll("[?#].*$", "");
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** "27CF001-女式风衣-工艺单-V2.pdf"；研发版"研发V1"；文件名里不能有的字符换成下划线 */
    static String fileName(PackOwner owner, TechPack pack) {
        String name = owner.code() + "-" + owner.name() + "-工艺单-" + pack.getStage().versionName(pack.getVersion()).replace(" ", "");
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_") + ".pdf";
    }

    private static String skuName(String materialName, MaterialSkuRef sku) {
        return materialName + (sku.colorName() == null ? "" : "-" + sku.colorName()) + (sku.spec() == null ? "" : " " + sku.spec());
    }

    private static int sizeIndex(Ctx ctx, BomItemSku sku) {
        int i = sku.getSizeCode() == null ? -1 : ctx.sizes().indexOf(sku.getSizeCode());
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private Map<String, String> labels(String dictType) {
        return dictDataService.listByType(dictType).stream()
                .collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }

    private static String unit(Map<String, String> units, String code) {
        return code == null ? "" : units.getOrDefault(code, code);
    }

    private Map<Long, MaterialRef> materials(List<BomRow> bom) {
        Set<Long> ids = bom.stream().map(r -> r.item().getMaterialId()).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : CostCalculator.byId(materialService.materialRefs(ids), MaterialRef::id);
    }

    private Map<Long, MaterialSkuRef> skus(List<BomRow> bom) {
        Set<Long> ids = bom.stream().flatMap(r -> r.skus().stream()).map(BomItemSku::getMaterialSkuId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : CostCalculator.byId(materialService.skuRefs(ids), MaterialSkuRef::id);
    }

    /** 数量、尺寸：去掉末尾的 0（2.350 → 2.35） */
    static String qty(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    static String money(BigDecimal v) {
        return v == null ? "" : Num.money(v).toPlainString();
    }

    /** 单价：至少两位小数，多的照写（18.5 → 18.50，0.0875 照旧） */
    static String price(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 2 ? v.setScale(2, RoundingMode.HALF_UP).toPlainString() : s.toPlainString();
    }

    private static String orNone(String s) {
        return StringUtils.hasText(s) ? s : NONE;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
