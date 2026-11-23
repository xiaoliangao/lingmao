package com.lingmao.scm;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 初始角色授权只在真实完整迁移的新库验证，不读取已被 HTTP 用例合法改过的业务库角色集合。
 * 沿用 S1FgMigrationIT 的隔离库、多连接与 session 迁移锁；不跳过生产迁移或改写授权 SQL。
 */
public final class IsolatedRoleMigration implements AutoCloseable {
    private static final List<String> SENTINELS = List.of("custom_migration_self", "custom_migration_all");
    private final JdbcTemplate admin;
    private final String database;
    private final String grantVersion;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;

    private IsolatedRoleMigration(JdbcTemplate admin, String database, String grantVersion,
                                  JdbcTemplate jdbc, Flyway flyway) {
        this.admin = admin; this.database = database; this.grantVersion = grantVersion;
        this.jdbc = jdbc; this.flyway = flyway;
    }

    public static IsolatedRoleMigration migrate(DataSource original, String password, String grantVersion) throws SQLException {
        if (!List.of("58", "59").contains(grantVersion)) throw new IllegalArgumentException("Unknown role grant migration");
        var admin = new JdbcTemplate(original);
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).endsWith("_test");
        String database = "s1_role_" + UUID.randomUUID().toString().replace("-", "") + "_test";
        String url, user;
        try (var connection = original.getConnection()) {
            String current = connection.getMetaData().getURL();
            int query = current.indexOf('?');
            String address = query < 0 ? current : current.substring(0, query);
            url = address.substring(0, address.lastIndexOf('/') + 1) + database + (query < 0 ? "" : current.substring(query));
            user = connection.getMetaData().getUserName();
        }
        admin.execute("CREATE DATABASE " + database);
        try {
            var separate = new DriverManagerDataSource(url, user, password);
            var seeded = new AtomicBoolean();
            Callback sentinel = new Callback() {
                @Override public boolean supports(Event event, Context context) {
                    return event == Event.BEFORE_EACH_MIGRATE && context.getMigrationInfo() != null
                            && context.getMigrationInfo().getVersion() != null
                            && grantVersion.equals(context.getMigrationInfo().getVersion().getVersion());
                }
                @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
                @Override public void handle(Event event, Context context) {
                    // 在真正 V58/V59 授权 SQL 执行之前存在，验证迁移不会给自定义角色自动授予权限。
                    try (var statement = context.getConnection().prepareStatement(
                            "INSERT INTO sys_role(code,name,data_scope) VALUES(?,?,'SELF'),(?,?,'ALL')")) {
                        statement.setString(1, SENTINELS.getFirst()); statement.setString(2, "迁移前自定义 SELF 哨兵");
                        statement.setString(3, SENTINELS.getLast()); statement.setString(4, "迁移前自定义 ALL 哨兵");
                        assertThat(statement.executeUpdate()).isEqualTo(2);
                        assertThat(seeded.compareAndSet(false, true)).isTrue();
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Cannot seed role sentinels before V" + grantVersion, failure);
                    }
                }
                @Override public String getCallbackName() { return "role-sentinels-before-v" + grantVersion; }
            };
            var configuration = Flyway.configure().dataSource(separate).locations("classpath:db/migration").callbacks(sentinel);
            configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
            Flyway migrated = configuration.load();
            migrated.migrate();
            assertThat(seeded).as("custom roles must exist before the real grant migration").isTrue();
            return new IsolatedRoleMigration(admin, database, grantVersion, new JdbcTemplate(separate), migrated);
        } catch (RuntimeException | Error failure) {
            try { admin.execute("DROP DATABASE " + database + " WITH (FORCE)"); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public JdbcTemplate jdbc() { return jdbc; }
    public Flyway flyway() { return flyway; }

    /** 原角色全集断言不做前缀排除；额外证明两个实际预存自定义角色仍在且没有被迁移授权。 */
    public void assertSentinelsUnchanged() throws IOException {
        var roles = jdbc.queryForList("SELECT code,data_scope,deleted,status::integer AS status FROM sys_role WHERE code IN (?,?) ORDER BY code",
                SENTINELS.getFirst(), SENTINELS.getLast());
        var grants = jdbc.queryForList("""
                SELECT r.code,m.perms FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id
                JOIN sys_menu m ON m.id=rm.menu_id WHERE r.code IN (?,?) ORDER BY r.code,m.id
                """, SENTINELS.getFirst(), SENTINELS.getLast());
        Path directory = Path.of("test-artifacts/full-audit/s1/schema-isolation"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("role-sentinels-before-v" + grantVersion + ".json"),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("migrationBeforeWhichSeeded", grantVersion, "sentinelRoles", roles, "sentinelGrants", grants,
                                "appliedMigrationCount", jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))));
        assertThat(roles).containsExactly(
                Map.of("code", SENTINELS.getLast(), "data_scope", "ALL", "deleted", false, "status", 1),
                Map.of("code", SENTINELS.getFirst(), "data_scope", "SELF", "deleted", false, "status", 1));
        assertThat(grants).as("full production migration must not grant custom roles any permissions").isEmpty();
    }

    @Override public void close() { admin.execute("DROP DATABASE " + database + " WITH (FORCE)"); }
}
