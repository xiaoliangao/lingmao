package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 提醒派生摘要的短批次维护入口。启动仅创建状态行，不自动扫描来源或启用摘要。
 * 每个维护动作自成 READ_COMMITTED 短事务，不能加入一个覆盖整库回填的外层事务。
 * 所有 namespace 来自部署配置；状态不缓存，回退在后续请求立即生效。
 * 部署运维可通过 notice-summary.sql 调用同一组函数，无需开放写管理接口。
 */
@Repository
@DependsOn("deploymentNamespaceGuard")
public class NoticeSummaryMaintenance implements InitializingBean {
    private static final int MAX_BATCH = 500;
    private static final RowMapper<BatchProgress> BATCH = (rs, row) ->
            new BatchProgress(rs.getInt("processed"), rs.getBoolean("complete"), rs.getLong("mismatches"));
    private static final RowMapper<State> STATE = (rs, row) -> new State(rs.getBoolean("backfill_complete"),
            position(rs, "backfill"), rs.getBoolean("reconcile_complete"), position(rs, "reconcile"),
            rs.getLong("reconcile_mismatches"), rs.getBoolean("ready"), rs.getBoolean("use_summary"));

    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;

    public NoticeSummaryMaintenance(JdbcTemplate jdbc, DeploymentNamespace namespace) {
        this.jdbc = jdbc;
        this.namespace = namespace;
    }

    public record Position(String docType, long docId) { }
    public record State(boolean backfillComplete, Position backfillAfter, boolean reconcileComplete,
                        Position reconcileAfter, long mismatches, boolean ready, boolean useSummary) { }
    /** mismatches 为本轮累计差异数，失败或超时整批回滚，不能推进进度。 */
    public record BatchProgress(int processed, boolean complete, long mismatches) { }

    @Override
    public void afterPropertiesSet() {
        // DeploymentNamespaceGuard 已先核对配置与数据库单例。这里不运行回填或对账。
        jdbc.execute("SELECT scm_notice_pending_initialize()");
    }

    public State state() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT backfill_after_type,backfill_after_id,backfill_complete,
                       reconcile_after_type,reconcile_after_id,reconcile_complete,
                       reconcile_mismatches,ready,use_summary
                FROM biz_doc_notice_pending_state WHERE tenant_key=?
                """, STATE, namespace.tenant()));
    }

    /** 一次 pendingPage 只取一次并用于两个主查询；不吞数据库异常后自动降级。 */
    public Optional<String> readNamespace() {
        boolean use = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT ready AND use_summary FROM biz_doc_notice_pending_state WHERE tenant_key=?
                """, Boolean.class, namespace.tenant()));
        return use ? Optional.of(namespace.tenant()) : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void restartBackfill() {
        budgets();
        jdbc.execute("SELECT scm_notice_pending_restart()");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public BatchProgress backfillBatch(int limit) {
        requireBatch(limit);
        budgets();
        return Objects.requireNonNull(jdbc.queryForObject("SELECT * FROM scm_notice_pending_backfill(?)", BATCH, limit));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void startReconciliation() {
        budgets();
        jdbc.execute("SELECT scm_notice_pending_start_reconciliation()");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public BatchProgress reconcileBatch(int limit) {
        requireBatch(limit);
        budgets();
        return Objects.requireNonNull(jdbc.queryForObject("SELECT * FROM scm_notice_pending_reconcile(?)", BATCH, limit));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
    public void setUseSummary(boolean enabled) {
        budgets();
        jdbc.queryForObject("SELECT scm_notice_pending_set_use_summary(?)", Object.class, enabled);
    }

    private void budgets() {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }

    private static void requireBatch(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Notice maintenance batch must be within 1..500");
    }

    private static Position position(ResultSet row, String prefix) throws SQLException {
        String type = row.getString(prefix + "_after_type");
        return type == null ? null : new Position(type, row.getLong(prefix + "_after_id"));
    }
}
