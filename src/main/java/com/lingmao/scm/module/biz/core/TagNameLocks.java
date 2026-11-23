package com.lingmao.scm.module.biz.core;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** 名称尚无行时也能跨实例串行 get-or-create；仅使用 PostgreSQL 事务锁，无外部组件。 */
@Component
public class TagNameLocks {
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;

    public TagNameLocks(JdbcTemplate jdbc, DeploymentNamespace namespace) {
        this.jdbc = jdbc;
        this.namespace = namespace;
    }

    /** 真正按有符号锁键排序、去重；hash 碰撞最多增加串行，不会颠倒锁序。 */
    public List<Long> keys(Collection<String> canonicalNames) {
        if (canonicalNames == null || canonicalNames.size() > TagName.MAX_PER_OBJECT * 2) {
            throw new IllegalArgumentException("at most 60 tag names may be locked together");
        }
        var ordered = new TreeSet<Long>();
        for (String name : canonicalNames) {
            if (name == null) throw new IllegalArgumentException("canonical name is required");
            ordered.add(key(namespace.tenant(), name));
        }
        return List.copyOf(ordered);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(Collection<String> canonicalNames) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tag name locks require the caller's transaction");
        }
        List<Long> keys = keys(canonicalNames);
        // 顺序 execute 明确保证锁序，不依赖 SELECT 目标函数与 ORDER BY 的求值先后。
        jdbc.execute("SELECT pg_advisory_xact_lock(?)", (PreparedStatementCallback<Void>) statement -> {
            for (long key : keys) {
                statement.setLong(1, key);
                statement.execute();
            }
            return null;
        });
    }

    static long key(String tenant, String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("lingmao:tag-name:v1\0" + tenant + "\0" + canonical).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
