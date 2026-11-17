package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.techpack.dto.PublishProblem;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.module.techpack.service.impl.PackOwners.Ctx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生效前检查（PRD §6.4.3、docs/api/01 §3.7）：一次列全，不通过不让生效。row 是物料清单第几行，0 说的是整个资料包。
 * <ol>
 *   <li>V2 起要写本版说明；至少一行 BOM</li>
 *   <li>随款色的行款式每个启用颜色都配了，随尺码的行每个尺码都配了，固定的行配了一个（研发 BOM 只估价，可以只选物料、不配 SKU）</li>
 *   <li>统一用量的行填了用量，分码的行每个尺码都有用量</li>
 *   <li>用到的物料、SKU 都没停用（款式上停用了的颜色的配料用不到，不查）</li>
 * </ol>
 * 只是算，不查库：调用方把这一版的 BOM、款式的颜色尺码、物料和 SKU 都给齐
 */
final class PublishChecks {

    private PublishChecks() {
    }

    static List<PublishProblem> check(TechPack pack, Ctx ctx, List<BomRow> bom, Map<Long, MaterialRef> materials,
                                      Map<Long, MaterialSkuRef> skus) {
        List<PublishProblem> problems = new ArrayList<>();
        if (pack.getVersion() >= 2 && (pack.getVersionNote() == null || pack.getVersionNote().isBlank())) {
            problems.add(new PublishProblem(0, "V2 起要写本版说明"));
        }
        if (bom.isEmpty()) {
            problems.add(new PublishProblem(0, "物料清单是空的"));
        }
        for (int i = 0; i < bom.size(); i++) {
            int row = i + 1;
            BomRow r = bom.get(i);
            MaterialRef m = materials.get(r.item().getMaterialId());
            if (m != null && !Integer.valueOf(1).equals(m.status())) {
                problems.add(new PublishProblem(row, "物料 " + m.name() + " 已停用"));
            }
            if (pack.getStage() != PackStage.DEV) {
                matched(r, ctx).forEach(msg -> problems.add(new PublishProblem(row, msg)));
            }
            usage(r.item(), r.sizes(), ctx.sizes()).forEach(msg -> problems.add(new PublishProblem(row, msg)));
            Set<Long> colorIds = Set.copyOf(ctx.colorIds());
            r.skus().stream()
                    .filter(s -> s.getColorId() == null || colorIds.contains(s.getColorId()))
                    .filter(s -> s.getSizeCode() == null || ctx.sizes().contains(s.getSizeCode()))
                    .map(s -> skus.get(s.getMaterialSkuId()))
                    .filter(s -> s != null && !Integer.valueOf(1).equals(s.status()))
                    .map(MaterialSkuRef::code).distinct()
                    .forEach(code -> problems.add(new PublishProblem(row, "物料 SKU " + code + " 已停用")));
        }
        return problems;
    }

    /** 配料配齐了没有：缺的颜色、尺码 */
    private static List<String> matched(BomRow r, Ctx ctx) {
        BomSkuMode mode = r.item().getSkuMode();
        List<BomItemSku> skus = r.skus();
        List<String> messages = new ArrayList<>();
        if (!mode.isByColor() && !mode.isBySize()) {
            if (skus.isEmpty()) {
                messages.add("还没配料");
            }
            return messages;
        }
        if (mode.isByColor()) {
            for (ColorOption color : ctx.colors()) {
                List<BomItemSku> ofColor = skus.stream().filter(s -> Objects.equals(s.getColorId(), color.id())).toList();
                if (!mode.isBySize()) {
                    if (ofColor.isEmpty()) {
                        messages.add(color.name() + "还没配料");
                    }
                    continue;
                }
                List<String> missing = missingSizes(ofColor, ctx.sizes());
                if (missing.size() == ctx.sizes().size()) {
                    messages.add(color.name() + "还没配料");
                } else if (!missing.isEmpty()) {
                    messages.add(color.name() + " " + String.join("、", missing) + " 还没配料");
                }
            }
            return messages;
        }
        List<String> missing = missingSizes(skus, ctx.sizes());
        if (!missing.isEmpty()) {
            messages.add(String.join("、", missing) + " 还没配料");
        }
        return messages;
    }

    private static List<String> missingSizes(List<BomItemSku> skus, List<String> sizes) {
        Set<String> have = skus.stream().map(BomItemSku::getSizeCode).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return sizes.stream().filter(size -> !have.contains(size)).toList();
    }

    /** 用量填了没有：统一的一个数，分码的每个尺码 */
    private static List<String> usage(BomItem item, List<BomItemSize> sizes, List<String> allSizes) {
        if (item.getUsageMode() == BomUsageMode.BY_SIZE) {
            Set<String> have = sizes.stream().filter(s -> s.getUsageQty() != null).map(BomItemSize::getSizeCode)
                    .collect(Collectors.toSet());
            List<String> missing = allSizes.stream().filter(size -> !have.contains(size)).toList();
            return missing.isEmpty() ? List.of() : List.of(String.join("、", missing) + " 码没填用量");
        }
        return item.getUsageQty() == null ? List.of("没填用量") : List.of();
    }
}
