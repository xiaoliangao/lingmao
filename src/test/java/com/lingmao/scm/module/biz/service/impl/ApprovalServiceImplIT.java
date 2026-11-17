package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.entity.ApprovalStepStatus;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 多级审批在真库上（V19，架构 §4.8、§13"状态并发"）：同一级几个人同时点，只有一个成功、其余 1002；审批流按枚举名存。
 * 金额传 0，不会碰上别的测试建的规则（规则门槛 ≥ 0，金额要大于门槛才加级），只有第 1 级。每个测试用随机的 doc_id，测完删掉
 */
@IntegrationTest
class ApprovalServiceImplIT {

    private static final String PO_AUDIT = "purchase:order:audit";

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate tx;

    private long docId;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM biz_approval_step WHERE biz_type = 'PURCHASE_ORDER' AND doc_id = ?", docId);
    }

    /**
     * 规则：4 个有审核权限的人同时审第 1 级，只有一个成功（返回 true：只有一级，审完了），其余 1002。
     * 每个人一个事务，改完先不提交、停一会儿：后来的人等这一行的锁，提交以后重新判断 status = 'CURRENT'，已经不成立了
     */
    @Test
    void concurrentPass_onlyOneWins() throws Exception {
        docId = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        approvalService.start(BizType.PURCHASE_ORDER, docId, BigDecimal.ZERO);
        int threads = 4;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                long userId = 900200L + i;
                futures.add(pool.submit(() -> {
                    login(userId, PO_AUDIT);
                    start.await();
                    try {
                        Boolean done = tx.execute(s -> {
                            boolean all = approvalService.pass(BizType.PURCHASE_ORDER, docId);
                            sleep();
                            return all;
                        });
                        assertThat(done).isTrue();
                        return userId;
                    } catch (BizException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);
                        return null;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            List<Long> winners = new ArrayList<>();
            for (Future<Long> future : futures) {
                Long winner = future.get();
                if (winner != null) {
                    winners.add(winner);
                }
            }

            assertThat(winners).hasSize(1);
            assertThat(jdbcTemplate.queryForList(
                    "SELECT status, pass_by FROM biz_approval_step WHERE biz_type = 'PURCHASE_ORDER' AND doc_id = ?", docId))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.get("status")).isEqualTo("PASSED");
                        assertThat(row.get("pass_by")).isEqualTo(winners.getFirst());
                    });
        } finally {
            pool.shutdownNow();
        }
    }

    /** 规则：驳回（cancel）以后再审 → 1002；重新提交是第 2 轮、从第 1 级起，旧的一轮留着 */
    @Test
    void cancelThenPass_1002_resubmitNewRound() {
        docId = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        approvalService.start(BizType.PURCHASE_ORDER, docId, BigDecimal.ZERO);
        approvalService.cancel(BizType.PURCHASE_ORDER, docId);
        login(900300L, PO_AUDIT);

        assertThat(catchThrowableOfType(BizException.class,
                () -> approvalService.pass(BizType.PURCHASE_ORDER, docId)).getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);

        approvalService.start(BizType.PURCHASE_ORDER, docId, BigDecimal.ZERO);
        ApprovalView view = approvalService.view(BizType.PURCHASE_ORDER, docId);

        assertThat(view.round()).isEqualTo(2);
        assertThat(view.levels()).singleElement().satisfies(l -> assertThat(l.status()).isEqualTo(ApprovalStepStatus.CURRENT));
        assertThat(view.waitingFor()).isEqualTo("第 1 级 · 有审核权限的人");
        assertThat(view.canPass()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM biz_approval_step WHERE biz_type = 'PURCHASE_ORDER' AND doc_id = ? AND status = 'CANCELLED'
                """, Integer.class, docId)).isEqualTo(1);
    }

    /** 规则："待我审核"只列当前这一级轮到我的：有第 1 级的审核权限就有它，没有就没有；审完了谁都没有 */
    @Test
    void waitingForMe_followsCurrentLevel() {
        docId = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        approvalService.start(BizType.PURCHASE_ORDER, docId, BigDecimal.ZERO);

        login(900600L);
        assertThat(approvalService.waitingForMe(BizType.PURCHASE_ORDER)).doesNotContain(docId);
        login(900600L, PO_AUDIT);
        assertThat(approvalService.waitingForMe(BizType.PURCHASE_ORDER)).contains(docId);

        approvalService.pass(BizType.PURCHASE_ORDER, docId);
        assertThat(approvalService.waitingForMe(BizType.PURCHASE_ORDER)).doesNotContain(docId);
    }

    private static void login(long userId, String... perms) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "it"), null, Arrays.stream(perms).map(SimpleGrantedAuthority::new).toList()));
    }

    /** 成功的那个事务多拿一会儿行锁，让别的线程确实撞上锁、走"等锁 → 重新判断条件"那条路 */
    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
