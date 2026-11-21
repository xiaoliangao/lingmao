package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.basic.dto.MaterialDetailVO;
import com.lingmao.scm.module.basic.dto.MaterialDupVO;
import com.lingmao.scm.module.basic.dto.MaterialListVO;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickVO;
import com.lingmao.scm.module.basic.dto.MaterialPurchaseInfo;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialSkuVO;
import com.lingmao.scm.module.basic.dto.MaterialTypeReq;
import com.lingmao.scm.module.basic.dto.SkuBatchReq;
import com.lingmao.scm.module.basic.dto.SkuSpecReq;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.basic.mapper.MaterialSkuMapper;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class MaterialServiceImpl implements MaterialService {

    static final String TYPE_DICT = "material_type";
    static final String UNIT_DICT = "unit";
    static final String SEASON_DICT = "season";

    /** 面料、里料：只有它们填面料功能（RFC 012 §5.1） */
    static final Set<String> FABRIC_TYPES = Set.of("FABRIC", "LINING");
    /** 物料编码 = 分类码 + 4 位流水 */
    static final int CODE_WIDTH = 4;
    /** 取到的号已经被导入的老编码占了，最多再取几次（RFC 003 §7.1） */
    static final int MAX_TRIES = 100;
    /** 当场加的物料类型，字典值 = CUSTOM_ + 序号（PRD §6.1.5 规则 6） */
    static final String CUSTOM_TYPE = "CUSTOM_";

    private final MaterialMapper materialMapper;
    private final MaterialSkuMapper materialSkuMapper;
    private final MaterialCategoryMapper materialCategoryMapper;
    private final ColorMapper colorMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final DictChecks dictChecks;
    private final TradeFeature tradeFeature;
    private final AttachmentService attachmentService;
    private final DocLogService docLogService;
    private final MaterialImport materialImport;
    private final DictDataService dictDataService;

    // ---------- 物料 ----------

    @Override
    public PageResult<MaterialListVO> page(MaterialQuery query) {
        query.setKeyword(Texts.trim(query.getKeyword()));
        IPage<MaterialListVO> page = materialMapper.selectListPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
        List<MaterialListVO> rows = page.getRecords();
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.MATERIAL,
                rows.stream().map(MaterialListVO::getId).toList(), Attachment.IMAGE);
        rows.forEach(r -> r.setImage(images.get(r.getId())));
        return PageResult.of(page);
    }

    @Override
    public MaterialDetailVO detail(long id) {
        Material material = getEntity(id);
        MaterialListVO names = materialMapper.selectListByIds(List.of(id)).getFirst();
        List<MaterialSku> skus = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                .eq(MaterialSku::getMaterialId, id)
                .orderByAsc(MaterialSku::getCode));
        Map<Long, Color> colors = colorsOf(skus);
        Map<Long, AttachmentVO> skuImages = attachmentService.firsts(BizType.MATERIAL_SKU,
                skus.stream().map(MaterialSku::getId).toList(), Attachment.IMAGE);
        List<MaterialSkuVO> skuVOs = skus.stream()
                .map(k -> MaterialSkuVO.of(k, colors.get(k.getColorId()), skuImages.get(k.getId()))).toList();
        return new MaterialDetailVO(material, names.getCategoryCode(), names.getCategoryName(), names.getParentCategoryName(),
                names.getDefaultSupplierName(), skuVOs, attachmentService.list(BizType.MATERIAL, id, Attachment.IMAGE));
    }

    @Override
    @Transactional
    public Long create(MaterialCreateReq req) {
        MaterialCategory category = requireLeafCategory(req.getCategoryId());
        Material material = new Material();
        material.setCategoryId(category.getId());
        material.setStockUnit(Texts.trim(req.getStockUnit()));
        fill(material, req, null);
        material.setCode(nextCode(category.getCode()));
        materialMapper.insert(material);
        List<MaterialSku> skus = insertSkus(material, req.getColorIds(), req.getSpecs());
        docLogService.add(BizType.MATERIAL, material.getId(), "create", skuCodes(skus));
        return material.getId();
    }

    @Override
    @Transactional
    public DictOption addType(MaterialTypeReq req) {
        String label = req.getLabel().strip();
        List<DictData> types = dictDataService.listWithText(TYPE_DICT);
        for (DictData t : types) {
            if (t.getLabel().strip().equals(label)) {
                throw new BizException(Integer.valueOf(1).equals(t.getStatus()) ? "已经有物料类型「" + label + "」了，直接选它"
                        : "物料类型「" + label + "」已经有了、停用着：找管理员在 管理后台 › 字典 里启用");
            }
        }
        int next = types.stream().map(DictData::getValue).filter(v -> v.matches(CUSTOM_TYPE + "\\d+"))
                .mapToInt(v -> Integer.parseInt(v.substring(CUSTOM_TYPE.length()))).max().orElse(0) + 1;
        DictDataSaveReq item = new DictDataSaveReq();
        item.setTypeCode(TYPE_DICT);
        item.setLabel(label);
        item.setValue(CUSTOM_TYPE + next);
        item.setSort(types.stream().map(DictData::getSort).filter(Objects::nonNull).max(Integer::compare).orElse(0) + 1);
        // 备料比由生产评估后填，没填 = 0（Q18，甲方 11-18），和预置的物料类型一样存 0
        item.setNumValue(Objects.requireNonNullElse(req.getReservePct(), BigDecimal.ZERO));
        item.setRemark("新建物料时加的");
        dictDataService.create(item);
        return new DictOption(label, item.getValue(), 1);
    }

    @Override
    @Transactional
    public void update(long id, MaterialSaveReq req) {
        Material material = getEntity(id);
        if (!material.getCategoryId().equals(req.getCategoryId())) {
            throw new BizException("物料分类建好就不能改：分类码是物料编码的前缀。分错了而且没用过的，删掉重建");
        }
        if (!material.getStockUnit().equals(Texts.trim(req.getStockUnit()))) {
            throw new BizException("库存单位建好就不能改：库存、BOM 用量都按它记，改了旧数就错了。采购单位和换算系数能改");
        }
        Material old = new Material();
        old.setMaterialType(material.getMaterialType());
        old.setStockUnit(material.getStockUnit());
        old.setPurchaseUnit(material.getPurchaseUnit());
        old.setSeason(material.getSeason());
        fill(material, req, old);
        materialMapper.update(material, Updates.replaceById(material));
        docLogService.add(BizType.MATERIAL, id, "update", null);
    }

    @Override
    @Transactional
    public void delete(long id) {
        getEntity(id);
        List<Long> skuIds = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                        .select(MaterialSku::getId)
                        .eq(MaterialSku::getMaterialId, id))
                .stream().map(MaterialSku::getId).toList();
        // 先删本域的子表（SKU 和挂在 SKU 上的图、记录），再删挂在物料上的，最后删物料（RFC 003 §2）。
        // 哪一步被外键拦住（SKU 被价格档案、BOM 引用了……）整个回滚，前面删的图和记录也都还在
        for (Long skuId : skuIds) {
            attachmentService.deleteByDoc(BizType.MATERIAL_SKU, skuId);
            docLogService.deleteByDoc(BizType.MATERIAL_SKU, skuId);
        }
        materialSkuMapper.delete(new LambdaQueryWrapper<MaterialSku>().eq(MaterialSku::getMaterialId, id));
        attachmentService.deleteByDoc(BizType.MATERIAL, id);
        docLogService.deleteByDoc(BizType.MATERIAL, id);
        materialMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(materialMapper, Material::new, id, Statuses.ENABLED, "物料");
        docLogService.add(BizType.MATERIAL, id, "enable", null);
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(materialMapper, Material::new, id, Statuses.DISABLED, "物料");
        docLogService.add(BizType.MATERIAL, id, "disable", null);
    }

    // ---------- SKU ----------

    @Override
    @Transactional
    public List<MaterialSkuVO> addSkus(long id, SkuBatchReq req) {
        lock(id);
        Material material = getEntity(id);
        List<MaterialSku> skus = insertSkus(material, req.getColorIds(), req.getSpecs());
        if (!skus.isEmpty()) {
            docLogService.add(BizType.MATERIAL, id, "addSkus", skuCodes(skus));
        }
        Map<Long, Color> colors = colorsOf(skus);
        return skus.stream().map(k -> MaterialSkuVO.of(k, colors.get(k.getColorId()), null)).toList();
    }

    @Override
    @Transactional
    public void updateSku(long id, long skuId, MaterialSkuSaveReq req) {
        MaterialSku sku = getSku(id, skuId);
        sku.setSupplierColorNo(Texts.trim(req.getSupplierColorNo()));
        sku.setSupplierItemNo(Texts.trim(req.getSupplierItemNo()));
        sku.setPrice(com.lingmao.scm.security.FieldPermissions.price(com.lingmao.scm.security.FieldPermissions.COST, sku.getPrice(), req.getPrice()));
        sku.setSafetyStock(req.getSafetyStock());
        sku.setRemark(Texts.trim(req.getRemark()));
        materialSkuMapper.update(sku, Updates.replaceById(sku));
        docLogService.add(BizType.MATERIAL, id, "updateSku", sku.getCode());
    }

    @Override
    @Transactional
    public void deleteSku(long id, long skuId) {
        lock(id);
        MaterialSku sku = getSku(id, skuId);
        long count = materialSkuMapper.selectCount(new LambdaQueryWrapper<MaterialSku>().eq(MaterialSku::getMaterialId, id));
        if (count <= 1) {
            throw new BizException("物料至少要留一个 SKU。整个物料不要了，删物料或者停用");
        }
        attachmentService.deleteByDoc(BizType.MATERIAL_SKU, skuId);
        docLogService.deleteByDoc(BizType.MATERIAL_SKU, skuId);
        materialSkuMapper.deleteById(skuId);   // 被价格档案、BOM、单据、库存引用了，外键拦住 → 1005，图和记录也回滚
        docLogService.add(BizType.MATERIAL, id, "deleteSku", sku.getCode());
    }

    @Override
    @Transactional
    public void enableSku(long id, long skuId) {
        MaterialSku sku = getSku(id, skuId);
        Statuses.set(materialSkuMapper, MaterialSku::new, skuId, Statuses.ENABLED, "物料 SKU");
        docLogService.add(BizType.MATERIAL, id, "enableSku", sku.getCode());
    }

    @Override
    @Transactional
    public void disableSku(long id, long skuId) {
        MaterialSku sku = getSku(id, skuId);
        Statuses.set(materialSkuMapper, MaterialSku::new, skuId, Statuses.DISABLED, "物料 SKU");
        docLogService.add(BizType.MATERIAL, id, "disableSku", sku.getCode());
    }

    @Override
    @Transactional
    public List<AttachmentVO> replaceImages(long id, List<AttachmentReq> files) {
        getEntity(id);
        List<AttachmentVO> images = attachmentService.replace(BizType.MATERIAL, id, Attachment.IMAGE, files);
        docLogService.add(BizType.MATERIAL, id, "images", images.size() + " 张");
        return images;
    }

    @Override
    @Transactional
    public List<AttachmentVO> replaceSkuImages(long id, long skuId, List<AttachmentReq> files) {
        MaterialSku sku = getSku(id, skuId);
        List<AttachmentVO> images = attachmentService.replace(BizType.MATERIAL_SKU, skuId, Attachment.IMAGE, files);
        docLogService.add(BizType.MATERIAL, id, "skuImages", sku.getCode());
        return images;
    }

    @Override
    public List<DocLogVO> logs(long id) {
        getEntity(id);
        return docLogService.list(BizType.MATERIAL, id);
    }

    // ---------- 查重、选 SKU、给下游 ----------

    @Override
    public List<MaterialDupVO> duplicates(Long categoryId, String name, Long defaultSupplierId, String supplierItemNo, Long excludeId) {
        String trimmedName = Texts.trim(name);
        String itemNo = Texts.trim(supplierItemNo);
        boolean byName = categoryId != null && trimmedName != null;
        boolean byItemNo = defaultSupplierId != null && itemNo != null;
        if (!byName && !byItemNo) {
            return List.of();
        }
        return materialMapper.selectDuplicates(byName ? categoryId : null, byName ? trimmedName : null,
                byItemNo ? defaultSupplierId : null, byItemNo ? itemNo : null, excludeId);
    }

    @Override
    public PageResult<MaterialPickVO> pick(MaterialPickQuery query) {
        query.setKeyword(Texts.trim(query.getKeyword()));
        IPage<MaterialPickVO> page = materialMapper.selectPickPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
        List<MaterialPickVO> rows = page.getRecords();
        Map<Long, AttachmentVO> skuImages = attachmentService.firsts(BizType.MATERIAL_SKU,
                rows.stream().map(MaterialPickVO::getSkuId).toList(), Attachment.IMAGE);
        Map<Long, AttachmentVO> materialImages = attachmentService.firsts(BizType.MATERIAL,
                rows.stream().map(MaterialPickVO::getMaterialId).distinct().toList(), Attachment.IMAGE);
        rows.forEach(r -> r.setImage(skuImages.getOrDefault(r.getSkuId(), materialImages.get(r.getMaterialId()))));
        return PageResult.of(page);
    }

    @Override
    public List<MaterialRef> materialRefs(Collection<Long> materialIds) {
        List<Long> ids = materialIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Material> found = materialMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        requireAll(ids, found.keySet(), "物料");
        return ids.stream().map(found::get).map(MaterialRef::from).toList();
    }

    @Override
    public List<MaterialSkuRef> skuRefs(Collection<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, MaterialSku> skus = materialSkuMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(MaterialSku::getId, Function.identity()));
        requireAll(ids, skus.keySet(), "物料 SKU");
        Map<Long, MaterialRef> materials = materialRefs(skus.values().stream().map(MaterialSku::getMaterialId).toList())
                .stream().collect(Collectors.toMap(MaterialRef::id, Function.identity()));
        Map<Long, Color> colors = colorsOf(skus.values());
        return ids.stream().map(skus::get)
                .map(k -> MaterialSkuRef.of(k, materials.get(k.getMaterialId()), colors.get(k.getColorId()))).toList();
    }

    @Override
    public Map<Long, MaterialPurchaseInfo> purchaseInfos(Collection<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MaterialSku> skus = materialSkuMapper.selectByIds(ids);
        Map<Long, Material> materials = materialMapper.selectByIds(skus.stream().map(MaterialSku::getMaterialId).distinct().toList())
                .stream().collect(Collectors.toMap(Material::getId, Function.identity()));
        return skus.stream().collect(Collectors.toMap(MaterialSku::getId, k -> MaterialPurchaseInfo.of(k, materials.get(k.getMaterialId()))));
    }

    @Override
    public Map<Long, MaterialSkuBrief> skuBriefs(Collection<Long> skuIds) {
        List<Long> ids = skuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<MaterialSku> skus = materialSkuMapper.selectByIds(ids);
        Map<Long, Material> materials = materialMapper.selectByIds(skus.stream().map(MaterialSku::getMaterialId).distinct().toList())
                .stream().collect(Collectors.toMap(Material::getId, Function.identity()));
        Map<Long, Color> colors = colorsOf(skus);
        Map<Long, AttachmentVO> skuImages = attachmentService.firsts(BizType.MATERIAL_SKU, ids, Attachment.IMAGE);
        Map<Long, AttachmentVO> materialImages = attachmentService.firsts(BizType.MATERIAL,
                List.copyOf(materials.keySet()), Attachment.IMAGE);
        Map<Long, MaterialSkuBrief> briefs = new HashMap<>();
        for (MaterialSku k : skus) {
            Material m = materials.get(k.getMaterialId());
            Color c = colors.get(k.getColorId());
            MaterialSkuBrief b = new MaterialSkuBrief();
            b.setMaterialSkuId(k.getId());
            b.setSkuCode(k.getCode());
            b.setMaterialId(m.getId());
            b.setMaterialCode(m.getCode());
            b.setMaterialName(m.getName());
            b.setMaterialType(m.getMaterialType());
            b.setColorName(c == null ? null : c.getName());
            b.setColorHex(c == null ? null : c.getHex());
            b.setSpec(k.getSpec());
            b.setStockUnit(m.getStockUnit());
            b.setImage(skuImages.getOrDefault(k.getId(), materialImages.get(m.getId())));
            briefs.put(k.getId(), b);
        }
        return briefs;
    }

    @Override
    public Map<String, Long> skuIdsByCodes(Collection<String> codes) {
        List<String> upper = codes.stream().filter(Objects::nonNull).map(c -> c.strip().toUpperCase(Locale.ROOT)).distinct().toList();
        if (upper.isEmpty()) {
            return Map.of();
        }
        String placeholders = IntStream.range(0, upper.size()).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(","));
        List<MaterialSku> found = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                .select(MaterialSku::getId, MaterialSku::getCode, MaterialSku::getLegacyCode)
                .apply("(upper(code) IN (" + placeholders + ") OR upper(legacy_code) IN (" + placeholders + "))", upper.toArray()));
        // 系统码优先：领猫编码碰巧等于另一个 SKU 的系统码时按系统码对（RFC 012 §4.2）
        Map<String, Long> out = new HashMap<>();
        found.forEach(k -> {
            if (k.getLegacyCode() != null) out.putIfAbsent(k.getLegacyCode().toUpperCase(Locale.ROOT), k.getId());
        });
        found.forEach(k -> out.put(k.getCode().toUpperCase(Locale.ROOT), k.getId()));
        out.keySet().retainAll(upper);
        return out;
    }

    @Override
    public Map<String, List<MaterialSkuRef>> enabledSkusByNames(Collection<String> names) {
        List<String> lower = names.stream().filter(StringUtils::hasText).map(n -> n.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        if (lower.isEmpty()) {
            return Map.of();
        }
        List<Material> materials = materialMapper.selectList(new LambdaQueryWrapper<Material>()
                .eq(Material::getStatus, 1)
                .apply("lower(btrim(name)) IN (" + IntStream.range(0, lower.size()).mapToObj(i -> "{" + i + "}")
                        .collect(Collectors.joining(",")) + ")", lower.toArray())
                .orderByAsc(Material::getCode));
        Map<String, List<MaterialSkuRef>> out = new LinkedHashMap<>();
        enabledSkus(materials).forEach((id, skus) -> out.computeIfAbsent(skus.getFirst().material().name().strip().toLowerCase(Locale.ROOT),
                x -> new ArrayList<>()).addAll(skus));
        return out;
    }

    @Override
    public Map<Long, List<MaterialSkuRef>> enabledSkusOf(Collection<Long> materialIds) {
        List<Long> ids = materialIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : enabledSkus(materialMapper.selectList(new LambdaQueryWrapper<Material>()
                .in(Material::getId, ids).eq(Material::getStatus, 1).orderByAsc(Material::getCode)));
    }

    /** 这些物料启用的 SKU：物料 id → SKU（按物料的顺序、SKU 编码排）；没有启用 SKU 的不在里面 */
    private Map<Long, List<MaterialSkuRef>> enabledSkus(List<Material> materials) {
        if (materials.isEmpty()) {
            return Map.of();
        }
        Map<Long, MaterialRef> refs = materials.stream().map(MaterialRef::from)
                .collect(Collectors.toMap(MaterialRef::id, Function.identity()));
        List<MaterialSku> skus = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                .in(MaterialSku::getMaterialId, refs.keySet()).eq(MaterialSku::getStatus, 1).orderByAsc(MaterialSku::getCode));
        Map<Long, Color> colors = colorsOf(skus);
        Map<Long, List<MaterialSku>> byMaterial = skus.stream().collect(Collectors.groupingBy(MaterialSku::getMaterialId));
        Map<Long, List<MaterialSkuRef>> out = new LinkedHashMap<>();
        for (Material m : materials) {
            for (MaterialSku k : byMaterial.getOrDefault(m.getId(), List.of())) {
                out.computeIfAbsent(m.getId(), x -> new ArrayList<>()).add(MaterialSkuRef.of(k, refs.get(m.getId()), colors.get(k.getColorId())));
            }
        }
        return out;
    }

    // ---------- 导入导出 ----------

    @Override
    public byte[] importTemplate() {
        return materialImport.template();
    }

    @Override
    public ImportResult importExcel(MultipartFile file) {
        return materialImport.importFile(file);
    }

    @Override
    public byte[] export(MaterialQuery query) {
        query.setKeyword(Texts.trim(query.getKeyword()));
        List<Long> ids = materialMapper.selectIds(query, Keywords.codes(query.getKeyword()), Excel.MAX_EXPORT_ROWS + 1);
        if (ids.size() > Excel.MAX_EXPORT_ROWS) {
            throw new BizException("要导出的超过 " + Excel.MAX_EXPORT_ROWS / 10_000 + " 万行了，请缩小筛选范围再导出");
        }
        return materialImport.export(ids);
    }

    // ---------- 内部 ----------

    /**
     * 物料自己的列（新建、修改共用）。old 是修改前的字典值（新建传 null）：没改的旧值，字典项后来停用了也照样留着
     */
    private void fill(Material material, MaterialSaveReq req, Material old) {
        String source = req.getDefaultSource() == null ? MaterialSource.PURCHASE.name() : req.getDefaultSource();
        tradeFeature.rejectIfOff(MaterialSource.valueOf(source).isTrade(), "默认来源「客供」");

        String type = Texts.trim(req.getMaterialType());
        String stockUnit = material.getStockUnit();
        String purchaseUnit = Objects.requireNonNullElse(Texts.trim(req.getPurchaseUnit()), stockUnit);
        String season = Texts.trim(req.getSeason());
        dictChecks.require(TYPE_DICT, type, old == null ? null : old.getMaterialType(), "物料类型");
        dictChecks.require(UNIT_DICT, stockUnit, old == null ? null : old.getStockUnit(), "库存单位");
        dictChecks.require(UNIT_DICT, purchaseUnit, old == null ? null : old.getPurchaseUnit(), "采购单位");
        dictChecks.require(SEASON_DICT, season, old == null ? null : old.getSeason(), "季节");

        BigDecimal factor = Objects.requireNonNullElse(req.getConversionFactor(), BigDecimal.ONE);
        if (purchaseUnit.equals(stockUnit) && factor.compareTo(BigDecimal.ONE) != 0) {
            throw new BizException(400, "采购单位和库存单位一样时，换算系数只能是 1");
        }

        material.setName(Texts.trim(req.getName()));
        material.setMaterialType(type);
        material.setPurchaseUnit(purchaseUnit);
        material.setConversionFactor(factor);
        material.setWidthCm(req.getWidthCm());
        material.setWeightGsm(req.getWeightGsm());
        material.setComposition(Texts.trim(req.getComposition()));
        material.setTexture(Texts.trim(req.getTexture()));
        // 面料功能只有面料、里料有（RFC 012 §5.1）；改成别的类型时清掉，页面上也不显示
        material.setFabricFunction(FABRIC_TYPES.contains(type) ? Texts.trim(req.getFabricFunction()) : null);
        material.setWithTag(req.getWithTag());
        // 功能吊牌（甲方 11-21）：选了"是"要写是哪几个吊牌；不是"是"的不存名称
        String tagNames = Boolean.TRUE.equals(req.getWithTag()) ? Texts.trim(req.getTagNames()) : null;
        if (Boolean.TRUE.equals(req.getWithTag()) && tagNames == null) {
            throw new BizException(400, "带功能吊牌选了\"是\"，要填吊牌名称");
        }
        material.setTagNames(tagNames);
        material.setDefaultSupplierId(req.getDefaultSupplierId());   // 只当默认值，外键保证存在，不查状态（架构 A23）
        material.setSupplierItemNo(Texts.trim(req.getSupplierItemNo()));
        material.setRefPrice(com.lingmao.scm.security.FieldPermissions.price(com.lingmao.scm.security.FieldPermissions.COST,
                material.getRefPrice(), req.getRefPrice()));
        material.setMoq(req.getMoq());
        material.setMinPackQty(Objects.requireNonNullElse(req.getMinPackQty(), BigDecimal.ONE));
        material.setLeadDays(req.getLeadDays());
        material.setYear(req.getYear());
        material.setSeason(season);
        material.setDefaultSource(source);
        material.setDefaultLossPct(req.getDefaultLossPct());
        material.setReservePct(req.getReservePct());
        material.setRemark(Texts.trim(req.getRemark()));
    }

    /** 物料只能挂在启用的二级分类上（RFC 003 §5.4），上级也要启用 */
    private MaterialCategory requireLeafCategory(Long categoryId) {
        MaterialCategory category = materialCategoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException("物料分类不存在，可能刚被删了，请刷新");
        }
        if (category.getLevel() != 2 || category.getCode() == null) {
            throw new BizException("物料要挂在二级分类上（有编码的那一级）");
        }
        MaterialCategory parent = materialCategoryMapper.selectById(category.getParentId());
        if (!Statuses.enabled(category.getStatus()) || parent == null || !Statuses.enabled(parent.getStatus())) {
            throw new BizException("物料分类「" + category.getName() + "」已经停用了，请换一个");
        }
        return category;
    }

    /**
     * 分类码 + 4 位流水。取到的号已经有了（11-18 以前导入的照用领猫编码）就再取下一个，最多试 100 次。
     * 先查后插在同一个事务里：sys_serial 这一行的锁持有到提交，同一分类的新建本来就排队；和导入撞上由唯一索引兜底（1000）
     */
    private String nextCode(String categoryCode) {
        return nextCode(serialNoGenerator, materialMapper, categoryCode);
    }

    /** 同上；导入也按它发编码（RFC 012 §4.2）。甲方的新编码规则到了改这里 */
    static String nextCode(SerialNoGenerator serialNoGenerator, MaterialMapper materialMapper, String categoryCode) {
        for (int i = 0; i < MAX_TRIES; i++) {
            String code = serialNoGenerator.next(categoryCode, CODE_WIDTH);
            if (!materialMapper.exists(new LambdaQueryWrapper<Material>().eq(Material::getCode, code))) {
                return code;
            }
        }
        throw new BizException("分类 " + categoryCode + " 连着 " + MAX_TRIES + " 个编码都被占了，请联系管理员");
    }

    /**
     * 颜色 × 规格两两组合建 SKU（RFC 003 §7.2）：没选颜色的只按规格建，都没有就是一个不带颜色、规格的 SKU；
     * 已有的组合跳过。新 SKU 的颜色要启用。返回这次新建的
     */
    private List<MaterialSku> insertSkus(Material material, List<Long> colorIds, List<SkuSpecReq> specReqs) {
        List<Color> colors = resolveColors(colorIds);
        List<Spec> specs = resolveSpecs(specReqs);
        Set<String> existing = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                        .eq(MaterialSku::getMaterialId, material.getId()))
                .stream().map(k -> SkuCodes.combo(k.getColorId(), k.getSpecCode())).collect(Collectors.toCollection(HashSet::new));

        List<Color> colorAxis = colors.isEmpty() ? Arrays.asList((Color) null) : colors;
        List<Spec> specAxis = specs.isEmpty() ? Arrays.asList((Spec) null) : specs;
        List<MaterialSku> rows = new ArrayList<>();
        for (Color color : colorAxis) {
            for (Spec spec : specAxis) {
                Long colorId = color == null ? null : color.getId();
                String specCode = spec == null ? null : spec.code();
                if (!existing.add(SkuCodes.combo(colorId, specCode))) {
                    continue;
                }
                MaterialSku sku = new MaterialSku();
                sku.setMaterialId(material.getId());
                sku.setColorId(colorId);
                sku.setSpec(spec == null ? null : spec.spec());
                sku.setSpecCode(specCode);
                sku.setCode(SkuCodes.compose(material.getCode(), color == null ? null : color.getCode(), specCode));
                rows.add(sku);
            }
        }
        if (rows.isEmpty()) {
            return rows;
        }
        List<String> codes = rows.stream().map(MaterialSku::getCode).toList();
        List<String> taken = materialSkuMapper.selectList(new LambdaQueryWrapper<MaterialSku>()
                        .select(MaterialSku::getCode)
                        .in(MaterialSku::getCode, codes))
                .stream().map(MaterialSku::getCode).toList();
        if (!taken.isEmpty()) {
            throw new BizException("SKU 码 " + String.join("、", taken) + " 已经被别的 SKU 用了（导入的老编码），换个规格码");
        }
        rows.forEach(materialSkuMapper::insert);
        return rows;
    }

    /** 选的颜色：都要存在、启用；重复的去掉，保持选的顺序 */
    private List<Color> resolveColors(List<Long> colorIds) {
        List<Long> ids = colorIds == null ? List.of() : colorIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Color> found = colorMapper.selectByIds(ids).stream().collect(Collectors.toMap(Color::getId, Function.identity()));
        List<Color> colors = new ArrayList<>();
        for (Long id : ids) {
            Color color = found.get(id);
            if (color == null) {
                throw new BizException("选的颜色有一个不存在了，可能刚被删了，请刷新");
            }
            if (!Statuses.enabled(color.getStatus())) {
                throw new BizException("颜色 " + color.getCode() + " " + color.getName() + " 已经停用了");
            }
            colors.add(color);
        }
        return colors;
    }

    /** 一个规格和它的规格码 */
    private record Spec(String spec, String code) {
    }

    /** 填的规格：规格码不填就由规格算；空行跳过；规格码重复的报错 */
    private static List<Spec> resolveSpecs(List<SkuSpecReq> specReqs) {
        Map<String, Spec> specs = new LinkedHashMap<>();
        for (SkuSpecReq req : specReqs == null ? List.<SkuSpecReq>of() : specReqs) {
            String spec = Texts.trim(req.getSpec());
            String code = Texts.upper(req.getSpecCode());
            if (code == null) {
                code = SkuCodes.specCodeOf(spec);
            }
            if (spec == null && code == null) {
                continue;
            }
            if (code == null) {
                throw new BizException(400, "规格「" + spec + "」算不出规格码，请手填一个");
            }
            if (!SkuCodes.SPEC_CODE.matcher(code).matches()) {
                throw new BizException(400, "规格码「" + code + "」只能是字母、数字和小数点（最多 16 位）：它要进 SKU 码。请手填一个");
            }
            Spec previous = specs.putIfAbsent(code, new Spec(spec == null ? code : spec, code));
            if (previous != null) {
                throw new BizException("规格码 " + code + " 填了两次");
            }
        }
        return new ArrayList<>(specs.values());
    }

    private void lock(long id) {
        if (materialMapper.lockById(id) == null) {
            throw new BizException(404, "物料不存在: " + id);
        }
    }

    private Material getEntity(long id) {
        Material material = materialMapper.selectById(id);
        if (material == null) {
            throw new BizException(404, "物料不存在: " + id);
        }
        return material;
    }

    /** SKU 要属于这个物料，不然当不存在 */
    private MaterialSku getSku(long materialId, long skuId) {
        MaterialSku sku = materialSkuMapper.selectById(skuId);
        if (sku == null || !sku.getMaterialId().equals(materialId)) {
            throw new BizException(404, "物料 SKU 不存在: " + skuId);
        }
        return sku;
    }

    /** 调用方拿不带颜色的 SKU 的 colorId（null）来 get：返回的 Map 要能 get(null)，所以空的也给 HashMap，不给 Map.of() */
    private Map<Long, Color> colorsOf(Collection<MaterialSku> skus) {
        List<Long> ids = skus.stream().map(MaterialSku::getColorId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return colorMapper.selectByIds(ids).stream().collect(Collectors.toMap(Color::getId, Function.identity()));
    }

    private static String skuCodes(List<MaterialSku> skus) {
        String codes = skus.stream().map(MaterialSku::getCode).collect(Collectors.joining("、"));
        return codes.length() <= 400 ? codes : codes.substring(0, 400) + "…（共 " + skus.size() + " 个）";
    }

    private static void requireAll(List<Long> ids, Set<Long> found, String what) {
        List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(404, what + "不存在: " + missing);
        }
    }
}
