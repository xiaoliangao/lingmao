package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.techpack.dto.BomItemForm;
import com.lingmao.scm.module.techpack.dto.BomSkuForm;
import com.lingmao.scm.module.techpack.dto.CostItemForm;
import com.lingmao.scm.module.techpack.dto.CraftSectionForm;
import com.lingmao.scm.module.techpack.dto.ProcessForm;
import com.lingmao.scm.module.techpack.dto.SpecPointForm;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.entity.CraftText;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.ProcessImage;
import com.lingmao.scm.module.techpack.entity.SpecPoint;
import com.lingmao.scm.module.techpack.entity.SpecValue;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.module.techpack.service.impl.PackContent.Content;
import com.lingmao.scm.module.techpack.service.impl.PackContent.ProcessRow;
import com.lingmao.scm.module.techpack.service.impl.PackContent.SpecRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 保存草稿的请求 → 要写进库的明细（docs/api/01 §3.5）。只查格式，不做生效前的检查（没配齐料也能存）：
 * 部位、物料必填，用量、损耗率、单价不能是负数，物料、SKU 要存在、SKU 是这个物料的，配料、用量的颜色尺码和配料方式对得上。
 * 出错报 1000，说是哪个页签第几行。单位不收前端的，取物料的库存单位
 */
@Component
@RequiredArgsConstructor
class PackForms {

    private final MaterialService materialService;
    private final FileUrlSigner signer;
    private final TradeFeature tradeFeature;

    /** 工艺指示的插图另外按段整组替换（AttachmentService），不在 Content 里 */
    Content toContent(TechPackSaveReq req, List<String> sizes) {
        Set<String> sizeSet = Set.copyOf(sizes);
        return new Content(specs(req.getSpecPoints(), sizeSet), bom(req.getBomItems(), sizeSet), texts(req.getCraftSections()),
                processes(req.getProcesses()), req.getCost() == null ? List.of() : costItems(req.getCost().getItems()));
    }

    private List<SpecRow> specs(List<SpecPointForm> forms, Set<String> sizes) {
        List<SpecRow> rows = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            SpecPointForm f = forms.get(i);
            String at = "尺寸指示第 " + (i + 1) + " 行";
            if (!StringUtils.hasText(f.getPoint())) {
                throw new BizException(at + "没选部位");
            }
            SpecPoint p = new SpecPoint();
            p.setPoint(f.getPoint().strip());
            p.setMethod(blankToNull(f.getMethod()));
            p.setToleranceCm(nonNegative(f.getToleranceCm(), at + "的公差"));
            p.setGradeCm(f.getGradeCm());
            p.setRemark(blankToNull(f.getRemark()));
            List<SpecValue> values = new ArrayList<>();
            if (f.getValues() != null) {
                f.getValues().forEach((size, value) -> {
                    if (value == null) {
                        return;
                    }
                    checkSize(size, sizes, at);
                    SpecValue v = new SpecValue();
                    v.setSizeCode(size);
                    v.setValueCm(nonNegative(value, at + " " + size + " 码的尺寸"));
                    values.add(v);
                });
            }
            rows.add(new SpecRow(p, values));
        }
        return rows;
    }

    private List<BomRow> bom(List<BomItemForm> forms, Set<String> sizes) {
        Set<Long> materialIds = forms.stream().map(BomItemForm::getMaterialId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MaterialRef> materials = materialIds.isEmpty() ? Map.of()
                : CostCalculator.byId(materialService.materialRefs(materialIds), MaterialRef::id);
        Set<Long> skuIds = forms.stream().filter(f -> f.getSkus() != null).flatMap(f -> f.getSkus().stream())
                .map(BomSkuForm::getMaterialSkuId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MaterialSkuRef> skus = skuIds.isEmpty() ? Map.of()
                : CostCalculator.byId(materialService.skuRefs(skuIds), MaterialSkuRef::id);

        List<BomRow> rows = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            BomItemForm f = forms.get(i);
            String at = "物料清单第 " + (i + 1) + " 行";
            if (!StringUtils.hasText(f.getPart())) {
                throw new BizException(at + "没选部位");
            }
            if (f.getMaterialId() == null) {
                throw new BizException(at + "没选物料");
            }
            MaterialRef m = materials.get(f.getMaterialId());
            if (m == null) {
                throw new BizException(at + "的物料不存在了，可能已经被删除，请重选");
            }
            BomSkuMode skuMode = f.getSkuMode() == null ? BomSkuMode.FIXED : f.getSkuMode();
            BomUsageMode usageMode = f.getUsageMode() == null ? BomUsageMode.UNIFORM : f.getUsageMode();
            MaterialSource source = f.getSource() == null ? MaterialSource.PURCHASE : f.getSource();
            tradeFeature.rejectIfOff(source == MaterialSource.CUSTOMER_SUPPLIED, "客供物料");

            BomItem item = new BomItem();
            item.setPart(f.getPart().strip());
            item.setMaterialId(m.id());
            item.setSkuMode(skuMode);
            item.setUsageMode(usageMode);
            item.setUsageQty(usageMode == BomUsageMode.UNIFORM ? nonNegative(f.getUsageQty(), at + "的用量") : null);
            item.setUnit(m.stockUnit());
            item.setLossPct(f.getLossPct() == null ? BigDecimal.ZERO : nonNegative(f.getLossPct(), at + "的损耗率"));
            item.setSource(source);
            item.setSupplierId(f.getSupplierId());
            item.setRefPrice(nonNegative(f.getRefPrice(), at + "的参考单价"));
            item.setPriceSourceId(f.getPriceSourceId());
            item.setPriceTaxIncluded(f.getPriceTaxIncluded() == null || f.getPriceTaxIncluded());
            item.setPriceFreightIncluded(f.getPriceFreightIncluded() == null || f.getPriceFreightIncluded());
            item.setRemark(blankToNull(f.getRemark()));

            rows.add(new BomRow(item, bomSkus(f, skuMode, m, skus, sizes, at), sizeUsages(f, usageMode, sizes, at)));
        }
        return rows;
    }

    /** 配料：每格要和配料方式对得上（按款色的有颜色、按尺码的有尺码），同一格只能配一个，SKU 是这个物料的。没选 SKU 的格子不存 */
    private static List<BomItemSku> bomSkus(BomItemForm f, BomSkuMode mode, MaterialRef m, Map<Long, MaterialSkuRef> skus,
                                            Set<String> sizes, String at) {
        List<BomItemSku> rows = new ArrayList<>();
        Set<String> cells = new HashSet<>();
        for (BomSkuForm s : f.getSkus() == null ? List.<BomSkuForm>of() : f.getSkus()) {
            if (s.getMaterialSkuId() == null) {
                continue;
            }
            MaterialSkuRef sku = skus.get(s.getMaterialSkuId());
            if (sku == null) {
                throw new BizException(at + "配的物料 SKU 不存在了，请重选");
            }
            BomPricing.checkBelongs(sku, m);
            Long colorId = mode.isByColor() ? s.getColorId() : null;
            String size = mode.isBySize() ? s.getSizeCode() : null;
            if (mode.isByColor() && colorId == null) {
                throw new BizException(at + "按款色配料，配料要选是哪个颜色的");
            }
            if (mode.isBySize()) {
                checkSize(size, sizes, at);
            }
            if (!cells.add(colorId + "|" + size)) {
                throw new BizException(at + "有一格配了两个 SKU" + (colorId == null && size == null ? "（固定配料只配一个）" : ""));
            }
            BomItemSku row = new BomItemSku();
            row.setColorId(colorId);
            row.setSizeCode(size);
            row.setMaterialSkuId(sku.id());
            row.setRefPrice(nonNegative(s.getRefPrice(), at + " SKU " + sku.code() + " 的单价"));
            rows.add(row);
        }
        return rows;
    }

    /** 分码用量：没填的码不存（生效时会查出来） */
    private static List<BomItemSize> sizeUsages(BomItemForm f, BomUsageMode mode, Set<String> sizes, String at) {
        if (mode != BomUsageMode.BY_SIZE || f.getSizeUsages() == null) {
            return List.of();
        }
        List<BomItemSize> rows = new ArrayList<>();
        f.getSizeUsages().forEach((size, qty) -> {
            if (qty == null) {
                return;
            }
            checkSize(size, sizes, at);
            BomItemSize row = new BomItemSize();
            row.setSizeCode(size);
            row.setUsageQty(nonNegative(qty, at + " " + size + " 码的用量"));
            rows.add(row);
        });
        return rows;
    }

    /** 九段文字；同一段发了两次 → 1000。没字的段不存 */
    private static List<CraftText> texts(List<CraftSectionForm> forms) {
        List<CraftText> rows = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (CraftSectionForm f : forms) {
            if (f.getSection() == null) {
                throw new BizException("工艺指示有一段没说是哪一段");
            }
            if (!seen.add(f.getSection())) {
                throw new BizException("工艺指示的「" + f.getSection().getLabel() + "」发了两次");
            }
            if (StringUtils.hasText(f.getContent())) {
                CraftText t = new CraftText();
                t.setSection(f.getSection());
                t.setContent(f.getContent().strip());
                rows.add(t);
            }
        }
        return rows;
    }

    private List<ProcessRow> processes(List<ProcessForm> forms) {
        List<ProcessRow> rows = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            ProcessForm f = forms.get(i);
            String at = "工序工价第 " + (i + 1) + " 行";
            if (!StringUtils.hasText(f.getName())) {
                throw new BizException(at + "没填工序名称");
            }
            if (f.getStdSeconds() != null && f.getStdSeconds() < 0) {
                throw new BizException(at + "的标准工时不能是负数");
            }
            PackProcess p = new PackProcess();
            p.setCategory(blankToNull(f.getCategory()));
            p.setName(f.getName().strip());
            p.setRequirement(blankToNull(f.getRequirement()));
            p.setStdSeconds(f.getStdSeconds());
            p.setPrice(nonNegative(f.getPrice(), at + "的工价"));
            p.setRemark(blankToNull(f.getRemark()));
            List<ProcessImage> images = new ArrayList<>();
            (f.getImages() == null ? List.<AttachmentReq>of() : f.getImages()).forEach(img -> {
                String url = signer.strip(img.getUrl());
                if (url == null || !url.startsWith(FileUrlSigner.PREFIX)) {
                    throw new BizException(400, at + "的图示不是本系统上传的文件");
                }
                ProcessImage image = new ProcessImage();
                image.setFileUrl(url);
                String thumb = signer.strip(img.getThumbUrl());
                image.setThumbUrl(thumb != null && thumb.startsWith(FileUrlSigner.PREFIX) ? thumb : null);
                images.add(image);
            });
            rows.add(new ProcessRow(p, images));
        }
        return rows;
    }

    private static List<CostItem> costItems(List<CostItemForm> forms) {
        List<CostItem> rows = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            CostItemForm f = forms.get(i);
            String at = "其他费用第 " + (i + 1) + " 行";
            if (!StringUtils.hasText(f.getName())) {
                throw new BizException(at + "没填名称");
            }
            if (f.getAmount() == null) {
                throw new BizException(at + "没填金额");
            }
            CostItem c = new CostItem();
            c.setName(f.getName().strip());
            c.setAmount(Num.money(f.getAmount()));
            c.setRemark(blankToNull(f.getRemark()));
            rows.add(c);
        }
        return rows;
    }

    private static void checkSize(String size, Set<String> sizes, String at) {
        if (size == null || !sizes.contains(size)) {
            throw new BizException(at + "的尺码「" + size + "」不在款式的尺码组里");
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String what) {
        if (value != null && value.signum() < 0) {
            throw new BizException(what + "不能是负数");
        }
        return value;
    }

    static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.strip() : null;
    }
}
