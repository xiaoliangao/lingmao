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

/** Preserve hidden financial inputs by server-verified line identity before replace-all persistence. */
final class PackFinancialAccess {
    private PackFinancialAccess() { }

    static void preserve(TechPackSaveReq req, TechPack head, PackContent.Content old) {
        if (FieldPermissions.has(FieldPermissions.COST)) return;
        if (req.getCost() != null) {
            var cost = req.getCost();
            FieldPermissions.price(FieldPermissions.COST, head.getBuyPrice(), cost.getBuyPrice());
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
            // Switching material/supplier requires a new financial decision, not the hidden old quote.
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
                // Match the persisted cell identity used by PackForms.bomSkus. Irrelevant dimensions
                // cannot turn the same fixed/colour/size cell into an apparently new, unpriced row.
                Long colorId = mode.isByColor() ? sku.getColorId() : null;
                String sizeCode = mode.isBySize() ? sku.getSizeCode() : null;
                var prior = !same ? null : previous.skus().stream().filter(s -> Objects.equals(s.getMaterialSkuId(), sku.getMaterialSkuId())
                        && Objects.equals(s.getColorId(), colorId) && Objects.equals(s.getSizeCode(), sizeCode)).findFirst().orElse(null);
                sku.setRefPrice(FieldPermissions.price(FieldPermissions.COST, prior == null ? null : prior.getRefPrice(), sku.getRefPrice()));
            }
        }
        Set<Long> usedProcesses = new HashSet<>();
        for (var form : req.getProcesses() == null ? List.<com.lingmao.scm.module.techpack.dto.ProcessForm>of() : req.getProcesses()) {
            var candidates = old.processes().stream().filter(r -> form.getId() != null ? Objects.equals(r.process().getId(), form.getId())
                    : Objects.equals(r.process().getName(), text(form.getName())) && Objects.equals(r.process().getCategory(), text(form.getCategory()))).toList();
            if (candidates.size() > 1) throw new BizException(400, "重复工序缺少明细身份，请刷新资料包后保存");
            var previous = candidates.isEmpty() ? null : candidates.getFirst().process();
            if (form.getId() != null && previous == null) throw new BizException(400, "工序行不属于当前资料包，请刷新后保存");
            if (previous != null && !usedProcesses.add(previous.getId())) throw new BizException(400, "同一工序明细不能重复提交");
            form.setPrice(FieldPermissions.price(FieldPermissions.COST, previous == null ? null : previous.getPrice(), form.getPrice()));
        }
    }

    private static String text(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
