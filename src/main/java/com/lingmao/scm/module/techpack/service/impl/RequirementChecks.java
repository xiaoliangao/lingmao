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

/** RFC010 §4.2：生效时检查全集，使用时按实际 SKU 检查，锁定的旧版允许 VOIDED。 */
final class RequirementChecks {
    private RequirementChecks() { }

    static List<String> check(TechPack pack, StyleOrderInfo style, StyleOrderInfo.Sku sku,
                              List<PackContent.BomRow> bom, Map<Long, MaterialRef> materials,
                              Map<Long, MaterialSkuRef> materialsSkus) {
        List<String> out = new ArrayList<>();
        String prefix = sku.code() + "：";
        if (style.status() == StyleStatus.DISABLED || !sku.enabled()
                || style.colors().stream().noneMatch(c -> c.id() == sku.colorId() && c.enabled())) {
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
            if (material == null || !Integer.valueOf(1).equals(material.status())) {
                out.add(at + "物料不存在或已停用");
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
                if (ref == null || !ref.selectable() || !Objects.equals(ref.material().id(), row.item().getMaterialId())) {
                    out.add(at + "配料 SKU 不存在、已停用或不属于该物料");
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
