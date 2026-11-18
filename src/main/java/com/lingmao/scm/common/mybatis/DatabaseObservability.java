package com.lingmao.scm.common.mybatis;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class DatabaseObservability {
    @Bean static BeanPostProcessor observedDatabase(Environment env) {
        long slow = env.getProperty("lingmao.observability.slow-sql-ms", Long.class, 1000L);
        if (slow < 1 || slow > 60_000) throw new IllegalArgumentException("slow-sql-ms must be within 1..60000");
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource dataSource && !(bean instanceof ObservedDataSource)
                        ? new ObservedDataSource(dataSource, slow) : bean;
            }
        };
    }
}
