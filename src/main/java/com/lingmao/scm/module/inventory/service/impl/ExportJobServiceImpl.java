package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.inventory.dto.ExportJobView;
import com.lingmao.scm.module.inventory.dto.StockLogExportPlan;
import com.lingmao.scm.module.inventory.dto.StockLogExportReq;
import com.lingmao.scm.module.inventory.service.ExportJobService;
import com.lingmao.scm.module.inventory.service.StockLogExportService;
import com.lingmao.scm.module.system.service.ExportFileStore;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.FieldPermissionDenied;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class ExportJobServiceImpl implements ExportJobService, OutboxHandler, HousekeepingTask {
    private static final String PERMISSION = "inventory:stock:export";
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final OutboxService outbox;
    private final StockLogExportService exporter;
    private final ExportFileStore files;
    private final PermissionService permissions;
    private final JsonMapper json;
    private final org.springframework.transaction.support.TransactionTemplate cleanupTx;
    private record Job(long id, long owner, long outbox, String filters, boolean financial, long watermark,
                       long rows, UUID token, OffsetDateTime created, OffsetDateTime expires) {}

    public ExportJobServiceImpl(JdbcTemplate jdbc, DeploymentNamespace namespace, OutboxService outbox,
                                StockLogExportService exporter, ExportFileStore files, PermissionService permissions, JsonMapper json,
                                org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10);
        this.namespace = namespace; this.outbox = outbox; this.exporter = exporter; this.files = files;
        this.permissions = permissions; this.json = json;
        cleanupTx = new org.springframework.transaction.support.TransactionTemplate(transactions); cleanupTx.setTimeout(5);
    }

    @Override @Transactional
    public ExportJobView create(StockLogExportReq filters, String requestId) {
        long user = currentUser(); Set<String> current = authorized(user); filters.query();
        reserve(user);
        long id = jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('sys_export_job','id'))", Long.class);
        long event = outbox.enqueue(kind(), "stock-logs:" + id, json.writeValueAsString(java.util.Map.of("jobId", id)), user, requestId);
        jdbc.update("""
                INSERT INTO sys_export_job(id,tenant_key,owner_user_id,outbox_id,kind,filters,financial_columns,watermark)
                VALUES(?,?,?,?,'STOCK_LOGS',?::jsonb,?,?)
                """, id, namespace.tenant(), user, event, json.writeValueAsString(filters), current.contains(FieldPermissions.COST), exporter.watermark());
        return get(id);
    }

    private void reserve(long user) {
        // 锁用户这一行作为很短的准入锁；这个事务里不做远程调用，也不做导出。
        jdbc.queryForObject("SELECT id FROM sys_user WHERE id=? AND NOT deleted AND status=1 FOR UPDATE", Long.class, user);
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM sys_export_job j JOIN sys_outbox o ON o.id=j.outbox_id
                WHERE j.tenant_key=? AND j.owner_user_id=? AND o.status IN ('PENDING','PROCESSING','RETRY_WAIT')
                """, Long.class, namespace.tenant(), user);
        if (count >= 2) throw new BizException(429, "已有两个未完成的导出任务，请等待完成或先取消");
    }

    @Override public ExportJobView get(long id) { return view(owned(id)); }

    private Job owned(long id) {
        long user = currentUser(); Set<String> current = authorized(user);
        var rows = jdbc.query("SELECT * FROM sys_export_job WHERE tenant_key=? AND id=? AND owner_user_id=?", this::row,
                namespace.tenant(), id, user);
        if (rows.isEmpty()) throw new BizException(404, "导出任务不存在");
        Job job = rows.getFirst();
        if (job.financial() && !current.contains(FieldPermissions.COST)) throw new FieldPermissionDenied("当前权限不能查看此导出任务，请按当前权限重新导出");
        return job;
    }

    private ExportJobView view(Job job) {
        var state = outbox.state(job.outbox()); String status = state.status();
        if (job.expires() != null && job.expires().isBefore(OffsetDateTime.now()) && status.equals("SUCCEEDED")) status = "EXPIRED";
        return new ExportJobView(job.id(), "STOCK_LOGS", status, job.rows(), state.attempts(), state.requestId(), job.created(),
                job.expires(), state.errorCode(), status.equals("SUCCEEDED") ? "/api/export-jobs/" + job.id() + "/file" : null);
    }

    @Override @Transactional public ExportJobView cancel(long id) { Job job = owned(id); outbox.cancel(job.outbox()); return view(job); }

    @Override @Transactional public ExportJobView retry(long id) {
        Job initial = owned(id); reserve(initial.owner());
        // 先拿和完成、过期同一把锁，再重读当前的文件版本：
        // 等用户准入锁的时候，别的重推可能已经做完了。
        jdbc.queryForObject("SELECT id FROM sys_outbox WHERE tenant_key=? AND id=? FOR UPDATE", Long.class, namespace.tenant(), initial.outbox());
        Job job = owned(id);
        if (job.expires() != null && job.expires().isBefore(OffsetDateTime.now())) {
            jdbc.update("UPDATE sys_outbox SET status='EXPIRED',updated_at=clock_timestamp() WHERE tenant_key=? AND id=? AND status='SUCCEEDED'", namespace.tenant(), job.outbox());
        }
        outbox.retry(job.outbox());
        jdbc.update("UPDATE sys_export_job SET file_token=NULL,row_count=0,byte_size=0,expires_at=NULL,watermark=? WHERE tenant_key=? AND id=?",
                exporter.watermark(), namespace.tenant(), id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { files.delete(job.id(), job.token()); }
        });
        return get(id);
    }

    @Override public Download download(long id) {
        Job job = owned(id); var view = view(job);
        if (!view.status().equals("SUCCEEDED") || job.token() == null) throw new BizException(409, "导出尚未完成或文件已过期，请查看任务状态");
        var path = files.path(id, job.token());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            expire(job, true);
            throw new BizException(410, "导出文件已清理，请重新生成");
        }
        return new Download(path, "库存日志-" + id + ".xlsx");
    }

    private long currentUser() {
        Long user = LoginUser.currentUserId(); if (user == null) throw new FieldPermissionDenied("请重新登录后查看导出任务"); return user;
    }
    private Set<String> authorized(long user) {
        Set<String> current = permissions.loadPermissions(user);
        if (current == null || !current.contains(PERMISSION)) throw new FieldPermissionDenied("当前账号没有库存导出权限"); return current;
    }
    private Job row(ResultSet rs, int n) throws SQLException {
        return new Job(rs.getLong("id"), rs.getLong("owner_user_id"), rs.getLong("outbox_id"), rs.getString("filters"),
                rs.getBoolean("financial_columns"), rs.getLong("watermark"), rs.getLong("row_count"), rs.getObject("file_token", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class));
    }

    @Override public String kind() { return "STOCK_LOG_EXPORT"; }
    @Override public void execute(OutboxWork work, String payload) throws Exception {
        long id = json.readTree(payload).path("jobId").longValue();
        var jobs = jdbc.query("SELECT * FROM sys_export_job WHERE tenant_key=? AND id=? AND outbox_id=?", this::row, namespace.tenant(), id, work.id());
        if (jobs.isEmpty()) throw new JobFailure(JobFailure.Code.INVALID_INPUT, true);
        Job job = jobs.getFirst(); checkPermissions(job);
        var slot = files.allocate(id, work.leaseToken()); boolean published = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
        try {
            var filters = json.readValue(job.filters(), StockLogExportReq.class);
            long rows;
            try (var output = files.output(slot)) {
                rows = exporter.write(new StockLogExportPlan(filters, job.watermark(), job.financial(), 1_000_000), output, count -> {
                    work.checkpoint(); checkPermissions(job);
                    if (System.nanoTime() > deadline) throw new JobFailure(JobFailure.Code.DEADLINE, true);
                    try { files.checkCapacity(); } catch (java.io.IOException failure) { throw new JobFailure(JobFailure.Code.DISK_FULL, true); }
                    jdbc.update("""
                            UPDATE sys_export_job SET row_count=? WHERE tenant_key=? AND id=? AND EXISTS
                            (SELECT 1 FROM sys_outbox WHERE id=? AND lease_token=? AND status='PROCESSING' AND lease_until>clock_timestamp())
                            """, count, namespace.tenant(), id, work.id(), UUID.fromString(work.leaseToken()));
                });
            }
            work.checkpoint(); checkPermissions(job); long size = files.publish(slot);
            work.complete(() -> jdbc.update("""
                    UPDATE sys_export_job SET row_count=?,byte_size=?,file_token=?,expires_at=clock_timestamp()+interval '24 hours'
                    WHERE tenant_key=? AND id=?
                    """, rows, size, slot.token(), namespace.tenant(), id));
            published = true;
        } finally { if (!published) files.discard(slot); }
    }

    private void checkPermissions(Job job) {
        Set<String> current = permissions.loadPermissions(job.owner());
        if (current == null || !current.contains(PERMISSION) || job.financial() && !current.contains(FieldPermissions.COST))
            throw new JobFailure(JobFailure.Code.PERMISSION_REVOKED, true);
    }

    @Override public String name() { return "export-job-expiry"; }
    @Override public void runUntil(java.util.function.BooleanSupplier stopping) {
        if (stopping.getAsBoolean()) return;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        var expired = jdbc.query("SELECT * FROM sys_export_job WHERE tenant_key=? AND expires_at<clock_timestamp() AND file_token IS NOT NULL ORDER BY expires_at LIMIT 128", this::row, namespace.tenant());
        for (Job job : expired) {
            if (stopping.getAsBoolean() || System.nanoTime() > deadline) break;
            if (expire(job, false)) files.delete(job.id(), job.token());
        }
    }

    private boolean expire(Job job, boolean missing) {
        return Boolean.TRUE.equals(cleanupTx.execute(status -> {
                String state = jdbc.queryForObject("SELECT status FROM sys_outbox WHERE tenant_key=? AND id=? FOR UPDATE", String.class, namespace.tenant(), job.outbox());
                if (!"SUCCEEDED".equals(state)) return false;
                int changed = jdbc.update("""
                        UPDATE sys_export_job SET file_token=NULL WHERE tenant_key=? AND id=? AND file_token=?
                            AND expires_at=? AND (? OR expires_at<clock_timestamp())
                        """, namespace.tenant(), job.id(), job.token(), job.expires(), missing);
                if (changed != 1) return false;
                jdbc.update("UPDATE sys_outbox SET status='EXPIRED',error_code=?,updated_at=clock_timestamp() WHERE tenant_key=? AND id=?",
                        missing ? JobFailure.Code.FILE_MISSING.name() : null, namespace.tenant(), job.outbox());
                return true;
        }));
    }
}
