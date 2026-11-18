package com.lingmao.scm.common.mybatis;

import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在线索引要等其他旧事务结束；Flyway 自身必须用会话锁，不能持有等待自己的旧事务。 */
@Configuration(proxyBeanMethods = false)
public class OnlineMigrationConfiguration {
    @Bean
    FlywayConfigurationCustomizer postgresOnlineMigrationLock() {
        return configuration -> configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
                .setTransactionalLock(false);
    }
}
