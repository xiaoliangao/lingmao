package com.lingmao.scm.common.scope;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Namespace comes only from deployment configuration, never from a request header. */
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

    /** Master dictionaries are shared across companies in this deployment. The scope is explicit. */
    public String sharedKey(String domain, String value) {
        if (domain == null || !domain.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Invalid key domain");
        if (value == null || value.length() > 512) throw new IllegalArgumentException("Invalid key value");
        return "scm:" + tenant + ":company:shared:" + domain + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
