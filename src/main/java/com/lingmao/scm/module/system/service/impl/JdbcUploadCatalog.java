package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.UploadCatalog;
import com.lingmao.scm.module.system.service.UploadStore;
import com.lingmao.scm.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public final class JdbcUploadCatalog implements UploadCatalog, HousekeepingTask {
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final UploadStore storage;
    public JdbcUploadCatalog(JdbcTemplate source, DeploymentNamespace namespace, UploadStore storage) {
        jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(source.getDataSource())); jdbc.setQueryTimeout(5);
        this.namespace = namespace; this.storage = storage;
    }
    @Override public void begin(UploadStore.Slot slot) {
        jdbc.update("INSERT INTO sys_upload_file(file_url,thumb_url,tenant_key,owner_user_id,status) VALUES(?,?,?,?,'WRITING')",
                slot.url(), slot.thumbnailUrl(), namespace.tenant(), LoginUser.currentUserId());
    }
    @Override public void ready(UploadStore.Slot slot, UploadStore.Stored stored) {
        if (jdbc.update("UPDATE sys_upload_file SET status='READY',byte_size=?,sha256=?,thumb_url=?,touched_at=clock_timestamp() WHERE tenant_key=? AND file_url=? AND status='WRITING'",
                stored.bytes(), stored.sha256(), stored.thumbnailUrl(), namespace.tenant(), slot.url()) != 1)
            throw new BizException(409, "上传状态已变化，请重新上传");
    }
    @Override public void failed(UploadStore.Slot slot) {
        jdbc.update("UPDATE sys_upload_file SET status='DELETED',touched_at=clock_timestamp() WHERE tenant_key=? AND file_url=? AND status IN ('WRITING','READY')",
                namespace.tenant(), slot.url());
    }
    @Override public String name() { return "upload-orphans"; }
    @Override public void runUntil(java.util.function.BooleanSupplier stopping) {
        if (stopping.getAsBoolean()) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        var slots = jdbc.query("""
                WITH candidates AS (
                    SELECT u.file_url FROM sys_upload_file u WHERE u.tenant_key=? AND
                        (u.status='DELETING' OR u.status IN ('WRITING','READY') AND u.touched_at<clock_timestamp()-interval '24 hours'
                         AND NOT EXISTS(SELECT 1 FROM biz_attachment a WHERE a.file_url IN (u.file_url,u.thumb_url) OR a.thumb_url IN (u.file_url,u.thumb_url))
                         AND NOT EXISTS(SELECT 1 FROM sup_supplier_cert c WHERE c.file_url IN (u.file_url,u.thumb_url))
                         AND NOT EXISTS(SELECT 1 FROM tp_process_image p WHERE p.file_url IN (u.file_url,u.thumb_url) OR p.thumb_url IN (u.file_url,u.thumb_url)))
                    ORDER BY u.touched_at,u.file_url LIMIT 64 FOR UPDATE OF u SKIP LOCKED
                ) UPDATE sys_upload_file u SET status='DELETING',touched_at=clock_timestamp()
                  FROM candidates c WHERE u.file_url=c.file_url RETURNING u.file_url,u.thumb_url
                """, (row, n) -> new UploadStore.Slot(row.getString(1), row.getString(2)), namespace.tenant());
        for (var slot : slots) {
            if (stopping.getAsBoolean() || System.nanoTime() > deadline) break;
            try {
                storage.delete(slot);
                jdbc.update("UPDATE sys_upload_file SET status='DELETED',touched_at=clock_timestamp() WHERE tenant_key=? AND file_url=? AND status='DELETING'", namespace.tenant(), slot.url());
            } catch (IOException | RuntimeException failure) { log.warn("孤儿上传清理暂未完成，将在下一轮重试"); }
        }
    }
}
