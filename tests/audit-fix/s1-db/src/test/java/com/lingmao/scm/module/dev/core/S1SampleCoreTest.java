package com.lingmao.scm.module.dev.core;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.mapper.SampleSearch;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实业务边界的纯函数测试；数据库锁/CAS 另在 IT 用多个已提交连接验证。 */
class S1SampleCoreTest {
    @Test
    void plansBackwardsUsingFollowingNodeDurationAndKeepsBusinessDates() {
        var plans = SampleNodePlanner.plan(LocalDate.of(2026, 11, 30), defaults());
        assertThat(plans).extracting(SampleNodePlanner.Planned::planDate).containsExactly(
                LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26),
                LocalDate.of(2026, 11, 29), LocalDate.of(2026, 11, 30));
        assertThat(plans).extracting(SampleNodePlanner.Planned::durationDays).containsExactly(1, 2, 1, 3, 1);
        assertThat(SampleNodePlanner.plan(LocalDate.of(2020, 3, 8), defaults()).getLast().planDate())
                .isEqualTo(LocalDate.of(2020, 3, 8));
    }

    @Test
    void sendMeaningIsStableCodeAndDoesNotDependOnRenamedDisplayText() {
        var plans = SampleNodePlanner.plan(LocalDate.of(2026, 12, 1), List.of(
                new SampleNodePlanner.Template("SEND", "会议送审新版名称", BigDecimal.ZERO)));
        assertThat(plans.getFirst().code()).isEqualTo("SEND");
        assertThat(plans.getFirst().name()).isEqualTo("会议送审新版名称");
        assertThat(plans.getFirst().planDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void malformedNodeDictionaryCannotLeaveAnUnreviewableWorkflow() {
        LocalDate date = LocalDate.of(2026, 11, 30);
        assertBad(() -> SampleNodePlanner.plan(date, List.of()));
        assertBad(() -> SampleNodePlanner.plan(date, List.of(new SampleNodePlanner.Template("SEW", "车版", BigDecimal.ONE))));
        assertBad(() -> SampleNodePlanner.plan(date, List.of(new SampleNodePlanner.Template("SEND", "送样", BigDecimal.ONE),
                new SampleNodePlanner.Template("CUT", "裁剪", BigDecimal.ONE))));
        assertBad(() -> SampleNodePlanner.plan(date, List.of(new SampleNodePlanner.Template("SEND", "甲", BigDecimal.ONE),
                new SampleNodePlanner.Template("SEND", "乙", BigDecimal.ONE))));
        assertBad(() -> SampleNodePlanner.plan(date, List.of(new SampleNodePlanner.Template("SEND", "送样", new BigDecimal("1.5")))));
        assertBad(() -> SampleNodePlanner.plan(date, List.of(new SampleNodePlanner.Template("SEND", "送样", BigDecimal.ONE.negate()))));
        List<SampleNodePlanner.Template> tooMany = IntStream.range(0, 101)
                .mapToObj(i -> new SampleNodePlanner.Template(i == 100 ? "SEND" : "N" + i, "节点" + i, BigDecimal.ONE)).toList();
        assertBad(() -> SampleNodePlanner.plan(date, tooMany));
    }

    @Test
    void totalQuantityIsPositiveBoundedAndIndependentOfSelectedMatrixArea() {
        assertThat(SampleRules.quantity(1)).isEqualTo(1);
        assertThat(SampleRules.quantity(99_999_999)).isEqualTo(99_999_999);
        assertThat(SampleRules.combinations(3, 5)).isEqualTo(15);
        assertThat(SampleRules.combinations(100, 100)).isEqualTo(10_000);
        assertBad(() -> SampleRules.quantity(0)); assertBad(() -> SampleRules.quantity(-1));
        assertBad(() -> SampleRules.quantity(100_000_000)); assertBad(() -> SampleRules.combinations(101, 1));
    }

    @Test
    void colorAndSizeSelectionsRejectUnknownMembersDuplicatesAndEmptySets() {
        assertThat(SampleRules.colorIds(List.of(3L, 1L), List.of(1L, 2L, 3L))).containsExactly(3L, 1L);
        assertThat(SampleRules.sizeCodes(List.of("M", "XL"), List.of("S", "M", "L", "XL"))).containsExactly("M", "XL");
        assertBad(() -> SampleRules.colorIds(List.of(2L), List.of(1L)));
        assertBad(() -> SampleRules.colorIds(List.of(1L, 1L), null));
        assertBad(() -> SampleRules.sizeCodes(List.of(), null));
        assertBad(() -> SampleRules.sizeCodes(List.of(" M"), List.of("M")));
        assertBad(() -> SampleRules.sizeCodes(List.of("XXL"), List.of("M")));
    }

    @Test
    void nextRevisionKeepsOldSelectionOrderAndReportsRemovedColorsOrExplicitFallback() {
        var partial = SampleRules.revisionColors(List.of(3L, 1L, 2L), List.of(2L, 3L, 4L));
        assertThat(partial.colorIds()).containsExactly(3L, 2L); assertThat(partial.removedIds()).containsExactly(1L);
        assertThat(partial.usedFallback()).isFalse();
        var replaced = SampleRules.revisionColors(List.of(1L, 2L), List.of(4L, 3L));
        assertThat(replaced.colorIds()).containsExactly(4L); assertThat(replaced.removedIds()).containsExactly(1L, 2L);
        assertThat(replaced.usedFallback()).isTrue();
        var unchanged = SampleRules.revisionColors(List.of(3L, 2L), List.of(1L, 2L, 3L));
        assertThat(unchanged.colorIds()).containsExactly(3L, 2L); assertThat(unchanged.removedIds()).isEmpty();
        assertThat(unchanged.usedFallback()).isFalse();
    }

    @Test
    void decimalPricesKeepUnknownNullAndRejectRoundingNegativeOrOverflow() {
        assertThat(SampleRules.price(null, 4, "预估成本")).isNull();
        assertThat(SampleRules.price(new BigDecimal("0.10").add(new BigDecimal("0.20")), 2, "吊牌价"))
                .isEqualByComparingTo("0.30");
        assertThat(SampleRules.price(BigDecimal.ZERO, 4, "成本")).isEqualByComparingTo("0.0000");
        assertBad(() -> SampleRules.price(new BigDecimal("1.001"), 2, "吊牌价"));
        assertBad(() -> SampleRules.price(new BigDecimal("-0.0001"), 4, "成本"));
        assertBad(() -> SampleRules.price(new BigDecimal("99999999999.0000"), 4, "成本"));
    }

    @Test
    void reviewerSetKeepsFirstOrderAndBoundsTheRawRequestBeforeDeduplication() {
        assertThat(SampleRules.reviewerIds(List.of(2L, 1L, 2L))).containsExactly(2L, 1L);
        var repeated = new ArrayList<Long>(); for (int i = 0; i < 101; i++) repeated.add(1L);
        assertBad(() -> SampleRules.reviewerIds(repeated));
        assertBad(() -> SampleRules.reviewerIds(List.of()));
        assertBad(() -> SampleRules.reviewerIds(IntStream.rangeClosed(1, 101).mapToObj(Long::valueOf).toList()));
    }

    @Test
    void twoStateMachinesRequireARealTerminalReviewAndANewRevision() {
        assertThat(SampleStates.ORDER.next("dispatch", SampleOrderStatus.WAIT_ASSIGN)).isEqualTo(SampleOrderStatus.MAKING);
        assertThat(SampleStates.ORDER.next("send", SampleOrderStatus.MAKING)).isEqualTo(SampleOrderStatus.WAIT_REVIEW);
        assertThat(SampleStates.SAMPLE.next("reopen", SampleStatus.PASSED)).isEqualTo(SampleStatus.DEVELOPING);
        SampleStates.requirePair(SampleStatus.DEVELOPING, SampleOrderStatus.WAIT_ASSIGN);
        SampleStates.requirePair(SampleStatus.CONVERTED, SampleOrderStatus.PASSED);
        assertThatThrownBy(() -> SampleStates.requirePair(SampleStatus.DEVELOPING, SampleOrderStatus.REVISE))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(1002));
        assertThatThrownBy(() -> SampleStates.ORDER.next("pass", SampleOrderStatus.MAKING)).isInstanceOf(BizException.class);
    }

    @Test
    void businessDateValidationNeverConvertsToUtcAndRejectsOnlyFutureCompletion() {
        LocalDate today = LocalDate.of(2026, 11, 22);
        assertThat(SampleRules.notFuture(today, today, "完成日").toString()).isEqualTo("2026-11-22");
        assertThat(SampleRules.notFuture(today.minusDays(1), today, "完成日")).isEqualTo(today.minusDays(1));
        assertBad(() -> SampleRules.notFuture(today.plusDays(1), today, "完成日"));
    }

    @Test
    void impossibleSingleKeywordCannotSilentlyBecomeAnUnfilteredQuery() {
        assertThat(search("x".repeat(101)).isImpossibleKeyword()).isTrue();
        assertThat(search(" ".repeat(200) + "YY26110001").isImpossibleKeyword()).isFalse();
        assertThat(search("YY26110001,DY202611220001").isImpossibleKeyword()).isFalse();
        assertBad(() -> search("x".repeat(8193)).isImpossibleKeyword());
    }

    private static SampleSearch search(String keyword) {
        return new SampleSearch(keyword, null, null, null, null, null, null, null, null,
                null, false, null, null, null, null, null);
    }

    private static List<SampleNodePlanner.Template> defaults() {
        return List.of(new SampleNodePlanner.Template("RECEIVE", "接单", BigDecimal.ONE),
                new SampleNodePlanner.Template("PATTERN", "纸样", new BigDecimal("2")),
                new SampleNodePlanner.Template("CUT", "裁剪", BigDecimal.ONE),
                new SampleNodePlanner.Template("SEW", "车版", new BigDecimal("3")),
                new SampleNodePlanner.Template("SEND", "送样", BigDecimal.ONE));
    }

    private static void assertBad(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    }
}
