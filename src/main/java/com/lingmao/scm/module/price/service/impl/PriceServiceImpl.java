package com.lingmao.scm.module.price.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.CurrentPriceVO;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.entity.Price;
import com.lingmao.scm.module.price.entity.PriceType;
import com.lingmao.scm.module.price.mapper.CurrentMaterialHit;
import com.lingmao.scm.module.price.mapper.MaterialUnit;
import com.lingmao.scm.module.price.mapper.MaterialUnitKey;
import com.lingmao.scm.module.price.mapper.PriceMapper;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 价格档案（RFC 006）。规则照 PRD §6.10 规则 1、2、5 和原型 5e-price.js 的 recordPrice、voidPrice。
 * <p>
 * 跨域只调 Service：供应商能不能用、名称（SupplierService），物料和 SKU 的状态、采购单位、名称（MaterialService）。
 * 款式的两处还没接，标了"第 4c 步：款式"：录加工价时查款式存在且启用、列表里填款号和名称（RFC 006 §13 实现记录）；
 * 在那之前款式由外键兜底（不存在的款式插不进去，1000"关联的数据不存在"）
 */
@Service
@RequiredArgsConstructor
public class PriceServiceImpl implements PriceService {

    /** 字典 process_mode：加工方式 */
    static final String PROCESS_MODE = "process_mode";

    /** 加工价的单位：字典 unit 的内置项"件"（RFC 003 §18） */
    static final String PIECE = "PIECE";

    /** 批量取现价一次最多带多少个 key：每个 key 4 个参数，离 PostgreSQL 一条语句 32767 个参数的上限远着 */
    static final int BATCH = 1000;

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final PriceMapper priceMapper;
    private final SupplierService supplierService;
    private final MaterialService materialService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final Clock clock;

    // ------------------------------------------------------------------ 列表

    @Override
    public PageResult<PriceRowVO> page(PriceQuery query) {
        IPage<PriceRowVO> page;
        if (Boolean.TRUE.equals(query.getCurrent())) {
            // 物料价只认物料现在的采购单位：先看命中的是哪些物料，问 basic 要单位，当参数传进 SQL（不跨域 JOIN）
            List<MaterialUnit> units = materialService.materialRefs(priceMapper.selectMaterialIds(query)).stream()
                    .map(m -> new MaterialUnit(m.id(), m.purchaseUnit())).toList();
            Page<PriceRowVO> request = query.toPage();
            request.setOptimizeCountSql(false);
            page = priceMapper.selectCurrentPage(request, query, units, LocalDate.now(clock));
        } else {
            page = priceMapper.selectHistoryPage(query.toPage(), query);
        }
        fill(page.getRecords());
        return new PageResult<>(page.getRecords(), page.getTotal());
    }

    /** 供应商、物料、SKU、记录人、作废人的名称，各域批量取（RFC 006 §7）；物料价的单位和物料现在的采购单位比，标"单位已变" */
    private void fill(List<PriceRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, SupplierRef> suppliers = supplierService.refs(ids(rows, PriceRowVO::getSupplierId));
        Map<Long, MaterialRef> materials = materialService.materialRefs(ids(rows, PriceRowVO::getMaterialId)).stream()
                .collect(Collectors.toMap(MaterialRef::id, Function.identity()));
        Map<Long, MaterialSkuRef> skus = materialService.skuRefs(ids(rows, PriceRowVO::getMaterialSkuId)).stream()
                .collect(Collectors.toMap(MaterialSkuRef::id, Function.identity()));
        // 第 4c 步：款式 —— styleService.refs(ids(rows, PriceRowVO::getStyleId)) 取款号、名称，下面循环里填 styleCode、styleName
        Map<Long, String> users = userService.nicknames(Stream.concat(rows.stream().map(PriceRowVO::getCreateBy),
                rows.stream().map(PriceRowVO::getVoidBy)).filter(Objects::nonNull).collect(Collectors.toSet()));
        for (PriceRowVO row : rows) {
            SupplierRef supplier = suppliers.get(row.getSupplierId());
            if (supplier != null) {
                row.setSupplierCode(supplier.code());
                row.setSupplierName(supplier.shortName());
            }
            MaterialRef material = materials.get(row.getMaterialId());
            if (material != null) {
                row.setMaterialCode(material.code());
                row.setMaterialName(material.name());
                row.setUnitChanged(!Objects.equals(row.getUnit(), material.purchaseUnit()));
            }
            MaterialSkuRef sku = skus.get(row.getMaterialSkuId());
            if (sku != null) {
                row.setSkuCode(sku.code());
                row.setSkuColorName(sku.colorName());
                row.setSkuSpec(sku.spec());
            }
            row.setCreateByName(users.get(row.getCreateBy()));
            row.setVoidByName(users.get(row.getVoidBy()));
        }
    }

    // ------------------------------------------------------------------ 录、作废

    @Override
    public CurrentPriceVO currentFor(PriceType type, long supplierId, Long materialId, Long materialSkuId, Long styleId,
                                     String processMode, LocalDate date) {
        BigDecimal taxPct = supplierService.getRef(supplierId).taxPct();
        LocalDate day = date != null ? date : LocalDate.now(clock);
        if (type == PriceType.MATERIAL) {
            if (materialId == null) {
                return new CurrentPriceVO(null, null, taxPct);
            }
            String unit = materialService.materialRefs(List.of(materialId)).getFirst().purchaseUnit();
            return new CurrentPriceVO(findMaterial(supplierId, materialId, materialSkuId, unit, day).orElse(null), unit, taxPct);
        }
        if (styleId == null || !StringUtils.hasText(processMode)) {
            return new CurrentPriceVO(null, PIECE, taxPct);
        }
        return new CurrentPriceVO(currentProcess(supplierId, styleId, processMode, day).orElse(null), PIECE, taxPct);
    }

    @Override
    @Transactional
    public long record(PriceSaveReq req) {
        PriceType type = req.getType();
        PricePerms.checkEdit(type);
        long supplierId = req.getSupplierId();
        // 还没准入的供应商先报价很常见：待准入、审批中、合作中都能录，停用、黑名单不能（RFC 004 §7）
        supplierService.checkUsable(supplierId, SupplierService.Usage.SAMPLE);
        SupplierRef supplier = supplierService.getRef(supplierId);
        LocalDate date = req.getEffectiveDate() != null ? req.getEffectiveDate() : LocalDate.now(clock);

        Price p = new Price();
        p.setType(type);
        p.setSupplierId(supplierId);
        // 和"同一层级"的现价比（原型 recordPrice）：物料价分 SKU 层和物料层，给卡其第一次单独定价时，现价是物料层的，不算同一层级
        Optional<PriceRef> sameLevel;
        if (type == PriceType.MATERIAL) {
            MaterialRef material = materialFor(req.getMaterialId(), req.getMaterialSkuId());
            p.setMaterialId(material.id());
            p.setMaterialSkuId(req.getMaterialSkuId());
            p.setUnit(material.purchaseUnit());
            sameLevel = findMaterial(supplierId, material.id(), req.getMaterialSkuId(), material.purchaseUnit(), date)
                    .filter(c -> Objects.equals(c.materialSkuId(), req.getMaterialSkuId()));
        } else {
            if (req.getStyleId() == null || !StringUtils.hasText(req.getProcessMode())) {
                throw new BizException(400, "加工价要选款式和加工方式");
            }
            // 第 4c 步：款式 —— styleService.checkUsable(req.getStyleId())：款式不存在、停用的不能录（1000）。
            // 现在由外键兜底：不存在的款式插不进去（1000"关联的数据不存在"），停用的还拦不住
            checkProcessMode(req.getProcessMode());
            p.setStyleId(req.getStyleId());
            p.setProcessMode(req.getProcessMode());
            p.setUnit(PIECE);
            sameLevel = currentProcess(supplierId, req.getStyleId(), req.getProcessMode(), date);
        }

        String reason = trim(req.getReason());
        if (sameLevel.isPresent()) {
            PriceRef current = sameLevel.get();
            if (current.price().compareTo(req.getPrice()) == 0) {
                throw new BizException(day(date) + " 的现价已经是 " + plain(current.price()) + "（" + day(current.effectiveDate())
                        + " 起），不用再录");
            }
            if (reason == null) {
                throw new BizException("现价 " + plain(current.price()) + "（" + day(current.effectiveDate()) + " 起）→ "
                        + plain(req.getPrice()) + "，要写调价原因");
            }
        }
        BigDecimal taxPct = req.getTaxPct() != null ? req.getTaxPct() : supplier.taxPct();
        if (taxPct == null) {
            throw new BizException(400, supplier.shortName() + " 没设税率，请填税率");
        }
        p.setPrice(req.getPrice());
        p.setTaxPct(taxPct);
        p.setTaxIncluded(req.getTaxIncluded() == null || req.getTaxIncluded());
        p.setFreightIncluded(req.getFreightIncluded() == null || req.getFreightIncluded());
        p.setEffectiveDate(date);
        p.setReason(reason);
        p.setRemark(trim(req.getRemark()));
        priceMapper.insert(p);
        return p.getId();
    }

    @Override
    @Transactional
    public void voidPrice(long id, String reason) {
        Price p = priceMapper.selectById(id);
        if (p == null) {
            throw new BizException(404, "这条价格不存在");
        }
        PricePerms.checkEdit(p.getType());
        // 条件更新（AND NOT voided）：已经作废的、两个人同时作废时后到的，都改到 0 行
        if (priceMapper.voidById(id, reason.strip(), LoginUser.currentUserId(), OffsetDateTime.now(clock)) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这条已经作废了");
        }
    }

    /** 物料价的物料、SKU：都要启用，SKU 要是这个物料的（RFC 006 §3）。物料、SKU 不存在 404 */
    private MaterialRef materialFor(Long materialId, Long skuId) {
        if (materialId == null) {
            throw new BizException(400, "物料价要选物料");
        }
        MaterialSkuRef sku = skuId == null ? null : materialService.skuRefs(List.of(skuId)).getFirst();
        if (sku != null && !sku.material().id().equals(materialId)) {
            throw new BizException(400, "SKU " + sku.code() + " 不是这个物料的");
        }
        MaterialRef material = sku != null ? sku.material() : materialService.materialRefs(List.of(materialId)).getFirst();
        if (!enabled(material.status())) {
            throw new BizException("物料 " + material.code() + " 已停用，不能录价格");
        }
        if (sku != null && !enabled(sku.status())) {
            throw new BizException("SKU " + sku.code() + " 已停用，不能录价格");
        }
        return material;
    }

    /** 加工方式只认字典里启用的 */
    private void checkProcessMode(String mode) {
        boolean ok = dictDataService.listByType(PROCESS_MODE).stream().anyMatch(o -> o.enabled() && o.value().equals(mode));
        if (!ok) {
            throw new BizException("加工方式「" + mode + "」不在选项里，或者已经停用了，请重新选");
        }
    }

    // ------------------------------------------------------------------ 取现价（给第 3、4 批）

    @Override
    public Optional<PriceRef> currentMaterial(long supplierId, long materialId, Long materialSkuId, LocalDate date) {
        String unit = materialService.materialRefs(List.of(materialId)).getFirst().purchaseUnit();
        return findMaterial(supplierId, materialId, materialSkuId, unit, date);
    }

    @Override
    public Map<MaterialPriceKey, PriceRef> currentMaterials(Collection<MaterialPriceKey> keys, LocalDate date) {
        List<MaterialPriceKey> wanted = keys.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> units = materialService.materialRefs(wanted.stream().map(MaterialPriceKey::materialId).toList())
                .stream().collect(Collectors.toMap(MaterialRef::id, MaterialRef::purchaseUnit));
        Map<MaterialPriceKey, PriceRef> result = new HashMap<>();
        for (int from = 0; from < wanted.size(); from += BATCH) {
            List<MaterialUnitKey> chunk = wanted.subList(from, Math.min(from + BATCH, wanted.size())).stream()
                    .map(k -> new MaterialUnitKey(k.supplierId(), k.materialId(), k.materialSkuId(), units.get(k.materialId())))
                    .toList();
            for (CurrentMaterialHit hit : priceMapper.selectCurrentMaterials(chunk, date)) {
                result.put(new MaterialPriceKey(hit.getSupplierId(), hit.getMaterialId(), hit.getKeySkuId()), PriceRef.of(hit));
            }
        }
        return result;
    }

    @Override
    public Optional<PriceRef> currentProcess(long supplierId, long styleId, String processMode, LocalDate date) {
        return Optional.ofNullable(priceMapper.selectCurrentProcess(supplierId, styleId, processMode, date)).map(PriceRef::of);
    }

    private Optional<PriceRef> findMaterial(long supplierId, long materialId, Long skuId, String unit, LocalDate date) {
        return Optional.ofNullable(priceMapper.selectCurrentMaterial(supplierId, materialId, skuId, unit, date)).map(PriceRef::of);
    }

    // ------------------------------------------------------------------ 小工具

    private static boolean enabled(Integer status) {
        return Integer.valueOf(1).equals(status);
    }

    private static <T> List<Long> ids(List<T> rows, Function<T, Long> id) {
        return rows.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 18.5000 → 18.5，和页面上 formatPrice 显示的一样 */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 提示里的日期：今年的写 11-01，别的年份带上年 */
    private String day(LocalDate date) {
        return date.getYear() == LocalDate.now(clock).getYear() ? date.format(MONTH_DAY) : date.toString();
    }
}
