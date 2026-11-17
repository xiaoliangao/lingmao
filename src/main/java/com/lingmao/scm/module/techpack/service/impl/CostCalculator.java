package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.techpack.dto.CostItemVO;
import com.lingmao.scm.module.techpack.dto.CostRowVO;
import com.lingmao.scm.module.techpack.dto.CostSummary;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.entity.CostMode;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 核价怎么算（PRD §6.4.5、架构 §4.9），只写在这里：核价页签、资料包详情的单件成本、概况的"资料包核价"、按款看成本都调它，
 * 所以几处的数一样（PRD §6.4 验收 10）。
 * <ul>
 *   <li>一行物料：基码用量（统一用量的行就是统一用量）×（1 + 损耗率%）× 单价，四舍五入到分；单价 = 这个颜色、基码配到的 SKU 上单独的价 &gt; 行上的价</li>
 *   <li>只有我方采购的行计入；面料块 = 物料类型是面料、里料的行，辅料块 = 其余的行</li>
 *   <li>加工费：按外发 = 工序工价合计；按成品采购 = 成品单价，没填用价格档案里默认加工厂"包工包料"的现价，都没有是 0</li>
 *   <li>合计 = 四块相加；倍率 = 吊牌价 ÷ 合计</li>
 * </ul>
 * 行的单件成本先取到分再加总：页面上看到的行加起来正好等于块的合计（和单据行金额的取法一样）
 */
@Component
@RequiredArgsConstructor
class CostCalculator {

    /** 进核价"面料"块的物料类型（字典 material_type） */
    static final Set<String> FABRIC_TYPES = Set.of("FABRIC", "LINING");
    /** 核价按成品采购算时，取价格档案里的哪种加工方式：包工包料（字典 process_mode） */
    static final String BUY_PROCESS_MODE = "FULL";

    private final TechPackMapper techPackMapper;
    private final PackContent packContent;
    private final MaterialService materialService;
    private final PriceService priceService;
    private final SupplierService supplierService;
    private final Clock clock;

    /** 一个版本的核价。withDev：生产版有研发来源时，再算一遍研发版当"研发估" */
    TechPackCostVO compute(TechPack pack, Ctx ctx, Long colorId, boolean withDev) {
        List<BomRow> bom = packContent.loadBom(pack.getId());
        List<PackContent.ProcessRow> processes = packContent.loadProcesses(pack.getId());
        List<CostItem> items = packContent.loadCostItems(pack.getId());
        Map<Long, MaterialRef> materials = materials(bom);
        Map<Long, MaterialSkuRef> skus = skus(bom);

        TechPackCostVO vo = new TechPackCostVO();
        vo.setTechPackId(pack.getId());
        vo.setBaseSize(ctx.baseSize());
        vo.setColorId(colorId);
        vo.setCostMode(pack.getCostMode());
        vo.setBuyPrice(pack.getBuyPrice());
        vo.setDefaultFactoryId(ctx.owner().defaultFactoryId());
        vo.setDefaultFactoryName(factoryName(ctx.owner().defaultFactoryId()));
        vo.setBuyPriceDefault(buyPriceDefault(pack, ctx));
        vo.setTagPrice(ctx.owner().tagPrice());

        List<CostRowVO> fabric = new ArrayList<>();
        List<CostRowVO> trim = new ArrayList<>();
        Set<String> bases = new HashSet<>();
        for (BomRow row : bom) {
            CostRowVO r = row(row, materials.get(row.item().getMaterialId()), skus, colorId, ctx.baseSize());
            MaterialRef m = materials.get(row.item().getMaterialId());
            (m != null && FABRIC_TYPES.contains(m.materialType()) ? fabric : trim).add(r);
            if (r.isCounted() && r.getPrice() != null) {
                bases.add(r.getPriceTaxIncluded() + "/" + r.getPriceFreightIncluded());
            }
        }
        vo.setFabricRows(fabric);
        vo.setTrimRows(trim);
        vo.setMixedBasis(bases.size() > 1);

        BigDecimal laborTotal = laborTotal(processes.stream().map(PackContent.ProcessRow::process).toList());
        vo.setLaborTotal(laborTotal);
        vo.setProcessCount(processes.size());
        vo.setOtherItems(items.stream().map(CostCalculator::itemVO).toList());

        BigDecimal labor = pack.getCostMode() == CostMode.BUY
                ? Num.money(firstNonNull(pack.getBuyPrice(), vo.getBuyPriceDefault(), BigDecimal.ZERO))
                : laborTotal;
        CostSummary summary = summary(sum(fabric), sum(trim), labor,
                Num.money(items.stream().map(CostItem::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)));
        vo.setSummary(summary);
        vo.setRatio(Num.ratio(ctx.owner().tagPrice(), summary.total()));

        if (withDev) {
            fillDev(vo, pack, ctx, colorId);
        }
        return vo;
    }

    /** 单件成本合计（按款式第一个颜色）：资料包详情、概况的"资料包核价"、按款看成本 */
    BigDecimal unitCost(TechPack pack, Ctx ctx) {
        return compute(pack, ctx, ctx.firstColorId(), false).getSummary().total();
    }

    /**
     * 物料清单页签上一行的单件成本（docs/api/01 §4.4）：随款色的行取第一个款色的 SKU 单价，随尺码的行取基码那个 SKU 的。
     * 工厂自备、客供的行，没单价、没用量的行是 null。不受看成本权限管（架构 §4.9）
     */
    static BigDecimal rowUnitCost(BomRow row, Long firstColorId, String baseSize) {
        BomItem item = row.item();
        if (item.getSource() != MaterialSource.PURCHASE) {
            return null;
        }
        BigDecimal price = priceOf(row, pick(row, firstColorId, baseSize));
        BigDecimal usage = usageOf(row, baseSize);
        return price == null || usage == null ? null : Num.money(Num.addPct(usage, item.getLossPct()).multiply(price));
    }

    /** 这一行在这个颜色、基码下用哪条配料：按款色分的比款色，按尺码分的比基码；固定的就是那一条；没配是 null */
    static BomItemSku pick(BomRow row, Long colorId, String baseSize) {
        BomSkuMode mode = row.item().getSkuMode();
        return row.skus().stream()
                .filter(s -> !mode.isByColor() || Objects.equals(s.getColorId(), colorId))
                .filter(s -> !mode.isBySize() || Objects.equals(s.getSizeCode(), baseSize))
                .findFirst().orElse(null);
    }

    /** 基码用量：统一的行取统一用量，分码的行取基码那一码；没填是 null */
    static BigDecimal usageOf(BomRow row, String baseSize) {
        if (row.item().getUsageMode() == BomUsageMode.BY_SIZE) {
            return row.sizes().stream().filter(s -> Objects.equals(s.getSizeCode(), baseSize))
                    .map(BomItemSize::getUsageQty).findFirst().orElse(null);
        }
        return row.item().getUsageQty();
    }

    /** 单价：配料上单独的价 > 行上的价 */
    static BigDecimal priceOf(BomRow row, BomItemSku sku) {
        return sku != null && sku.getRefPrice() != null ? sku.getRefPrice() : row.item().getRefPrice();
    }

    static BigDecimal laborTotal(List<PackProcess> processes) {
        return Num.money(processes.stream().map(PackProcess::getPrice).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CostRowVO row(BomRow row, MaterialRef material, Map<Long, MaterialSkuRef> skus, Long colorId, String baseSize) {
        BomItem item = row.item();
        BomItemSku picked = pick(row, colorId, baseSize);
        MaterialSkuRef sku = picked == null ? null : skus.get(picked.getMaterialSkuId());
        BigDecimal usage = usageOf(row, baseSize);
        BigDecimal price = priceOf(row, picked);

        CostRowVO r = new CostRowVO();
        r.setBomItemId(item.getId());
        r.setSeq(item.getSeq());
        r.setPart(item.getPart());
        r.setMaterialId(item.getMaterialId());
        r.setMaterialName(material == null ? null : material.name());
        if (sku != null) {
            r.setMaterialSkuId(sku.id());
            r.setSkuCode(sku.code());
            r.setSkuColorName(sku.colorName());
            r.setSkuSpec(sku.spec());
        }
        r.setSource(item.getSource());
        r.setUsageQty(usage == null ? BigDecimal.ZERO : usage);
        r.setUnit(item.getUnit());
        r.setLossPct(item.getLossPct());
        r.setPrice(price);
        r.setPriceTaxIncluded(item.getPriceTaxIncluded());
        r.setPriceFreightIncluded(item.getPriceFreightIncluded());
        r.setCounted(item.getSource() == MaterialSource.PURCHASE);
        r.setCost(r.isCounted() && usage != null && price != null
                ? Num.money(Num.addPct(usage, item.getLossPct()).multiply(price))
                : Num.money(BigDecimal.ZERO));
        return r;
    }

    /** 研发估：同一个方法算研发版；行按"部位 + 物料"对上第一行，其他费用按名称对上 */
    private void fillDev(TechPackCostVO vo, TechPack pack, Ctx ctx, Long colorId) {
        if (pack.getStage() != PackStage.PROD || pack.getSourcePackId() == null) {
            return;
        }
        TechPack source = techPackMapper.selectById(pack.getSourcePackId());
        if (source == null || source.getStage() != PackStage.DEV) {
            return;
        }
        TechPackCostVO dev = compute(source, ctx, colorId, false);
        vo.setDev(new TechPackCostVO.DevCost(source.getId(), source.getVersion(), dev.getSummary()));
        Map<String, BigDecimal> devRows = new HashMap<>();
        for (CostRowVO r : concat(dev.getFabricRows(), dev.getTrimRows())) {
            devRows.putIfAbsent(r.getPart() + "|" + r.getMaterialId(), r.getCost());
        }
        concat(vo.getFabricRows(), vo.getTrimRows()).forEach(r -> r.setDevCost(devRows.get(r.getPart() + "|" + r.getMaterialId())));
        Map<String, BigDecimal> devItems = new HashMap<>();
        dev.getOtherItems().forEach(i -> devItems.putIfAbsent(i.getName(), i.getAmount()));
        vo.getOtherItems().forEach(i -> i.setDevAmount(devItems.get(i.getName())));
    }

    /** 默认加工厂做这个款"包工包料"的现价；款式没设默认加工厂、档案里没有是 null */
    private BigDecimal buyPriceDefault(TechPack pack, Ctx ctx) {
        Long factoryId = ctx.owner().defaultFactoryId();
        if (factoryId == null || pack.getStyleId() == null) {
            return null;
        }
        return priceService.currentProcess(factoryId, pack.getStyleId(), BUY_PROCESS_MODE, LocalDate.now(clock))
                .map(PriceRef::price).orElse(null);
    }

    private String factoryName(Long factoryId) {
        if (factoryId == null) {
            return null;
        }
        SupplierRef ref = supplierService.refs(List.of(factoryId)).get(factoryId);
        return ref == null ? null : ref.shortName();
    }

    private Map<Long, MaterialRef> materials(List<BomRow> bom) {
        Set<Long> ids = bom.stream().map(r -> r.item().getMaterialId()).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : byId(materialService.materialRefs(ids), MaterialRef::id);
    }

    private Map<Long, MaterialSkuRef> skus(List<BomRow> bom) {
        Set<Long> ids = bom.stream().flatMap(r -> r.skus().stream()).map(BomItemSku::getMaterialSkuId).collect(Collectors.toSet());
        return ids.isEmpty() ? Map.of() : byId(materialService.skuRefs(ids), MaterialSkuRef::id);
    }

    private static CostItemVO itemVO(CostItem item) {
        CostItemVO vo = new CostItemVO();
        vo.setId(item.getId());
        vo.setSeq(item.getSeq());
        vo.setName(item.getName());
        vo.setAmount(item.getAmount());
        vo.setRemark(item.getRemark());
        return vo;
    }

    private static CostSummary summary(BigDecimal fabric, BigDecimal trim, BigDecimal labor, BigDecimal other) {
        return new CostSummary(fabric, trim, labor, other, Num.money(fabric.add(trim).add(labor).add(other)));
    }

    private static BigDecimal sum(List<CostRowVO> rows) {
        return Num.money(rows.stream().map(CostRowVO::getCost).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static List<CostRowVO> concat(List<CostRowVO> a, List<CostRowVO> b) {
        List<CostRowVO> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static <T> Map<Long, T> byId(Collection<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}
