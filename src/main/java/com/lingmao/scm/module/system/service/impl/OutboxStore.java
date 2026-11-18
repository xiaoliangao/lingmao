package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.OutboxState;
import com.lingmao.scm.module.system.service.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxStore implements OutboxService {
    record Lease(long id, String kind, String payload, UUID token, String requestId, int attempt, Long actor) {}
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final TransactionTemplate tx;

    public OutboxStore(JdbcTemplate jdbc, DeploymentNamespace namespace, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10);
        this.namespace = namespace; tx = new TransactionTemplate(manager); tx.setTimeout(5);
    }

    @Override @Transactional
    public long enqueue(String kind, String dedupeKey, String payload, Long actor, String requestId) {
        if (!List.of("CACHE_INVALIDATE", "STOCK_LOG_EXPORT").contains(kind) || dedupeKey.length() > 200)
            throw new IllegalArgumentException("Unsupported job kind/key");
        String trace = com.lingmao.scm.common.log.TraceIdFilter.normalized(requestId);
        List<Long> inserted = jdbc.queryForList("""
                INSERT INTO sys_outbox(tenant_key,kind,dedupe_key,payload,actor_user_id,request_id)
                VALUES(?,?,?,?::jsonb,?,?) ON CONFLICT(tenant_key,kind,dedupe_key) DO NOTHING RETURNING id
                """, Long.class, namespace.tenant(), kind, dedupeKey, payload, actor, trace);
        if (!inserted.isEmpty()) return inserted.getFirst();
        return jdbc.queryForObject("SELECT id FROM sys_outbox WHERE tenant_key=? AND kind=? AND dedupe_key=?",
                Long.class, namespace.tenant(), kind, dedupeKey);
    }

    @Override public OutboxState state(long id) {
        var rows = jdbc.query("SELECT * FROM sys_outbox WHERE tenant_key=? AND id=?", OutboxStore::state,
                namespace.tenant(), id);
        if (rows.isEmpty()) throw new BizException(404, "后台任务不存在");
        return rows.getFirst();
    }

    private static OutboxState state(ResultSet rs, int n) throws SQLException {
        return new OutboxState(rs.getLong("id"), rs.getString("kind"), rs.getString("status"), rs.getInt("attempts"),
                rs.getBoolean("cancel_requested"), rs.getString("error_code"), rs.getString("request_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    Lease claim(String kind) {
        UUID token = UUID.randomUUID();
        var rows = jdbc.query("""
                WITH candidate AS (SELECT id FROM sys_outbox
                    WHERE tenant_key=? AND kind=? AND status IN ('PENDING','RETRY_WAIT')
                    AND available_at<=clock_timestamp() AND NOT cancel_requested
                    ORDER BY available_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE sys_outbox j SET status='PROCESSING',attempts=j.attempts+1,lease_token=?,
                    lease_until=clock_timestamp()+interval '60 seconds',updated_at=clock_timestamp(),error_code=NULL
                FROM candidate c WHERE j.id=c.id RETURNING j.*
                """, (rs, n) -> new Lease(rs.getLong("id"), rs.getString("kind"), rs.getString("payload"),
                rs.getObject("lease_token", UUID.class), rs.getString("request_id"), rs.getInt("attempts"), (Long) rs.getObject("actor_user_id")),
                namespace.tenant(), kind, token);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    boolean renew(Lease lease) {
        return jdbc.update("""
                UPDATE sys_outbox SET lease_until=clock_timestamp()+interval '60 seconds',updated_at=clock_timestamp()
                WHERE tenant_key=? AND id=? AND lease_token=? AND status='PROCESSING'
                    AND lease_until>clock_timestamp() AND NOT cancel_requested
                """, namespace.tenant(), lease.id(), lease.token()) == 1;
    }

    void check(Lease lease) {
        var states = jdbc.queryForList("""
                SELECT cancel_requested FROM sys_outbox WHERE tenant_key=? AND id=? AND lease_token=?
                    AND status='PROCESSING' AND lease_until>clock_timestamp()
                """, Boolean.class, namespace.tenant(), lease.id(), lease.token());
        if (states.isEmpty()) throw new JobFailure(JobFailure.Code.LEASE_LOST, false);
        if (states.getFirst()) throw new JobFailure(JobFailure.Code.CANCELLED, true);
    }

    void complete(Lease lease, Runnable businessUpdate) {
        tx.executeWithoutResult(status -> {
            // Fence the short commit against a reaper, another worker and a simultaneous cancellation.
            var permitted = jdbc.queryForList("""
                    SELECT id FROM sys_outbox WHERE tenant_key=? AND id=? AND lease_token=?
                        AND status='PROCESSING' AND lease_until>clock_timestamp() AND NOT cancel_requested FOR UPDATE
                    """, Long.class, namespace.tenant(), lease.id(), lease.token());
            if (permitted.isEmpty()) throw new JobFailure(JobFailure.Code.LEASE_LOST, false);
            businessUpdate.run();
            jdbc.update("""
                    UPDATE sys_outbox SET status='SUCCEEDED',lease_token=NULL,lease_until=NULL,error_code=NULL,
                        updated_at=clock_timestamp() WHERE id=? AND tenant_key=?
                    """, lease.id(), namespace.tenant());
        });
    }

    void fail(Lease lease, JobFailure failure) {
        String state = failure.code() == JobFailure.Code.CANCELLED ? "CANCELLED"
                : failure.permanent() || lease.attempt() >= 5 ? "DEAD_LETTER" : "RETRY_WAIT";
        long backoff = Math.min(900, 30L << Math.min(lease.attempt() - 1, 5));
        jdbc.update("""
                UPDATE sys_outbox SET status=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE ? END,
                    error_code=?,lease_token=NULL,lease_until=NULL,
                    available_at=clock_timestamp()+(? * interval '1 second'),updated_at=clock_timestamp()
                WHERE tenant_key=? AND id=? AND lease_token=? AND status='PROCESSING'
                """, state, failure.code().name(), backoff, namespace.tenant(), lease.id(), lease.token());
    }

    int reap() {
        return jdbc.update("""
                UPDATE sys_outbox SET status=CASE WHEN cancel_requested THEN 'CANCELLED'
                    WHEN attempts>=5 THEN 'DEAD_LETTER' ELSE 'RETRY_WAIT' END,
                    error_code=CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'LEASE_EXPIRED' END,
                    lease_token=NULL,lease_until=NULL,available_at=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp()
                WHERE tenant_key=? AND status='PROCESSING' AND lease_until<=clock_timestamp()
                """, namespace.tenant());
    }

    @Override public void cancel(long id) {
        jdbc.update("""
                UPDATE sys_outbox SET cancel_requested=TRUE,
                    status=CASE WHEN status='PROCESSING' THEN status ELSE 'CANCELLED' END,updated_at=clock_timestamp()
                WHERE tenant_key=? AND id=? AND status IN ('PENDING','PROCESSING','RETRY_WAIT')
                """, namespace.tenant(), id);
    }

    @Override public void retry(long id) {
        int changed = jdbc.update("""
                UPDATE sys_outbox SET status='PENDING',attempts=0,cancel_requested=FALSE,error_code=NULL,
                    lease_token=NULL,lease_until=NULL,available_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE tenant_key=? AND id=? AND status IN ('DEAD_LETTER','CANCELLED','EXPIRED')
                """, namespace.tenant(), id);
        if (changed != 1) throw new BizException(409, "任务当前状态不能重试，请刷新后查看");
    }

    @Override public boolean housekeepingLease(String name, int seconds) {
        if (!name.matches("[a-z0-9-]{1,30}") || seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Invalid housekeeping lease");
        var claimed = jdbc.queryForList("""
                INSERT INTO sys_job_lease(name,lease_token,lease_until) VALUES(?,?,clock_timestamp()+(? * interval '1 second'))
                ON CONFLICT(name) DO UPDATE SET lease_token=EXCLUDED.lease_token,lease_until=EXCLUDED.lease_until
                WHERE sys_job_lease.lease_until<=clock_timestamp() RETURNING name
                """, String.class, namespace.tenant() + ":" + name, UUID.randomUUID(), seconds);
        return !claimed.isEmpty();
    }
}
