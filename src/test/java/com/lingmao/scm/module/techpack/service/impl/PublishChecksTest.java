package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.techpack.dto.PublishProblem;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.BLACK;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.KHAKI;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.ctx;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.item;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.material;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.row;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.size;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;

class PublishChecksTest {

    private static final MaterialRef FABRIC = material(5, "FABRIC", "18", "1", null);

    /** 规则（PRD §6.4.3）：生产 BOM 一次列全——随款色的行少配了黑色、分码的行 L 码没填用量、V2 没写本版说明 */
    @Test
    void prod_listsAllProblemsAtOnce() {
        var byColor = row(item(5, BomSkuMode.BY_COLOR, "1.5", "3", "18", MaterialSource.PURCHASE),
                List.of(sku(KHAKI, null, 51, null)), List.of());
        var bySize = row(item(5, BomSkuMode.FIXED, null, "0", "18", MaterialSource.PURCHASE),
                List.of(sku(null, null, 51, null)), List.of(size("S", "1.2"), size("M", "1.3")));

        List<PublishProblem> problems = PublishChecks.check(pack(PackStage.PROD, 2, null), ctx(), List.of(byColor, bySize),
                Map.of(5L, FABRIC), Map.of(51L, skuRef(51, 1)));

        assertThat(problems).containsExactly(
                new PublishProblem(0, "V2 起要写本版说明"),
                new PublishProblem(1, "黑色还没配料"),
                new PublishProblem(2, "L 码没填用量"));
    }

    /** 规则：研发 BOM 只估价，可以不配 SKU；用量照样要填，物料停用照样拦 */
    @Test
    void dev_skipsMatchingButNotUsageOrDisabled() {
        MaterialRef disabled = new MaterialRef(5L, "WL5", "旧面料", "FABRIC", "METER", "METER", null, null, "PURCHASE", null, null,
                null, 0);
        var noSku = row(item(5, BomSkuMode.BY_COLOR, null, "0", "18", MaterialSource.PURCHASE), List.of(), List.of());

        List<PublishProblem> problems = PublishChecks.check(pack(PackStage.DEV, 1, null), ctx(), List.of(noSku),
                Map.of(5L, disabled), Map.of());

        assertThat(problems).extracting(PublishProblem::message)
                .containsExactly("物料 旧面料 已停用", "S、M、L 码没填用量");
    }

    /** 规则：停用的 SKU 只在用得到时拦——配给款式已经去掉的颜色（3 号）的不查 */
    @Test
    void disabledSkuOfRemovedColor_ignored() {
        var row = row(item(5, BomSkuMode.BY_COLOR, "1", "0", "18", MaterialSource.PURCHASE),
                List.of(sku(KHAKI, null, 51, null), sku(BLACK, null, 52, null), sku(3L, null, 53, null)), List.of());

        List<PublishProblem> problems = PublishChecks.check(pack(PackStage.PROD, 1, null), ctx(), List.of(row),
                Map.of(5L, FABRIC), Map.of(51L, skuRef(51, 1), 52L, skuRef(52, 0), 53L, skuRef(53, 0)));

        assertThat(problems).containsExactly(new PublishProblem(1, "物料 SKU WL5-52 已停用"));
    }

    private static TechPack pack(PackStage stage, int version, String note) {
        TechPack pack = new TechPack();
        pack.setStage(stage);
        pack.setVersion(version);
        pack.setVersionNote(note);
        return pack;
    }

    private static MaterialSkuRef skuRef(long id, int status) {
        return new MaterialSkuRef(id, "WL5-" + id, FABRIC, null, null, null, null, null, null, null, status);
    }
}
