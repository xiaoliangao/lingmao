package com.lingmao.scm.common.doc;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.security.LoginUser;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 比较并交换在真库上（架构 §4.2、§13"状态并发"）：几个人同时审同一张单，只有一个成功，其余 1002；
 * 传空实体时修改人、修改时间照样自动填；状态按枚举名存。
 * 还没有带状态的业务表，测试里临时建一张 it_status_doc，测完删掉；它的实体和 Mapper 只在测试里
 */
@IntegrationTest
class StatusCasIT {

    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2000-01-01T00:00:00+08:00");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ItDocMapper mapper;

    @BeforeEach
    void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE it_status_doc (
                    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    status      VARCHAR(16) NOT NULL,
                    remark      VARCHAR(500),
                    create_by   BIGINT,
                    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
                    update_by   BIGINT,
                    update_time TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    @AfterEach
    void dropTable() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.execute("DROP TABLE IF EXISTS it_status_doc");
    }

    /**
     * 规则：8 个人同时把同一张"待审核"审成"已审核"，只有一个成功，其余 7 个 1002。
     * 每个人一个事务，改完先不提交、停一会儿：后来的人等这一行的锁，等到提交以后 PostgreSQL 重新判断 status = 'PENDING'，
     * 已经不成立了，改 0 行。修改人是成功的那个人，备注没被碰（空实体不往 SET 里放字段）
     */
    @Test
    void concurrentAudit_onlyOneWins() throws Exception {
        long id = insert(DocStatus.PENDING);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                long userId = 900100L + i;
                futures.add(pool.submit(() -> {
                    login(userId);
                    start.await();
                    try {
                        tx.executeWithoutResult(s -> {
                            StatusCas.update(mapper, new ItDoc(),
                                    StatusCas.of(ItDoc::getId, id, ItDoc::getStatus, DocStatus.PENDING, DocStatus.APPROVED));
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
            Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM it_status_doc WHERE id = ?", id);
            assertThat(row.get("status")).isEqualTo("APPROVED");
            assertThat(row.get("update_by")).isEqualTo(winners.getFirst());
            assertThat(row.get("remark")).isEqualTo("原来的备注");
            assertThat(mapper.selectById(id).getUpdateTime()).isAfter(LONG_AGO.plusDays(1));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 规则：状态已经不是查出来时的那个（别人先审了），改 0 行、1002，这一行一点没动 */
    @Test
    void staleStatus_throws1002AndChangesNothing() {
        long id = insert(DocStatus.APPROVED);

        assertThatThrownBy(() -> StatusCas.update(mapper, new ItDoc(),
                StatusCas.of(ItDoc::getId, id, ItDoc::getStatus, DocStatus.PENDING, DocStatus.APPROVED)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
        assertThat(mapper.selectById(id).getUpdateTime()).isAtSameInstantAs(LONG_AGO);
    }

    /** 插一行，修改时间拨到很久以前：改成功了它会变成现在，没改它不动 */
    private long insert(DocStatus status) {
        ItDoc doc = new ItDoc();
        doc.setStatus(status);
        doc.setRemark("原来的备注");
        mapper.insert(doc);
        jdbcTemplate.update("UPDATE it_status_doc SET update_time = ? WHERE id = ?", LONG_AGO, doc.getId());
        return doc.getId();
    }

    private static void login(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "it"), null, List.of()));
    }

    /** 成功的那个事务多拿一会儿行锁，让别的线程确实撞上锁、走"等锁 → 重新判断条件"那条路 */
    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("it_status_doc")
    public static class ItDoc extends AuditEntity {
        @TableField(updateStrategy = FieldStrategy.NEVER)
        private DocStatus status;
        private String remark;
    }

    /** 只在测试里的 Mapper：@Mapper 在 com.lingmao.scm 下，集成测试起应用时会被扫到，表在测试开始时才建，不影响启动 */
    @Mapper
    public interface ItDocMapper extends BaseMapper<ItDoc> {
    }
}
