package com.lingmao.scm;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用能在一个全新的库上起来：全部迁移从 V1 执行到最新，所有 bean 都建得出来，启动时建好了 admin。
 * 以前叫 ScmApplicationTests，连的是开发库 scm，会把还没提交的新迁移执行掉；现在连 scm_test，mvn test 也不再跑它
 */
@IntegrationTest
class ScmApplicationIT {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 迁移跑到了最新：没有待执行的，当前版本就是 db/migration 里最大的那个 */
    @Test
    void migratedToLatest() {
        MigrationInfo[] all = flyway.info().all();
        MigrationInfo current = flyway.info().current();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(current).isNotNull();
        assertThat(current.getVersion()).isEqualTo(Arrays.stream(all).map(MigrationInfo::getVersion).max(Comparable::compareTo).orElseThrow());
    }

    /** AdminInitializer 在空库上建了 admin，并给了超级管理员角色 */
    @Test
    void adminCreatedWithAdminRole() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_user u
                JOIN sys_user_role ur ON ur.user_id = u.id JOIN sys_role r ON r.id = ur.role_id
                WHERE u.username = 'admin' AND r.code = 'admin' AND u.deleted = false
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }
}
