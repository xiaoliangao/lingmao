package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 上游变更提醒在真库上（V19）：处理是条件更新，两个人同时点只有一个成功；别的单据的 id 处理不了；待处理计数跟着变 */
@IntegrationTest
class DocNoticeServiceImplIT {

    @Autowired
    private DocNoticeService docNoticeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate tx;

    private long docId;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM biz_doc_notice WHERE doc_type = 'PRODUCTION_ORDER' AND doc_id = ?", docId);
    }

    /** 规则：3 个人同时处理同一条，只有一个成功，记下的是他和他写的说明；其余 1002。处理完待处理数从 1 变 0 */
    @Test
    void concurrentHandle_onlyOneWins() throws Exception {
        docId = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        docNoticeService.add(BizType.PRODUCTION_ORDER, docId, BizType.ORDER_CHANGE, 1L, "黑色 M 码从 100 改成 80", 900400L);
        long noticeId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_doc_notice WHERE doc_type = 'PRODUCTION_ORDER' AND doc_id = ?", Long.class, docId);
        assertThat(docNoticeService.countPending(BizType.PRODUCTION_ORDER, docId)).isEqualTo(1);

        int threads = 3;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                long userId = 900400L + i;
                futures.add(pool.submit(() -> {
                    login(userId);
                    start.await();
                    try {
                        tx.executeWithoutResult(s -> {
                            docNoticeService.handle(BizType.PRODUCTION_ORDER, docId, noticeId, "改了生产单 " + userId);
                            sleep();
                        });
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
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM biz_doc_notice WHERE id = ?", noticeId);
            assertThat(row.get("handled_by")).isEqualTo(winners.getFirst());
            assertThat(row.get("handle_remark")).isEqualTo("改了生产单 " + winners.getFirst());
            assertThat(row.get("handled_time")).isNotNull();
            assertThat(docNoticeService.countPending(BizType.PRODUCTION_ORDER, docId)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    /** 规则：路径上的单据 id 和提醒对不上（拿能看的单据去处理别的单据的提醒）→ 404，提醒原样没处理 */
    @Test
    void handleWithAnotherDocId_404_untouched() {
        docId = ThreadLocalRandom.current().nextLong(900_000_000L, 999_999_999L);
        docNoticeService.add(BizType.PRODUCTION_ORDER, docId, BizType.ORDER_CHANGE, 1L, "交期提前 3 天", null);
        long noticeId = jdbcTemplate.queryForObject(
                "SELECT id FROM biz_doc_notice WHERE doc_type = 'PRODUCTION_ORDER' AND doc_id = ?", Long.class, docId);
        login(900500L);

        BizException e = catchThrowableOfType(BizException.class,
                () -> docNoticeService.handle(BizType.PRODUCTION_ORDER, docId + 1, noticeId, null));

        assertThat(e.getCode()).isEqualTo(404);
        assertThat(docNoticeService.countPending(BizType.PRODUCTION_ORDER, docId)).isEqualTo(1);
    }

    private static void login(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "it"), null, List.of()));
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
