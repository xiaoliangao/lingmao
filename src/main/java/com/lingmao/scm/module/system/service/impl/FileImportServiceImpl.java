package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.idempotency.IdempotencyInProgress;
import com.lingmao.scm.common.idempotency.RequestFingerprint;
import com.lingmao.scm.common.jobs.HousekeepingTask;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.FileImportService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.FieldPermissionDenied;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public final class FileImportServiceImpl implements FileImportService, HousekeepingTask {
    private static final Set<String> FIELD_RIGHTS = Set.of(FieldPermissions.COST, FieldPermissions.ORDER,
            FieldPermissions.PRODUCTION, FieldPermissions.CONTACT, FieldPermissions.BANK);
    private final Semaphore parsers = new Semaphore(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DeploymentNamespace namespace;
    private final PermissionService permissions;
    private final JsonMapper json;
    private record Key(long user, String operation, String scope, String file, String options) {}

    public FileImportServiceImpl(JdbcTemplate jdbc, PlatformTransactionManager manager, DeploymentNamespace namespace,
                                 PermissionService permissions, JsonMapper json) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10);
        tx = new TransactionTemplate(manager); tx.setTimeout(60);
        this.namespace = namespace; this.permissions = permissions; this.json = json;
    }

    @Override public <P> ImportResult execute(String operation, String scope, String permission, MultipartFile file, Object options,
                                              Supplier<P> parse, Function<P, ImportResult> write) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("File import parsing must precede the write transaction");
        if (!operation.matches("[a-z-]{1,30}") || scope == null || scope.length() > 80)
            throw new IllegalArgumentException("Invalid server import scope");
        Long user = LoginUser.currentUserId();
        if (user == null) throw new FieldPermissionDenied("登录账号");
        Set<String> initial = authorize(user, permission);
        Set<String> fields = initial.stream().filter(FIELD_RIGHTS::contains).collect(Collectors.toSet());
        if (!parsers.tryAcquire()) throw new IdempotencyInProgress();
        try {
            Key key = new Key(user, operation, scope, hash(file), RequestFingerprint.of(json, options));
            ImportResult replay = completed(key);
            if (replay != null) return replay;
            P plan;
            try { plan = withPermissions(initial, parse); }
            catch (RuntimeException invalid) {
                // Another request may have committed this exact file while this pre-validation was reading.
                replay = completed(key);
                if (replay == null) throw invalid;
                authorize(user, permission); return replay;
            }
            return tx.execute(status -> {
                boolean claimed = Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                        RequestFingerprint.lockKey(namespace.tenant() + ":import:" + RequestFingerprint.of(json, key))));
                if (!claimed) throw new IdempotencyInProgress();
                Set<String> current = authorize(user, permission);
                if (!current.containsAll(fields)) throw new FieldPermissionDenied("导入字段");
                ImportResult previous = completed(key);
                if (previous != null) return previous;
                jdbc.update("""
                        DELETE FROM sys_import_receipt WHERE tenant_key=? AND user_id=? AND operation=? AND scope_key=?
                            AND file_hash=? AND options_hash=? AND expires_at<=clock_timestamp()
                        """, namespace.tenant(), user, operation, scope, key.file(), key.options());
                // The parser may take seconds. DataScope must use the same live rights just checked above,
                // rather than the request filter's earlier ALL/SELF snapshot.
                ImportResult result = withPermissions(current, () -> write.apply(plan));
                jdbc.update("""
                        INSERT INTO sys_import_receipt(tenant_key,user_id,operation,scope_key,file_hash,options_hash,result)
                        VALUES(?,?,?,?,?,?,?::jsonb)
                        """, namespace.tenant(), user, operation, scope, key.file(), key.options(), json.writeValueAsString(result));
                return result;
            });
        } finally { parsers.release(); }
    }

    private Set<String> authorize(long user, String permission) {
        Set<String> current = permissions.loadPermissions(user);
        if (current == null || !current.contains(permission)) throw new FieldPermissionDenied("当前导入操作");
        return current;
    }

    private static <T> T withPermissions(Set<String> current, Supplier<T> work) {
        var previousContext = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var freshContext = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        freshContext.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                previousContext.getAuthentication().getPrincipal(), null,
                current.stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(freshContext);
            return work.get();
        } finally { org.springframework.security.core.context.SecurityContextHolder.setContext(previousContext); }
    }

    private ImportResult completed(Key key) {
        var rows = jdbc.queryForList("""
                SELECT result::text FROM sys_import_receipt WHERE tenant_key=? AND user_id=? AND operation=? AND scope_key=?
                    AND file_hash=? AND options_hash=? AND expires_at>clock_timestamp()
                """, String.class, namespace.tenant(), key.user(), key.operation(), key.scope(), key.file(), key.options());
        return rows.isEmpty() ? null : json.readValue(rows.getFirst(), ImportResult.class);
    }

    private static String hash(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("文件不能为空");
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new BizException("只收 .xlsx 文件");
        if (file.getSize() > Excel.MAX_IMPORT_BYTES) throw new BizException(413, "文件超过 10MB，请分批导入");
        try (var input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024]; long size = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                if ((size += count) > Excel.MAX_IMPORT_BYTES) throw new BizException(413, "文件超过 10MB，请分批导入");
                digest.update(buffer, 0, count);
            }
            if (size == 0) throw new BizException("文件不能为空");
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        catch (IOException failure) { throw new BizException("读取文件失败，请重新选择文件"); }
    }

    @Override public String name() { return "import-receipt-expiry"; }
    @Override public void runUntil(java.util.function.BooleanSupplier stopping) {
        if (stopping.getAsBoolean()) return;
        jdbc.update("""
                DELETE FROM sys_import_receipt WHERE id IN (SELECT id FROM sys_import_receipt
                WHERE tenant_key=? AND expires_at<clock_timestamp() ORDER BY expires_at LIMIT 1000)
                """, namespace.tenant());
    }
}
