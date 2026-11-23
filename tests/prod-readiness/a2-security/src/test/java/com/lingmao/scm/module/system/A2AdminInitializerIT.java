package com.lingmao.scm.module.system;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.IsolatedRoleMigration;
import com.lingmao.scm.ScmApplication;
import com.lingmao.scm.config.AuditMetaObjectHandler;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P71：完整迁移的隔离 PostgreSQL + 真实 MyBatis/Spring 事务代理，绝不修改共享测试库 admin。 */
@SpringBootTest(classes = ScmApplication.class)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class A2AdminInitializerIT {
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;
    @Autowired AuditMetaObjectHandler audit;
    @Value("${spring.datasource.password:}") String databasePassword;
    private static final String ORIGINAL = "A2-Original-Admin-6284";
    private static final String RESTART = "A2-Restart-Admin-8642";

    @Test void twoIndependentInstancesCreateOnlyOneAccountAndOneInitialGrant() throws Exception {
        try (var isolated = database();
             var first = initializer(isolated.jdbc().getDataSource(), ORIGINAL);
             var second = initializer(isolated.jdbc().getDataSource(), RESTART);
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            JdbcTemplate jdbc = isolated.jdbc();
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var one = tasks.submit(() -> { ready.countDown(); start.await(); run(first); return true; });
            var two = tasks.submit(() -> { ready.countDown(); start.await(); run(second); return true; });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(one.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(two.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(adminCount(jdbc)).isOne();
            assertThat(adminGrants(jdbc)).isOne();
            String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE username='admin'", String.class);
            assertThat(passwords.matches(ORIGINAL, hash) ^ passwords.matches(RESTART, hash)).isTrue();
            Map<String, Object> before = snapshot(jdbc);
            long auditBefore = auditCount(jdbc);
            run(first); run(second);
            assertUnchanged(jdbc, before);
            assertThat(auditCount(jdbc)).isEqualTo(auditBefore);
            assertThat(adminGrants(jdbc)).isOne();
        }
    }

    @Test void restartingAfterExplicitRevocationCannotRestoreRoleOrPassword() throws Exception {
        try (var isolated = database(); var first = initializer(isolated.jdbc().getDataSource(), ORIGINAL)) {
            JdbcTemplate jdbc = isolated.jdbc(); run(first);
            jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username='admin')");
            Map<String, Object> before = snapshot(jdbc); long auditBefore = auditCount(jdbc);
            try (var restart = initializer(jdbc.getDataSource(), RESTART)) { run(restart); }
            assertUnchanged(jdbc, before);
            assertThat(adminGrants(jdbc)).isZero();
            assertThat(auditCount(jdbc)).isEqualTo(auditBefore);
            assertThat(passwords.matches(ORIGINAL, (String) before.get("password"))).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "disabled", "deleted"})
    void everyPreexistingIdentityIsUntouchedIncludingSoftDeletedUsername(String state) throws Exception {
        try (var isolated = database(); var startup = initializer(isolated.jdbc().getDataSource(), RESTART)) {
            JdbcTemplate jdbc = isolated.jdbc();
            jdbc.update("INSERT INTO sys_user(username,password,nickname,status,deleted) VALUES('admin',?,'A2 existing identity',?,?)",
                    passwords.encode(ORIGINAL), state.equals("disabled") ? 0 : 1, state.equals("deleted"));
            Map<String, Object> before = snapshot(jdbc); long auditBefore = auditCount(jdbc);
            run(startup); run(startup);
            assertThat(adminCount(jdbc)).isOne();
            assertUnchanged(jdbc, before);
            assertThat(adminGrants(jdbc)).isZero();
            assertThat(auditCount(jdbc)).isEqualTo(auditBefore);
        }
    }

    @Test void failedGrantRollsBackNewIdentityAndAuditThenARealRetryCanInitialize() throws Exception {
        try (var isolated = database(); var startup = initializer(isolated.jdbc().getDataSource(), ORIGINAL)) {
            JdbcTemplate jdbc = isolated.jdbc(); long auditBefore = auditCount(jdbc);
            jdbc.execute("""
                    CREATE FUNCTION a2_reject_initial_grant() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF EXISTS(SELECT 1 FROM sys_user WHERE id=NEW.user_id AND username='admin') THEN
                            RAISE EXCEPTION 'A2 synthetic initial grant rejection' USING ERRCODE='P2B71';
                        END IF;
                        RETURN NEW;
                    END $$
                    """);
            jdbc.execute("CREATE TRIGGER a2_reject_initial_grant AFTER INSERT ON sys_user_role FOR EACH ROW EXECUTE FUNCTION a2_reject_initial_grant()");
            assertThatThrownBy(() -> run(startup)).isInstanceOf(DataAccessException.class);
            assertThat(adminCount(jdbc)).isZero();
            assertThat(adminGrants(jdbc)).isZero();
            assertThat(auditCount(jdbc)).isEqualTo(auditBefore);
            jdbc.execute("DROP TRIGGER a2_reject_initial_grant ON sys_user_role");
            jdbc.execute("DROP FUNCTION a2_reject_initial_grant()");
            run(startup);
            assertThat(adminCount(jdbc)).isOne();
            assertThat(adminGrants(jdbc)).isOne();
            assertThat(passwords.matches(ORIGINAL, (String) snapshot(jdbc).get("password"))).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=0", "deleted=true"})
    void unavailableInitialRoleCannotLeaveAnUnusableAccount(String change) throws Exception {
        try (var isolated = database(); var startup = initializer(isolated.jdbc().getDataSource(), ORIGINAL)) {
            JdbcTemplate jdbc = isolated.jdbc();
            jdbc.update("UPDATE sys_role SET " + change + " WHERE code='admin'");
            long auditBefore = auditCount(jdbc);
            assertThatThrownBy(() -> run(startup)).isInstanceOf(IllegalStateException.class);
            assertThat(adminCount(jdbc)).isZero();
            assertThat(adminGrants(jdbc)).isZero();
            assertThat(auditCount(jdbc)).isEqualTo(auditBefore);
        }
    }

    @Test void absentInitialPasswordDoesNotCreateAnAccount() throws Exception {
        try (var isolated = database(); var startup = initializer(isolated.jdbc().getDataSource(), "")) {
            long auditBefore = auditCount(isolated.jdbc()); run(startup);
            assertThat(adminCount(isolated.jdbc())).isZero();
            assertThat(auditCount(isolated.jdbc())).isEqualTo(auditBefore);
        }
    }

    private IsolatedRoleMigration database() throws Exception {
        return IsolatedRoleMigration.migrate(dataSource, databasePassword, "59");
    }

    private AnnotationConfigApplicationContext initializer(DataSource isolated, String password) throws Exception {
        // 两个独立上下文各自持有 Mapper、连接和事务代理，复用生产代码且不伪造返回值。
        var context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> isolated);
        var global = new GlobalConfig(); global.setBanner(false);
        global.setMetaObjectHandler(audit);
        global.setDbConfig(new GlobalConfig.DbConfig().setLogicDeleteValue("true").setLogicNotDeleteValue("false"));
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(SysUserMapper.class); configuration.addMapper(SysRoleMapper.class); configuration.addMapper(SysUserRoleMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(isolated); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
        SqlSessionFactory sessions = factory.getObject();
        context.registerBean(SqlSessionTemplate.class, () -> new SqlSessionTemplate(sessions));
        context.registerBean(SysUserMapper.class, () -> context.getBean(SqlSessionTemplate.class).getMapper(SysUserMapper.class));
        context.registerBean(SysRoleMapper.class, () -> context.getBean(SqlSessionTemplate.class).getMapper(SysRoleMapper.class));
        context.registerBean(SysUserRoleMapper.class, () -> context.getBean(SqlSessionTemplate.class).getMapper(SysUserRoleMapper.class));
        context.registerBean(AdminInitializer.class, () -> new AdminInitializer(context.getBean(SysUserMapper.class),
                context.getBean(SysRoleMapper.class), context.getBean(SysUserRoleMapper.class), passwords, password));
        context.refresh();
        return context;
    }

    private void run(AnnotationConfigApplicationContext context) {
        context.getBean(AdminInitializer.class).run(new DefaultApplicationArguments());
    }

    private long adminCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_user WHERE username='admin'", Long.class);
    }

    private long adminGrants(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_user_role ur JOIN sys_user u ON u.id=ur.user_id JOIN sys_role r ON r.id=ur.role_id WHERE u.username='admin' AND r.code='admin'", Long.class);
    }

    private long auditCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_data_log", Long.class);
    }

    private Map<String, Object> snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForMap("SELECT id,password,status,deleted,session_version,nickname FROM sys_user WHERE username='admin'");
    }

    private void assertUnchanged(JdbcTemplate jdbc, Map<String, Object> before) {
        assertThat(snapshot(jdbc).equals(before)).as("existing identity, password, status and version remain unchanged").isTrue();
    }

    // 仅由上面的隔离上下文显式注册；不让 @SpringBootTest 把它识别为主应用配置。
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
