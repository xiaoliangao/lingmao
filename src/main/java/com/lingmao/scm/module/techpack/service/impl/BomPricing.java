package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BOM 行的默认单价（PRD §6.4.3、docs/api/01 §3.9）。取价规则只写在这里，前端不直接调价格档案：
 * 价格档案里这家供应商这个 SKU 的现价 &gt; 这家供应商这个物料的现价 &gt; 物料 SKU 单价 &gt; 物料参考单价。
 * 价格档案、物料单价都按采购单位，这里除以换算系数换成元 / 库存单位（4 位小数）。
 * 行上的价按这一行配到的第一个 SKU 取（还没配料就按物料取）；其他 SKU 取到的价和行上不一样的，单独记在配料上
 */
@Component
@RequiredArgsConstructor
class BomPricing {

    static final String FROM_ARCHIVE = "ARCHIVE";
    static final String FROM_SKU = "SKU";
    static final String FROM_MATERIAL = "MATERIAL";
    static final String FROM_NONE = "NONE";

    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final PriceService priceService;
    private final Clock clock;

    /** 一行取到的价：行上的（元 / 库存单位）、从哪来、和行上不一样的 SKU 的价 */
    record Priced(BigDecimal refPrice, String from, PriceRef archive, Map<Long, BigDecimal> skuPrices) {

        boolean taxIncluded() {
            return archive == null || archive.taxIncluded();
        }

        boolean freightIncluded() {
            return archive == null || archive.freightIncluded();
        }
    }

    /**
     * 加行、换物料、换供应商、换配料时的默认值，按请求的顺序。供应商没给用物料的默认供应商。
     * 物料停用了 → 1000；SKU 不是这个物料的 → 1000
     */
    List<BomDefaultVO> defaults(List<BomDefaultReq> reqs) {
        Map<Long, MaterialRef> materials = CostCalculator.byId(
                materialService.materialRefs(reqs.stream().map(BomDefaultReq::getMaterialId).collect(Collectors.toSet())),
                MaterialRef::id);
        Map<Long, MaterialSkuRef> skus = skuRefs(reqs.stream().flatMap(r -> ids(r.getSkuIds()).stream()).toList());
        List<Line> lines = new ArrayList<>();
        for (BomDefaultReq req : reqs) {
            MaterialRef m = TechPackServiceImpl.at(materials, req.getMaterialId());
            if (m == null) {
                throw new BizException("物料不存在了，可能已经被删除，请重选");
            }
            if (!Integer.valueOf(1).equals(m.status())) {
                throw new BizException("物料 " + m.code() + " " + m.name() + " 已经停用了，不能再加进物料清单");
            }
            List<MaterialSkuRef> rowSkus = ids(req.getSkuIds()).stream().map(skus::get).toList();
            rowSkus.forEach(s -> checkBelongs(s, m));
            lines.add(new Line(m, req.getSupplierId() != null ? req.getSupplierId() : m.defaultSupplierId(), rowSkus));
        }
        Map<MaterialPriceKey, PriceRef> archive = archive(lines);
        Set<Long> supplierIds = lines.stream().map(Line::supplierId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SupplierRef> suppliers = supplierIds.isEmpty() ? Map.of() : supplierService.refs(supplierIds);

        List<BomDefaultVO> result = new ArrayList<>();
        for (Line line : lines) {
            MaterialRef m = line.material();
            Priced priced = price(line, archive);
            BomDefaultVO vo = new BomDefaultVO();
            vo.setMaterialId(m.id());
            vo.setUnit(m.stockUnit());
            vo.setPurchaseUnit(m.purchaseUnit());
            vo.setConversionFactor(m.conversionFactor());
            vo.setSupplierId(line.supplierId());
            SupplierRef supplier = line.supplierId() == null ? null : suppliers.get(line.supplierId());
            vo.setSupplierName(supplier == null ? null : supplier.shortName());
            vo.setLossPct(m.defaultLossPct() == null ? BigDecimal.ZERO : m.defaultLossPct());
            vo.setSource(m.defaultSource() == null ? MaterialSource.PURCHASE : MaterialSource.valueOf(m.defaultSource()));
            vo.setRefPrice(priced.refPrice());
            vo.setPriceSourceId(priced.archive() == null ? null : priced.archive().id());
            vo.setPriceTaxIncluded(priced.taxIncluded());
            vo.setPriceFreightIncluded(priced.freightIncluded());
            vo.setPriceFrom(priced.from());
            vo.setSkuPrices(priced.skuPrices().entrySet().stream()
                    .map(e -> new BomDefaultVO.SkuPrice(e.getKey(), e.getValue())).toList());
            result.add(vo);
        }
        return result;
    }

    /** 一行：物料、取价用的供应商（可能没有）、配到的 SKU（去重、按顺序） */
    private record Line(MaterialRef material, Long supplierId, List<MaterialSkuRef> skus) {
    }

    /** 一个价：按采购单位的原价、从哪来、价格档案的那条 */
    private record Hit(BigDecimal perPurchaseUnit, String from, PriceRef archive) {
    }

    private Priced price(Line line, Map<MaterialPriceKey, PriceRef> archive) {
        MaterialRef m = line.material();
        MaterialSkuRef first = line.skus().isEmpty() ? null : line.skus().getFirst();
        Hit rowHit = hit(m, line.supplierId(), first, archive);
        BigDecimal rowPrice = perStockUnit(rowHit.perPurchaseUnit(), m.conversionFactor());
        Map<Long, BigDecimal> skuPrices = new LinkedHashMap<>();
        for (MaterialSkuRef sku : line.skus()) {
            BigDecimal p = perStockUnit(hit(m, line.supplierId(), sku, archive).perPurchaseUnit(), m.conversionFactor());
            if (p != null && (rowPrice == null || p.compareTo(rowPrice) != 0)) {
                skuPrices.put(sku.id(), p);
            }
        }
        return new Priced(rowPrice, rowHit.from(), rowHit.archive(), skuPrices);
    }

    /** 一个 SKU（null = 只看物料）按规则取到的价 */
    private static Hit hit(MaterialRef m, Long supplierId, MaterialSkuRef sku, Map<MaterialPriceKey, PriceRef> archive) {
        if (supplierId != null) {
            PriceRef ref = sku == null ? null : archive.get(new MaterialPriceKey(supplierId, m.id(), sku.id()));
            if (ref == null) {
                ref = archive.get(new MaterialPriceKey(supplierId, m.id(), null));
            }
            if (ref != null) {
                return new Hit(ref.price(), FROM_ARCHIVE, ref);
            }
        }
        if (sku != null && sku.skuPrice() != null) {
            return new Hit(sku.skuPrice(), FROM_SKU, null);
        }
        if (m.refPrice() != null) {
            return new Hit(m.refPrice(), FROM_MATERIAL, null);
        }
        return new Hit(null, FROM_NONE, null);
    }

    /** 查价格档案；一次查全部行（PriceService.currentMaterials 一条 SQL） */
    private Map<MaterialPriceKey, PriceRef> archive(List<Line> lines) {
        Set<MaterialPriceKey> keys = new HashSet<>();
        for (Line line : lines) {
            if (line.supplierId() == null) {
                continue;
            }
            keys.add(new MaterialPriceKey(line.supplierId(), line.material().id(), null));
            line.skus().forEach(s -> keys.add(new MaterialPriceKey(line.supplierId(), line.material().id(), s.id())));
        }
        return keys.isEmpty() ? Map.of() : priceService.currentMaterials(keys, LocalDate.now(clock));
    }

    /** 按采购单位的价 ÷ 换算系数（1 采购单位 = n 库存单位），4 位小数 */
    static BigDecimal perStockUnit(BigDecimal perPurchaseUnit, BigDecimal factor) {
        if (perPurchaseUnit == null) {
            return null;
        }
        if (factor == null || factor.signum() <= 0 || factor.compareTo(BigDecimal.ONE) == 0) {
            return Num.price(perPurchaseUnit);
        }
        return perPurchaseUnit.divide(factor, Num.PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private Map<Long, MaterialSkuRef> skuRefs(Collection<Long> ids) {
        Set<Long> distinct = new HashSet<>(ids);
        return distinct.isEmpty() ? Map.of() : CostCalculator.byId(materialService.skuRefs(distinct), MaterialSkuRef::id);
    }

    private static List<Long> ids(List<Long> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    static void checkBelongs(MaterialSkuRef sku, MaterialRef material) {
        if (!Objects.equals(sku.material().id(), material.id())) {
            throw new BizException("物料 SKU " + sku.code() + " 不是物料 " + material.code() + " 的，请刷新后重选");
        }
    }
}
