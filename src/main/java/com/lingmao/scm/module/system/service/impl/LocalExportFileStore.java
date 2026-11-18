package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.ExportFileSlot;
import com.lingmao.scm.module.system.service.ExportFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

@Service
@org.springframework.context.annotation.DependsOn("deploymentNamespaceGuard")
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public final class LocalExportFileStore implements ExportFileStore, HousekeepingTask {
    private final Path root;
    private final String prefix;
    private final UUID identity;
    private final Path marker;
    private java.nio.file.DirectoryStream<Path> scan;
    private java.util.Iterator<Path> iterator;
    public LocalExportFileStore(@Value("${lingmao.export.dir:${EXPORT_DIR:./exports}}") String directory, DeploymentNamespace namespace,
            org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) throws IOException {
        Path configured = Path.of(directory).toAbsolutePath().normalize(); Files.createDirectories(configured);
        root = configured.toRealPath(); prefix = namespace.tenant() + "-";
        marker = root.resolve("." + prefix + "storage-identity");
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager); tx.setTimeout(10);
        identity = tx.execute(status -> {
            UUID expected = jdbc.queryForObject("SELECT export_store_uuid FROM sys_deployment_namespace WHERE singleton AND tenant_id=? FOR UPDATE", UUID.class, namespace.tenant());
            try {
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) Files.writeString(marker, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 64) throw new IOException("Invalid storage identity");
                UUID actual = UUID.fromString(Files.readString(marker).strip());
                if (expected == null) jdbc.update("UPDATE sys_deployment_namespace SET export_store_uuid=? WHERE singleton", actual);
                else if (!expected.equals(actual)) throw new IllegalStateException("All application instances must mount the same private export directory");
                return actual;
            } catch (IOException invalid) { throw new java.io.UncheckedIOException("Private export storage identity could not be read", invalid); }
        });
    }
    @Override public ExportFileSlot allocate(long jobId, String leaseToken) throws IOException {
        checkCapacity(); UUID token = UUID.fromString(leaseToken); Path complete = path(jobId, token);
        return new ExportFileSlot(jobId, token, complete.resolveSibling(complete.getFileName() + ".part"), complete);
    }
    @Override public OutputStream output(ExportFileSlot slot) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(slot.partial(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long bytes;
            private void reserve(int count) { if (count > MAX_BYTES - bytes) throw new JobFailure(JobFailure.Code.SIZE_LIMIT, true); bytes += count; }
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException { reserve(len); out.write(bytes, off, len); }
        };
    }
    @Override public void checkCapacity() throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 64 || !identity.toString().equals(Files.readString(marker).strip()))
            throw new IOException("Private export storage identity changed");
        if (Files.getFileStore(root).getUsableSpace() < 64L * 1024 * 1024) throw new JobFailure(JobFailure.Code.DISK_FULL, true);
    }
    @Override public long publish(ExportFileSlot slot) throws IOException {
        long size = Files.size(slot.partial());
        if (size > MAX_BYTES) throw new JobFailure(JobFailure.Code.SIZE_LIMIT, true);
        try { Files.move(slot.partial(), slot.complete(), StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(slot.partial(), slot.complete()); }
        return size;
    }
    @Override public void discard(ExportFileSlot slot) { erase(slot.partial()); erase(slot.complete()); }
    @Override public Path path(long jobId, UUID token) {
        if (jobId <= 0 || token == null) throw new IllegalArgumentException("Invalid export file identity");
        return root.resolve(prefix + jobId + "-" + token + ".xlsx");
    }
    @Override public void delete(long jobId, UUID token) { if (token != null) erase(path(jobId, token)); }
    private static void erase(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(LocalExportFileStore.class).warn("Private export deletion deferred: {}", failure.getClass().getSimpleName()); }
    }
    @Override public String name() { return "export-file-orphans"; }
    @Override public synchronized void run() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        Instant parts = Instant.now().minusSeconds(3600), complete = Instant.now().minusSeconds(2 * 86400);
        try {
            if (scan == null) { scan = Files.newDirectoryStream(root, prefix + "*"); iterator = scan.iterator(); }
            int inspected = 0;
            while (iterator.hasNext() && inspected++ < 512 && System.nanoTime() <= deadline) {
                Path file = iterator.next();
                String name = file.getFileName().toString();
                if (!name.matches(java.util.regex.Pattern.quote(prefix) + "[1-9][0-9]*-[0-9a-f-]{36}\\.xlsx(?:\\.part)?")
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                Instant modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (modified.isBefore(name.endsWith(".part") ? parts : complete)) erase(file);
            }
            if (!iterator.hasNext()) closeScan();
        } catch (IOException failure) { closeScan(); throw new java.io.UncheckedIOException(failure); }
    }
    @jakarta.annotation.PreDestroy public synchronized void closeScan() {
        if (scan != null) { try { scan.close(); } catch (IOException ignored) {} scan = null; iterator = null; }
    }
}
