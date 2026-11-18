package com.lingmao.scm.common.scope;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 命名空间只来自部署配置，不取请求头。 */
@Component
public final class DeploymentNamespace {
    private final String tenant;

    public DeploymentNamespace(@Value("${lingmao.tenant-id:single}") String tenant) {
        if (tenant == null || !tenant.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("lingmao.tenant-id must contain 1–64 safe characters");
        }
        this.tenant = tenant;
    }

    public String tenant() { return tenant; }

    /** 主数据字典在这套部署里各主体共用，范围明确写出来。 */
    public String sharedKey(String domain, String value) {
        if (domain == null || !domain.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Invalid key domain");
        if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid key value");
        return "scm:" + tenant + ":company:shared:" + domain + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
