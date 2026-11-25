package com.lingmao.scm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 已有的安装可以去掉初始密码；全新的生产库（从来没有过 admin，删了的也算有过，同 AdminInitializer）不行。 */
@Component @Profile("prod") @Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionAdminGuard implements ApplicationRunner {
    private final JdbcTemplate jdbc; private final String password;
    public ProductionAdminGuard(JdbcTemplate jdbc,@Value("${lingmao.init-admin-password:}") String password) {
        this.jdbc=jdbc; this.password=password;
    }
    @Override public void run(ApplicationArguments ignored) {
        if (password.isBlank() && Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM sys_user WHERE username='admin')",Boolean.class)))
            throw new IllegalStateException("Production configuration rejected: INIT_ADMIN_PASSWORD is required for a fresh database");
    }
}
