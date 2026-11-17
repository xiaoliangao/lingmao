package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.techpack.entity.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequirementChecksTest {
    private final StyleOrderInfo.Sku redM = new StyleOrderInfo.Sku(11, 1, "M", "STYLE-RED-M", true);
    private final StyleOrderInfo.Sku redL = new StyleOrderInfo.Sku(12, 1, "L", "STYLE-RED-L", true);
    private final StyleOrderInfo.Sku blackL = new StyleOrderInfo.Sku(13, 2, "L", "STYLE-BLACK-L", true);
    private final StyleOrderInfo style = new StyleOrderInfo(1, "STYLE", "款式", null, null, StyleStatus.ENABLED, null,
            List.of("M", "L"), List.of(new StyleOrderInfo.Color(1, "RED", "红", null, true),
            new StyleOrderInfo.Color(2, "BLACK", "黑", null, true)), List.of(redM, redL, blackL), null, null);

    private TechPack pack() {
        TechPack p = new TechPack(); p.setStyleId(1L); p.setStage(PackStage.PROD); p.setStatus(PackStatus.VOIDED); return p;
    }
    private List<String> check(TechPack p, StyleOrderInfo.Sku sku, PackContent.BomRow row, int enabled) {
        var m = material(2, "FABRIC", "5", "1", null);
        var ms = new MaterialSkuRef(21L, "MAT-M", m, null, null, null, null, null, BigDecimal.ONE, null, enabled);
        return RequirementChecks.check(p, style, sku, List.of(row), Map.of(2L, m), Map.of(21L, ms));
    }

    @Test void actualPairsOnly_noColorSizeCartesianProduct() {
        var row = row(item(2, BomSkuMode.BY_COLOR_SIZE, "1", "0", "5", MaterialSource.PURCHASE),
                List.of(sku(1L, "M", 21, "5"), sku(2L, "L", 21, "5")), List.of());
        assertThat(check(pack(), redM, row, 1)).isEmpty();
        assertThat(check(pack(), blackL, row, 1)).isEmpty();
        assertThat(check(pack(), redL, row, 1)).anyMatch(s -> s.contains("STYLE-RED-L") && s.contains("配料不完整"));
    }

    @Test void missingSizeUsageAndDisabledMaterialSku_reportSpecificRow() {
        var row = row(item(2, BomSkuMode.FIXED, null, "0", "5", MaterialSource.PURCHASE),
                List.of(sku(null, null, 21, "5")), List.of(size("M", "1")));
        assertThat(check(pack(), redL, row, 0)).anyMatch(s -> s.contains("BOM 第 1 行") && s.contains("已停用"))
                .anyMatch(s -> s.contains("没有有效用量"));
    }

    @Test void legacyUniformAcceptsNewSize_butFrozenVersionRequiresRevision() {
        var row = row(item(2, BomSkuMode.FIXED, "1", "0", "5", MaterialSource.PURCHASE),
                List.of(sku(null, null, 21, "5")), List.of());
        TechPack p = pack();
        assertThat(check(p, redL, row, 1)).isEmpty();
        p.setOwnerSnapshot(new PackOwnerSnapshot(1, owner(null), List.of("M"), "M", ctx().colors()));
        assertThat(check(p, redL, row, 1)).anyMatch(s -> s.contains("生效时的色码范围"));
        assertThat(check(p, redM, row, 1)).isEmpty();
    }
}
