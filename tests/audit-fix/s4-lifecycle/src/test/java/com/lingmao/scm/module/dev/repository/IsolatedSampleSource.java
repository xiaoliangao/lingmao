package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.SqlStatementCapture;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 本类只拥有一个随机的样衣审计测试源库，不清理共享测试库或共享 Redis。
 * 恢复测试仍备份整个源库；隔离的是别的测试已提交的夹具，不是裁剪本测试的备份内容。
 */
final class IsolatedSampleSource implements AutoCloseable {
    private static final String PREFIX = "s4_sample_source_";
    private static final Path EVIDENCE = Path.of("test-artifacts/full-audit/sample/s4-schema/source-isolation.json");
    private final String sharedUrl;
    private final String sharedDatabase;
    private final String database = PREFIX + UUID.randomUUID().toString().replace("-", "") + "_test";
    private final String sourceUrl;
    private final Properties credentials = new Properties();
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private HikariDataSource pool;
    private boolean createAttempted;
    private boolean closeAttempted;
    private RuntimeException cleanupFailure;

    private IsolatedSampleSource(DataSourceProperties properties) {
        sharedUrl = properties.getUrl();
        URI address = verifiedAddress(sharedUrl);
        sharedDatabase = address.getPath().substring(1);
        sourceUrl = "jdbc:postgresql://" + address.getRawAuthority() + "/" + database;
        if (!database.matches("s4_sample_source_[0-9a-f]{32}_test") || database.equals(sharedDatabase)) {
            throw new IllegalStateException("Invalid isolated sample source identity");
        }
        if (properties.getUsername() == null || properties.getUsername().isBlank()) {
            throw new IllegalStateException("The isolated sample source requires a configured test user");
        }
        credentials.setProperty("user", properties.getUsername());
        credentials.setProperty("password", properties.getPassword() == null ? "" : properties.getPassword());
        credentials.setProperty("connectTimeout", "5");
        credentials.setProperty("socketTimeout", "10");
        credentials.setProperty("ApplicationName", "s4_sample_source_lifecycle");
        evidence.put("scope", "Fresh fully migrated S4 source database; not a large shared-database restore capacity result");
        evidence.put("sharedDatabase", sharedDatabase);
        evidence.put("sourceDatabase", database);
        evidence.put("distinctFromSharedDatabase", !database.equals(sharedDatabase));
        evidence.put("sourceDatabaseRemoved", false);
    }

    private static URI verifiedAddress(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("An explicit loopback PostgreSQL test URL is required");
        }
        final URI uri;
        try { uri = URI.create(url.substring("jdbc:".length())); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid PostgreSQL test URL"); }
        if (uri.getHost() == null || !java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_]+_test")
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalStateException("Only a credential-free loopback URL naming a test database is permitted");
        }
        return uri;
    }

    private static IsolatedSampleSource create(DataSourceProperties properties) {
        IsolatedSampleSource source = new IsolatedSampleSource(properties);
        try {
            try (Connection connection = source.managementConnection()) {
                if (source.exists(connection)) throw new IllegalStateException("Generated sample source already exists");
                // The unguessable, validated name is owned by this attempt. A failed client response
                // after CREATE must still allow cleanup; an explicit duplicate-name error must not.
                source.createAttempted = true;
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE \"" + source.database + "\" TEMPLATE template0");
                    source.evidence.put("sourceDatabaseWasCreated", true);
                } catch (SQLException failure) {
                    if ("42P04".equals(failure.getSQLState())) source.createAttempted = false;
                    throw failure;
                }
            }
            try (Connection connection = DriverManager.getConnection(source.sourceUrl, source.credentials);
                 var statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                try (var rows = statement.executeQuery("SELECT current_database(), count(*) FROM pg_catalog.pg_tables WHERE schemaname='public'")) {
                    if (!rows.next() || !source.database.equals(rows.getString(1)) || rows.getLong(2) != 0) {
                        throw new IllegalStateException("The dedicated sample source must start with an empty public schema");
                    }
                    source.evidence.put("publicTablesBeforeMigration", rows.getLong(2));
                }
            }
            return source;
        } catch (SQLException failure) {
            RuntimeException primary = safeFailure("create source database", failure);
            source.closeAfterFailure(primary);
            throw primary;
        } catch (RuntimeException primary) {
            source.closeAfterFailure(primary);
            throw primary;
        }
    }

    private Connection managementConnection() throws SQLException {
        // This URL is validated before any connection or DDL. Verify the actual database again
        // on each fresh connection, including cleanup after a failed context initialization.
        Connection connection = DriverManager.getConnection(sharedUrl, credentials);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("SELECT current_database()")) {
                if (!rows.next() || !sharedDatabase.equals(rows.getString(1))) {
                    throw new IllegalStateException("Management connection did not reach the verified test database");
                }
            }
            statement.execute("SET statement_timeout='30s'; SET lock_timeout='5s'");
            return connection;
        } catch (SQLException | RuntimeException primary) {
            try { connection.close(); } catch (SQLException cleanup) { primary.addSuppressed(safeFailure("close management connection", cleanup)); }
            throw primary;
        }
    }

    private boolean exists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_database WHERE datname=?)")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        }
    }

    private void migrate(DataSource application, Flyway flyway) {
        try {
            evidence.put("applicationDatabase", requireSource(application));
            evidence.put("flywayDatabase", requireSource(flyway.getConfiguration().getDataSource()));
            var result = flyway.migrate();
            evidence.put("migrationsExecuted", result.migrationsExecuted);
            evidence.put("schemaVersion", String.valueOf(flyway.info().current().getVersion()));
            evidence.put("allConfiguredMigrationsApplied", true);
            writeEvidence();
        } catch (RuntimeException primary) {
            closeAfterFailure(primary);
            throw primary;
        }
    }

    private String requireSource(DataSource dataSource) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT current_database()")) {
            if (!rows.next() || !database.equals(rows.getString(1))) {
                throw new IllegalStateException("Both application and Flyway must use the owned sample source database");
            }
            return rows.getString(1);
        } catch (SQLException failure) { throw safeFailure("verify source database", failure); }
    }

    String database() { return database; }
    String sharedDatabase() { return sharedDatabase; }

    private void closeAfterFailure(Throwable primary) {
        try { close(); } catch (RuntimeException cleanup) { if (cleanup != primary) primary.addSuppressed(cleanup); }
    }

    /** Explicitly checked after context shutdown; bean destruction alone would only log failures. */
    void requireRemoved() {
        if (cleanupFailure != null) throw cleanupFailure;
        if (!closeAttempted || !Boolean.TRUE.equals(evidence.get("sourceDatabaseRemoved"))) {
            throw new IllegalStateException("Owned sample source cleanup was not verified");
        }
    }

    @Override public synchronized void close() {
        if (closeAttempted) {
            if (cleanupFailure != null) throw cleanupFailure;
            return;
        }
        closeAttempted = true;
        RuntimeException failure = null;
        if (pool != null) {
            try { pool.close(); evidence.put("sourcePoolClosed", pool.isClosed()); }
            catch (RuntimeException problem) { failure = safeFailure("close source pool", problem); }
        }
        if (createAttempted) {
            try (Connection connection = managementConnection(); var statement = connection.createStatement()) {
                if (!database.matches("s4_sample_source_[0-9a-f]{32}_test") || database.equals(sharedDatabase)) {
                    throw new IllegalStateException("Refuse to remove an unowned sample source database");
                }
                statement.execute("DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
                boolean removed = !exists(connection);
                evidence.put("sourceDatabaseRemoved", removed);
                if (!removed) throw new IllegalStateException("Owned sample source still exists after cleanup");
            } catch (SQLException | RuntimeException problem) {
                RuntimeException cleanup = safeFailure("remove source database", problem);
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        } else {
            evidence.put("sourceDropSkipped", true);
            evidence.put("sourceDatabaseWasCreated", false);
        }
        if (failure != null) evidence.put("cleanupFailure", failure.getMessage());
        try { writeEvidence(); }
        catch (RuntimeException write) { if (failure == null) failure = write; else failure.addSuppressed(write); }
        cleanupFailure = failure;
        if (failure != null) throw failure;
    }

    private void writeEvidence() {
        try {
            Files.createDirectories(EVIDENCE.getParent());
            Files.writeString(EVIDENCE, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } catch (Exception failure) { throw safeFailure("write source isolation evidence", failure); }
    }

    private static RuntimeException safeFailure(String stage, Exception failure) {
        String state = failure instanceof SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().matches("[0-9A-Z]{5}") ? "; SQLSTATE=" + sql.getSQLState() : "";
        // JDBC exceptions can contain usernames/URLs. The fixed stage and SQLSTATE are enough
        // here; preserve the existing assertion or migration failure as the primary exception.
        return new IllegalStateException("Isolated S4 source: " + stage + " failed (" + failure.getClass().getSimpleName() + state + ")");
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class Configuration {
        @Bean(destroyMethod = "close")
        IsolatedSampleSource isolatedSampleSource(DataSourceProperties properties) { return create(properties); }

        @Bean(destroyMethod = "")
        @ConfigurationProperties("spring.datasource.hikari")
        DataSource dataSource(IsolatedSampleSource source, DataSourceProperties properties) {
            HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).url(source.sourceUrl).build();
            source.pool = pool;
            return pool;
        }

        @Bean SqlStatementCapture sqlStatementCapture() { return new SqlStatementCapture(); }

        @Bean FlywayMigrationStrategy migrateOwnedSampleSource(IsolatedSampleSource source, DataSource dataSource) {
            return flyway -> source.migrate(dataSource, flyway);
        }
    }
}
