package com.lingmao.scm;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 集成测试启动时怎么准备数据库（@IntegrationTest 引入）：先确认连的是测试库，再清空、重新执行全部迁移。
 * 清空靠 Flyway 的 clean：删掉库里的所有表、索引、函数，包括 flyway_schema_history，再从 V1 执行一遍。
 * 这样每次跑的都是"全新的库 + 全部迁移"，迁移本身也一起验证了。
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestConfig {

    /** 一次 mvn verify 只清一次：万一哪个测试的配置不一样、Spring 又起了一个应用，不能把前面测试正在用的数据清掉 */
    private static final AtomicBoolean CLEANED = new AtomicBoolean();

    /**
     * Spring Boot 启动时执行迁移，有这个 bean 就用它代替默认的 migrate()。
     * 清空之前查库名：配置被环境变量（SPRING_DATASOURCE_URL）之类的盖掉、连到了开发库 scm 或者别的库，直接启动失败，
     * 一条语句都不执行。应用用的库和 Flyway 用的库都查：Flyway 可以单独配地址
     */
    @Bean
    FlywayMigrationStrategy cleanTestDatabaseThenMigrate(DataSource dataSource) {
        return flyway -> {
            ensureTestDatabase(dataSource);
            ensureTestDatabase(flyway.getConfiguration().getDataSource());
            if (CLEANED.compareAndSet(false, true)) {
                flyway.clean();
            }
            flyway.migrate();
        };
    }

    private static void ensureTestDatabase(DataSource dataSource) {
        String name = new JdbcTemplate(dataSource).queryForObject("SELECT current_database()", String.class);
        if (name == null || !name.endsWith("_test")) {
            throw new IllegalStateException("集成测试只能连名字以 _test 结尾的库（会先清空它），现在连的是 " + name
                    + "。检查 src/test/resources/application-it.yaml 和环境变量 SPRING_DATASOURCE_URL");
        }
    }
}
