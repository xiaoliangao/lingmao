package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按实际下单的一个款式 SKU 查生产 BOM 配齐没有（RFC010 §4.2）：资料包生效时查全集，用的时候按实际 SKU 再查一遍；
 * 订单锁定的旧版本作废了照样能用。
 * strict = true（新订单提交、审核）：款式颜色 SKU、物料、物料 SKU 停用了也拦；
 * false（已审核订单的算料、变更单）：只查配料、用量（缺了算料会算错），下单以后才停用的不拦，和停用"老单据照常走"一致
 */
final class RequirementChecks {
    private RequirementChecks() { }

    static List<String> check(TechPack pack, StyleOrderInfo style, StyleOrderInfo.Sku sku,
                              List<PackContent.BomRow> bom, Map<Long, MaterialRef> materials,
                              Map<Long, MaterialSkuRef> materialsSkus, boolean strict) {
        List<String> out = new ArrayList<>();
        String prefix = sku.code() + "：";
        if (strict && (style.status() == StyleStatus.DISABLED || !sku.enabled()
                || style.colors().stream().noneMatch(c -> c.id() == sku.colorId() && c.enabled()))) {
            out.add(prefix + "款式、颜色或 SKU 已停用");
        }
        if (pack.getStage() != PackStage.PROD || pack.getStatus() == PackStatus.DRAFT
                || !Objects.equals(pack.getStyleId(), style.id())) {
            out.add(prefix + "需要本款已生效或订单已锁定的生产 BOM");
            return out;
        }
        if (pack.getOwnerSnapshot() != null && (!pack.getOwnerSnapshot().sizes().contains(sku.sizeCode())
                || pack.getOwnerSnapshot().colors().stream().noneMatch(c -> Objects.equals(c.id(), sku.colorId())))) {
            out.add(prefix + "不在生产版生效时的色码范围内，请修订并生效生产 BOM 后再使用");
        }
        if (bom.isEmpty()) out.add(prefix + "生产 BOM 物料清单为空，请修订生产版");
        for (int i = 0; i < bom.size(); i++) {
            PackContent.BomRow row = bom.get(i);
            String at = prefix + "BOM 第 " + (i + 1) + " 行：";
            MaterialRef material = materials.get(row.item().getMaterialId());
            if (material == null) {
                out.add(at + "物料不存在了");
            } else if (strict && !Integer.valueOf(1).equals(material.status())) {
                out.add(at + "物料 " + material.code() + " 已停用");
            }
            var mode = row.item().getSkuMode();
            List<BomItemSku> matches = row.skus().stream()
                    .filter(s -> mode.isByColor() ? Objects.equals(s.getColorId(), sku.colorId()) : s.getColorId() == null)
                    .filter(s -> mode.isBySize() ? Objects.equals(s.getSizeCode(), sku.sizeCode()) : s.getSizeCode() == null)
                    .toList();
            if (matches.size() != 1) {
                out.add(at + "这个款色/尺码的配料不完整，请修订生产版");
            } else {
                MaterialSkuRef ref = materialsSkus.get(matches.getFirst().getMaterialSkuId());
                if (ref == null || !Objects.equals(ref.material().id(), row.item().getMaterialId())) {
                    out.add(at + "配的物料 SKU 不存在或不属于这个物料");
                } else if (strict && !ref.selectable()) {
                    out.add(at + "配的物料 SKU " + ref.code() + " 已停用");
                }
            }
            BigDecimal usage = row.item().getUsageMode() == BomUsageMode.BY_SIZE
                    ? row.sizes().stream().filter(s -> Objects.equals(s.getSizeCode(), sku.sizeCode()))
                    .map(s -> s.getUsageQty()).filter(Objects::nonNull).findFirst().orElse(null)
                    : row.item().getUsageQty();
            if (usage == null || usage.signum() <= 0) out.add(at + "这个尺码没有有效用量，请修订生产版");
        }
        return out;
    }
}
