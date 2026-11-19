package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.security.FieldPermissions;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 整组替换保存之前，按服务端核过的行身份保住看不见的价格。 */
final class PackFinancialAccess {
    private PackFinancialAccess() { }

    static void preserve(TechPackSaveReq req, TechPack head, PackContent.Content old) {
        if (FieldPermissions.has(FieldPermissions.COST)) return;
        if (req.getCost() != null) {
            var cost = req.getCost();
            FieldPermissions.price(FieldPermissions.COST, head.getBuyPrice(), cost.getBuyPrice());
            FieldPermissions.price(FieldPermissions.COST, head.getLaborPrice(), cost.getLaborPrice());
            FieldPermissions.preserve(FieldPermissions.COST, head.getCostMode(), cost.getCostMode());
            if (cost.getItems() != null && !cost.getItems().isEmpty()) FieldPermissions.require(FieldPermissions.COST);
            req.setCost(null); // Existing cost header and other-cost rows remain outside the replacement.
        }
        Set<Long> usedBom = new HashSet<>();
        for (var form : req.getBomItems() == null ? List.<com.lingmao.scm.module.techpack.dto.BomItemForm>of() : req.getBomItems()) {
            var candidates = old.bom().stream().filter(r -> form.getId() != null ? Objects.equals(r.item().getId(), form.getId())
                    : Objects.equals(r.item().getMaterialId(), form.getMaterialId()) && Objects.equals(r.item().getPart(), text(form.getPart()))).toList();
            if (candidates.size() > 1) throw new BizException(400, "重复物料行缺少明细身份，请刷新资料包后保存");
            var previous = candidates.isEmpty() ? null : candidates.getFirst();
            if (form.getId() != null && previous == null) throw new BizException(400, "物料行不属于当前资料包，请刷新后保存");
            if (previous != null && !usedBom.add(previous.item().getId())) throw new BizException(400, "同一物料明细不能重复提交");
            // 换了物料或供应商要重新定价，不沿用看不见的旧报价。
            boolean same = previous != null && Objects.equals(previous.item().getMaterialId(), form.getMaterialId())
                    && Objects.equals(previous.item().getSupplierId(), form.getSupplierId());
            if (previous != null && !same && (previous.item().getRefPrice() != null
                    || previous.skus().stream().anyMatch(s -> s.getRefPrice() != null)))
                FieldPermissions.require(FieldPermissions.COST);
            var item = same ? previous.item() : null;
            form.setRefPrice(FieldPermissions.price(FieldPermissions.COST, item == null ? null : item.getRefPrice(), form.getRefPrice()));
            form.setPriceSourceId(FieldPermissions.preserve(FieldPermissions.COST, item == null ? null : item.getPriceSourceId(), form.getPriceSourceId()));
            form.setPriceTaxIncluded(FieldPermissions.preserve(FieldPermissions.COST, item == null ? Boolean.TRUE : item.getPriceTaxIncluded(), form.getPriceTaxIncluded()));
            form.setPriceFreightIncluded(FieldPermissions.preserve(FieldPermissions.COST, item == null ? Boolean.TRUE : item.getPriceFreightIncluded(), form.getPriceFreightIncluded()));
            BomSkuMode mode = form.getSkuMode() == null ? BomSkuMode.FIXED : form.getSkuMode();
            for (var sku : form.getSkus() == null ? List.<com.lingmao.scm.module.techpack.dto.BomSkuForm>of() : form.getSkus()) {
                // 和 PackForms.bomSkus 用同样的已存格子身份。不相干的维度
                // 不能把同一个固定、按色、按码的格子变成看起来是新的、没价的行。
                Long colorId = mode.isByColor() ? sku.getColorId() : null;
                String sizeCode = mode.isBySize() ? sku.getSizeCode() : null;
                var prior = !same ? null : previous.skus().stream().filter(s -> Objects.equals(s.getMaterialSkuId(), sku.getMaterialSkuId())
                        && Objects.equals(s.getColorId(), colorId) && Objects.equals(s.getSizeCode(), sizeCode)).findFirst().orElse(null);
                sku.setRefPrice(FieldPermissions.price(FieldPermissions.COST, prior == null ? null : prior.getRefPrice(), sku.getRefPrice()));
            }
        }
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
