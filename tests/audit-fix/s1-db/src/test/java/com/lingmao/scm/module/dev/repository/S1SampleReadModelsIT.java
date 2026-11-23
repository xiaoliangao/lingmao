package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实历史头授权与 Home 权限前置/总数解耦，不用数据库无关的假 Mapper 返回替代。 */
@IntegrationTest
class S1SampleReadModelsIT extends SampleTestSupport {
    @Autowired SqlSessionFactory sessions;

    @Test
    void historicalHeadBatchesUseCurrentParentScopeAndRejectAnyHiddenOrMixedCompanyId() throws Exception {
        Fixture f = fixture(); Fixture hiddenOwner = hidden(f);
        Created visible = draft(f); dispatch(f, visible.sampleId(), MakeWay.INTERNAL); send(f, visible.sampleId());
        review(f, visible.sampleId(), SampleReviewResult.REVISE);
        long reviewId = jdbc.queryForObject("SELECT id FROM dev_sample_review WHERE sample_order_id=?", Long.class, visible.orderId());
        Created hidden = draft(hiddenOwner); dispatch(hiddenOwner, hidden.sampleId(), MakeWay.INTERNAL); send(hiddenOwner, hidden.sampleId());
        review(hiddenOwner, hidden.sampleId(), SampleReviewResult.PASS);
        long hiddenReview = jdbc.queryForObject("SELECT id FROM dev_sample_review WHERE sample_order_id=?", Long.class, hidden.orderId());
        login(f.maker(), false);
        assertThat(samples.orderIdentity(f.company(), visible.orderId(), DataScope.current(false)).getSampleVersion()).isEqualTo(1);
        assertThat(samples.reviewIdentity(f.company(), reviewId, DataScope.current(false)).getSampleOrderId()).isEqualTo(visible.orderId());
        assertThat(samples.sampleIdentities(f.company(), DataScope.current(false), List.of(visible.sampleId(), visible.sampleId()))).hasSize(1);
        denied(404, () -> samples.sampleIdentities(f.company(), DataScope.current(false), List.of(visible.sampleId(), hidden.sampleId())));
        denied(404, () -> samples.orderIdentities(f.company(), DataScope.current(false), List.of(visible.orderId(), hidden.orderId())));
        denied(404, () -> samples.reviewIdentities(f.company(), DataScope.current(false), List.of(reviewId, hiddenReview)));
        denied(404, () -> samples.orderIdentity(f.otherCompany(), visible.orderId(), DataScope.current(false)));
        denied(404, () -> samples.reviewIdentity(f.company(), Long.MAX_VALUE, DataScope.current(false)));
        denied(400, () -> samples.sampleIdentities(f.company(), DataScope.current(false), Collections.nCopies(101, visible.sampleId())));
        denied(400, () -> samples.orderIdentities(f.company(), DataScope.current(false), Collections.nCopies(101, visible.orderId())));
        denied(400, () -> samples.reviewIdentities(f.company(), DataScope.current(false), Collections.nCopies(101, reviewId)));
        assertThat(samples.sampleIdentities(f.company(), DataScope.current(false), List.of())).isEmpty();
        assertThat(samples.orderIdentities(f.company(), DataScope.current(false), List.of())).isEmpty();
        assertThat(samples.reviewIdentities(f.company(), DataScope.current(false), List.of())).isEmpty();
        login(f.creator(), true);
        var current = snapshot(f, visible.sampleId());
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.company(), visible.sampleId(), DataScope.current(false), current.sample().getVersion(), current.order().getVersion());
            locked.order().setMakerId(f.otherUser()); samples.updateOrder(locked, locked.order()); samples.touchSample(locked);
        });
        login(f.maker(), false);
        denied(404, () -> samples.orderIdentity(f.company(), visible.orderId(), DataScope.current(false)));
        denied(404, () -> samples.reviewIdentity(f.company(), reviewId, DataScope.current(false)));
        evidence("historical-header-authorization.json", Map.of("historicalOrder", visible.orderId(), "historicalReview", reviewId,
                "currentBusinessVersion", 2, "formerMakerAfterReassignment", 404, "mixedHiddenBatch", 404, "raw101Ids", 400));
    }

    @Test
    void homeReturnsOldestTwentyVisibleRowsWithIndependentActualCountAndOwnerFiltering() throws Exception {
        Fixture f = fixture(); Fixture hiddenOwner = hidden(f); LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < 3; i++) {
            late(hiddenOwner, today.minusDays(120 - i)); waitingReview(hiddenOwner, today.minusDays(120 - i));
        }
        var late = new ArrayList<Created>(); var review = new ArrayList<Created>();
        for (int i = 0; i < 21; i++) {
            late.add(late(f, today.minusDays(60 - i))); review.add(waitingReview(f, today.minusDays(60 - i)));
        }
        login(f.creator(), false);
        var capture = capture(); capture.start();
        List<S1SamplePlansIT.Executed> sql;
        List<com.lingmao.scm.module.dev.mapper.SampleRow> oldest;
        long total;
        try {
            oldest = samples.homePreview(f.company(), DataScope.current(false), SampleRepository.HomeKind.LATE, f.maker(), 20);
            total = samples.homeCount(f.company(), DataScope.current(false), SampleRepository.HomeKind.LATE, f.maker());
        } finally { sql = capture.finish(); }
        assertThat(total).isEqualTo(21);
        assertThat(oldest).extracting(row -> row.getId()).containsExactlyElementsOf(late.subList(0, 20).stream().map(Created::sampleId).toList());
        assertThat(oldest).extracting(row -> row.getHomeRankDate()).isSorted();
        assertThat(sql).hasSize(2); // 一条有界预览、一条COUNT，不先全量读再过滤。
        assertThat(sql.getFirst().sql()).contains("home_rank_date", "LIMIT");
        assertThat(sql.getFirst().parameters()).contains(f.company(), f.creator(), f.maker(), 20);
        assertThat(sql.getLast().sql()).contains("count(*)").doesNotContain("LIMIT ?");
        var oldestReview = samples.homePreview(f.company(), DataScope.current(false), SampleRepository.HomeKind.REVIEW, f.designer(), 20);
        assertThat(oldestReview).extracting(row -> row.getId()).containsExactlyElementsOf(review.subList(0, 20).stream().map(Created::sampleId).toList());
        assertThat(samples.homeCount(f.company(), DataScope.current(false), SampleRepository.HomeKind.REVIEW, f.designer())).isEqualTo(21);
        assertThat(samples.homeCount(f.company(), DataScope.current(false), SampleRepository.HomeKind.REVIEW, f.maker())).isZero();
        assertThat(samples.homeCount(f.company(), DataScope.current(false), SampleRepository.HomeKind.LATE, f.designer())).isZero();
        assertThat(samples.homeCount(f.otherCompany(), DataScope.current(false), SampleRepository.HomeKind.LATE, null)).isZero();
        denied(400, () -> samples.homePreview(f.company(), DataScope.current(false), SampleRepository.HomeKind.REVIEW, null, 21));
        var plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql.getFirst().sql(), String.class, sql.getFirst().parameters().toArray());
        evidence("home-visible-oldest-20.json", Map.of("hiddenOlderRowsPerGroup", 3, "visibleRowsPerGroup", 21, "previewSize", oldest.size(),
                "total", total, "executed", sql, "explain", plan));
    }

    private Created late(Fixture f, LocalDate firstPlan) {
        Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL);
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 1, 1);
            var plans = samples.lockNodes(locked);
            for (int i = 0; i < plans.size(); i++) plans.get(i).setPlanDate(firstPlan.plusDays(i));
            samples.updatePlans(locked, plans); samples.updateOrder(locked, locked.order()); samples.touchSample(locked);
        });
        return made;
    }

    private Created waitingReview(Fixture f, LocalDate sent) {
        Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL);
        transactions.executeWithoutResult(status -> {
            var locked = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 1, 1);
            long send = samples.lockNodes(locked).stream().filter(n -> n.getNodeCode().equals("SEND")).findFirst().orElseThrow().getId();
            samples.setDone(locked, send, sent); locked.order().setStatus(SampleOrderStatus.WAIT_REVIEW);
            samples.updateOrder(locked, locked.order()); samples.touchSample(locked);
        });
        return made;
    }

    private S1SamplePlansIT.Capture capture() {
        var configuration = sessions.getConfiguration();
        synchronized (configuration) {
            for (var interceptor : configuration.getInterceptors()) if (interceptor instanceof S1SamplePlansIT.Capture found) return found;
            var made = new S1SamplePlansIT.Capture(configuration); configuration.addInterceptor(made); return made;
        }
    }

    private static Fixture hidden(Fixture f) {
        return new Fixture(f.company(), f.otherCompany(), f.category(), f.sizeGroup(), f.otherUser(), f.otherUser(), f.otherUser(),
                f.creator(), f.factory(), f.colors());
    }

    private static void denied(int code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
