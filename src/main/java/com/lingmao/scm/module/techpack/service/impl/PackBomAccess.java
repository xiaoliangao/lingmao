package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.BomItemForm;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;
import com.lingmao.scm.security.FieldPermissionDenied;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 物料清单开发、生产分工（RFC 012 §3，甲方 Q49 选 B；11-21 改成主料和工艺开发填、辅料生产填）：物料类型定这一行归谁填，
 * 字典 material_type 的 text_value 写 PROD 的归生产，其余（DEV、没填）归开发。只能填一块的人，另一块的行不能加、删、换物料、改部位用量配料，改了 403。
 * 按服务端认出的行身份（明细 id）比对，做法和看不见价格的人改不了价格一样（PackFinancialAccess）；价格归看成本权限管，这里不比
 */
@Component
@RequiredArgsConstructor
class PackBomAccess {

    static final String DEV = "techpack:bom:dev";
    static final String PROD = "techpack:bom:prod";
    static final String OWNER_PROD = "PROD";

    private final DictDataService dictDataService;
    private final MaterialService materialService;

    /** 归生产填的物料类型 */
    Set<String> prodTypes() {
        return dictDataService.listByType("material_type").stream()
                .filter(o -> OWNER_PROD.equals(o.textValue())).map(DictOption::value).collect(Collectors.toSet());
    }

    /**
     * 保存草稿前查：forms 和 submitted 一一对应（PackForms.toContent 按顺序转的），loadOld 取库里这一版现在的物料清单。
     * 两样权限都有的不查
     */
    void check(List<BomItemForm> forms, List<BomRow> submitted, Supplier<List<BomRow>> loadOld) {
        boolean dev = LoginUser.hasAuthority(DEV);
        boolean prod = LoginUser.hasAuthority(PROD);
        if (dev && prod) {
            return;
        }
        List<BomRow> old = loadOld.get();
        Set<String> prodTypes = prodTypes();
        Set<Long> materialIds = Stream.concat(submitted.stream(), old.stream()).map(r -> r.item().getMaterialId()).collect(Collectors.toSet());
        Map<Long, String> types = materialIds.isEmpty() ? Map.of() : materialService.materialRefs(materialIds).stream()
                .collect(Collectors.toMap(MaterialRef::id, m -> Objects.requireNonNullElse(m.materialType(), "")));
        Function<BomRow, Boolean> mine = r -> prodTypes.contains(types.get(r.item().getMaterialId())) ? prod : dev;

        Map<Long, BomRow> before = new HashMap<>();
        old.forEach(r -> before.put(r.item().getId(), r));
        Set<Long> kept = new HashSet<>();
        for (int i = 0; i < submitted.size(); i++) {
            BomRow now = submitted.get(i);
            Long id = forms.get(i).getId();
            BomRow was = id == null ? null : before.get(id);
            if (was != null) {
                kept.add(id);
            }
            if (mine.apply(now) && (was == null || mine.apply(was))) {
                continue;
            }
            if (was == null || !same(was, now)) {
                throw denied(prod);
            }
        }
        for (BomRow r : old) {
            if (!kept.contains(r.item().getId()) && !mine.apply(r)) {
                throw denied(prod);
            }
        }
    }

    private static FieldPermissionDenied denied(boolean prod) {
        return new FieldPermissionDenied(prod ? "填物料清单开发的行（主料和工艺）的" : "填物料清单生产的行（辅料）的");
    }

    /** 除了价格，行上能改的都一样（部位、物料、配料方式和配到哪些 SKU、用量、损耗、来源、供应商、备注） */
    static boolean same(BomRow a, BomRow b) {
        var x = a.item();
        var y = b.item();
        return Objects.equals(x.getPart(), y.getPart()) && Objects.equals(x.getMaterialId(), y.getMaterialId())
                && x.getSkuMode() == y.getSkuMode() && x.getUsageMode() == y.getUsageMode()
                && sameNum(x.getUsageQty(), y.getUsageQty()) && sameNum(x.getLossPct(), y.getLossPct())
                && x.getSource() == y.getSource() && Objects.equals(x.getSupplierId(), y.getSupplierId())
                && Objects.equals(x.getRemark(), y.getRemark())
                && skuCells(a.skus()).equals(skuCells(b.skus())) && sizeUsages(a.sizes()).equals(sizeUsages(b.sizes()));
    }

    private static boolean sameNum(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Set<List<Object>> skuCells(List<BomItemSku> skus) {
        return skus.stream().map(s -> java.util.Arrays.<Object>asList(s.getColorId(), s.getSizeCode(), s.getMaterialSkuId()))
                .collect(Collectors.toSet());
    }

    private static Map<String, BigDecimal> sizeUsages(List<BomItemSize> sizes) {
        Map<String, BigDecimal> out = new HashMap<>();
        sizes.forEach(s -> out.put(s.getSizeCode(), s.getUsageQty() == null ? null : s.getUsageQty().stripTrailingZeros()));
        return out;
    }
}
