package com.lingmao.scm.common.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 每个新连接都有服务器端硬超时，HTTP 线程异常退出也不会无限占用锁。 */
@Configuration(proxyBeanMethods = false)
public class DatabaseTimeouts {
    @Bean
    static BeanPostProcessor databaseTimeoutsPostProcessor(Environment env) {
        int statement = bounded(env, "lingmao.db.statement-timeout-ms", 30_000, 1_000, 300_000);
        int lock = bounded(env, "lingmao.db.lock-timeout-ms", 5_000, 100, 30_000);
        int idle = bounded(env, "lingmao.db.idle-transaction-timeout-ms", 30_000, 1_000, 300_000);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource pool) {
                    pool.setConnectionTimeout(10_000);
                    pool.setValidationTimeout(3_000);
                    pool.setConnectionInitSql("SET statement_timeout = '" + statement + "ms'; SET lock_timeout = '"
                            + lock + "ms'; SET idle_in_transaction_session_timeout = '" + idle + "ms'");
                }
                return bean;
            }
        };
    }

    private static int bounded(Environment env, String key, int fallback, int min, int max) {
        int value = env.getProperty(key, Integer.class, fallback);
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出允许范围");
        return value;
    }
}
