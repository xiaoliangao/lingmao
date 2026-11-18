package com.lingmao.scm.config;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 共用一个 schema 的安装不能悄悄变成多租户隔离库。 */
@Component
@DependsOnDatabaseInitialization
public class DeploymentNamespaceGuard implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final boolean multiTenant;
    private final boolean companyIsolation;

    public DeploymentNamespaceGuard(JdbcTemplate jdbc, DeploymentNamespace namespace,
            @Value("${lingmao.multitenancy.enabled:false}") boolean multiTenant,
            @Value("${lingmao.company-isolation.enabled:false}") boolean companyIsolation) {
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.multiTenant = multiTenant;
        this.companyIsolation = companyIsolation;
    }

    @Override public void afterPropertiesSet() {
        if (multiTenant || companyIsolation) throw new IllegalStateException(
                "This schema supports one trusted deployment namespace and shared company data only; isolated tenancy is not implemented");
        jdbc.update("INSERT INTO sys_deployment_namespace(singleton,tenant_id) VALUES(true,?) ON CONFLICT(singleton) DO NOTHING", namespace.tenant());
        String persisted = jdbc.queryForObject("SELECT tenant_id FROM sys_deployment_namespace WHERE singleton", String.class);
        if (!namespace.tenant().equals(persisted)) throw new IllegalStateException("Deployment namespace does not match this database");
    }
}
