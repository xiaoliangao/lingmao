package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.production.entity.MaterialIssueStatus;
import com.lingmao.scm.module.production.entity.ProductionNode;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 生产单的规则（docs/api/05 §2、§3.3.1，PRD §6.7） */
class ProductionRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    /** 规则（§3.3.1）：从交期往前倒排，最后一个节点完成 = 交期；天数没填（0）按 1 天 */
    @Test
    void backSchedule_endsOnDueDate() {
        List<ProductionRules.Plan> plans = ProductionRules.backSchedule(List.of(
                new ProductionRules.NodeDef("CUT", "裁剪", 2),
                new ProductionRules.NodeDef("SEW", "车缝", 5),
                new ProductionRules.NodeDef("PACK", "包装", 0)), LocalDate.of(2026, 12, 10));

        assertThat(plans).extracting(p -> p.def().code(), ProductionRules.Plan::start, ProductionRules.Plan::finish).containsExactly(
                tuple("CUT", LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 4)),
                tuple("SEW", LocalDate.of(2026, 12, 5), LocalDate.of(2026, 12, 9)),
                tuple("PACK", LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 10)));
    }

    /** 规则（PRD §6.7.2）：完成晚了是 LATE；没完成的过了计划完成 LATE，离计划完成 ≤ 2 天 WARN，报过开始 RUNNING */
    @Test
    void nodeState_byPlanAndActual() {
        assertThat(ProductionRules.nodeState(node(TODAY.minusDays(2), null, TODAY.minusDays(1)), TODAY)).isEqualTo("LATE");
        assertThat(ProductionRules.nodeState(node(TODAY.minusDays(1), null, TODAY.minusDays(1)), TODAY)).isEqualTo("DONE");
        assertThat(ProductionRules.nodeState(node(TODAY.minusDays(1), null, null), TODAY)).isEqualTo("LATE");
        assertThat(ProductionRules.lateDays(node(TODAY.minusDays(3), null, null), TODAY)).isEqualTo(3);
        assertThat(ProductionRules.nodeState(node(TODAY.plusDays(2), null, null), TODAY)).isEqualTo("WARN");
        assertThat(ProductionRules.nodeState(node(TODAY.plusDays(5), TODAY, null), TODAY)).isEqualTo("RUNNING");
        assertThat(ProductionRules.nodeState(node(TODAY.plusDays(5), null, null), TODAY)).isEqualTo("TODO");
    }

    /** 规则（§2.1，Q09）：回够总件数的 97% 算完成；回过货或报过开始是生产中；都没有是已下单。每个 SKU 最多 ⌊件数 × 1.05⌋ */
    @Test
    void byProgress_finishesAtNinetySevenPercent() {
        ProductionNode started = node(TODAY.plusDays(5), TODAY, null);
        ProductionNode idle = node(TODAY.plusDays(5), null, null);

        assertThat(ProductionRules.byProgress(List.of(item(100, 0)), List.of(idle))).isEqualTo(DocStatus.APPROVED);
        assertThat(ProductionRules.byProgress(List.of(item(100, 0)), List.of(started))).isEqualTo(DocStatus.RUNNING);
        assertThat(ProductionRules.byProgress(List.of(item(60, 30), item(40, 36)), List.of(idle))).isEqualTo(DocStatus.RUNNING);
        assertThat(ProductionRules.byProgress(List.of(item(60, 60), item(40, 37)), List.of(idle))).isEqualTo(DocStatus.DONE);
        assertThat(ProductionRules.maxPieces(100)).isEqualTo(105);
        assertThat(ProductionRules.maxPieces(33)).isEqualTo(34);
    }

    /** 规则（RFC 013 §4）：登记了裁床数的 SKU，回货上限、完成都按裁床数；最少裁床数让已回的不超出上限 */
    @Test
    void cutQty_isTheBasis() {
        ProductionOrderItem cut = item(100, 88);
        cut.setCutQty(90);
        ProductionNode idle = node(TODAY.plusDays(5), null, null);

        assertThat(ProductionRules.maxReturn(cut)).isEqualTo(94);
        assertThat(ProductionRules.maxReturn(item(100, 0))).isEqualTo(105);
        assertThat(ProductionRules.byProgress(List.of(cut), List.of(idle))).isEqualTo(DocStatus.DONE);       // 88 ≥ 90 × 97%
        assertThat(ProductionRules.byProgress(List.of(item(100, 88)), List.of(idle))).isEqualTo(DocStatus.RUNNING);
        assertThat(ProductionRules.minCut(0)).isZero();
        assertThat(ProductionRules.minCut(100)).isEqualTo(96);      // ⌊96 × 1.05⌋ = 100，⌊95 × 1.05⌋ = 99
        assertThat(ProductionRules.maxPieces(ProductionRules.minCut(105))).isGreaterThanOrEqualTo(105);
    }

    /** 规则（§3.4.1）：没有自购的料是 null；每种都领够 DONE；一点没领 NONE；其他 PARTIAL */
    @Test
    void issueStatus_byNeedAndIssued() {
        Map<Long, BigDecimal> need = Map.of(1L, new BigDecimal("10.5"), 2L, new BigDecimal("3"));

        assertThat(ProductionRules.issueStatus(Map.of(), Map.of())).isNull();
        assertThat(ProductionRules.issueStatus(need, Map.of())).isEqualTo(MaterialIssueStatus.NONE);
        assertThat(ProductionRules.issueStatus(need, Map.of(1L, new BigDecimal("10.5")))).isEqualTo(MaterialIssueStatus.PARTIAL);
        assertThat(ProductionRules.issueStatus(need, Map.of(1L, new BigDecimal("11"), 2L, new BigDecimal("3.000"))))
                .isEqualTo(MaterialIssueStatus.DONE);
    }

    /** 规则（§1.3）：报过进度、有下游的不出反审核；资料包里没有自购的料不出领料；领过料才出退料 */
    @Test
    void actions_followBlockedAndIssueStatus() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Stream.of("production:order:audit", "production:order:close", "production:node:edit", "inventory:doc:add")
                        .map(SimpleGrantedAuthority::new).toList()));
        try {
            assertThat(ProductionSupport.actions(DocStatus.APPROVED, new ProductionSupport.Ctx(false, null, false, null)))
                    .contains("unaudit", "close", "report").doesNotContain("issue", "returnMaterial");
            assertThat(ProductionSupport.actions(DocStatus.APPROVED,
                    new ProductionSupport.Ctx(false, null, true, MaterialIssueStatus.NONE)))
                    .contains("issue").doesNotContain("unaudit", "returnMaterial");
            assertThat(ProductionSupport.actions(DocStatus.DONE,
                    new ProductionSupport.Ctx(false, null, true, MaterialIssueStatus.PARTIAL)))
                    .contains("returnMaterial", "close").doesNotContain("issue", "report");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static ProductionNode node(LocalDate planFinish, LocalDate actualStart, LocalDate actualFinish) {
        ProductionNode n = new ProductionNode();
        n.setNodeName("车缝");
        n.setPlanStart(planFinish.minusDays(3));
        n.setPlanFinish(planFinish);
        n.setActualStart(actualStart);
        n.setActualFinish(actualFinish);
        return n;
    }

    private static ProductionOrderItem item(int qty, int returned) {
        ProductionOrderItem i = new ProductionOrderItem();
        i.setQty(qty);
        i.setReturnedQty(returned);
        return i;
    }
}
