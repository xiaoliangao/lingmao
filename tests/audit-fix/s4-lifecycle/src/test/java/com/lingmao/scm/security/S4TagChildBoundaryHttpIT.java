package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 同一用例在未叠入新守卫的真实旧源码运行并变红；不模拟数据库或跳过生产鉴权。 */
class S4TagChildBoundaryHttpIT extends A2BusinessFixture {
    @Test void aPricePermissionNeverReplacesTheChildObjectsDomainReadPermission() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list", "order:order:edit");
        Account auditWithoutDomain = custom(f.company(), "ALL", "system:audit:query", FieldPermissions.ORDER);
        long order = sourceOrder(f, owner, "DRAFT", f.company());
        long item = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, order);
        String path = "/api/data-logs?table=ord_order_item&rowId=" + item;
        var response = raw(auditWithoutDomain, "GET", path, null, f.company(), null);
        JsonNode actual = node(response);
        var evidence = map("method", "GET", "path", path, "companyHeader", Long.toString(f.company()),
                "rolePermissions", permissions.loadPermissions(auditWithoutDomain.id()).stream().sorted().toList(),
                "httpStatus", response.statusCode(), "requestId", response.headers().firstValue("X-Request-Id").orElse(null),
                "actualResponse", actual, "realParent", Map.of("table", "ord_order", "id", Long.toString(order)));
        Path file = Path.of("test-artifacts/full-audit/tags/s4/child-boundary-http.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        assertThat(actual.path("code").intValue()).as("direct child audit must require order:order:list").isEqualTo(403);
        assertThat(actual.path("data").isNull()).isTrue();
    }
}
