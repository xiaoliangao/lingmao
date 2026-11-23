package com.lingmao.scm.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立旧守卫负控：没有新增 production class 依赖，先归档真实响应再断言。 */
class S4SharedAuditBoundaryHttpIT extends S4SharedAuditFixture {
    @ParameterizedTest(name = "{index}-{0}")
    @ValueSource(strings = {"bas_company", "bas_warehouse", "bas_customer", "inv_stock", "prc_price"})
    void globalAuditAndFinancialPermissionsNeverReplaceDomainRead(String table) throws Exception {
        Fixture f = fixture(); Target target = target(f, table);
        Account actor = custom(f.company(), "ALL", "system:audit:query", FieldPermissions.COST,
                FieldPermissions.PRODUCTION, FieldPermissions.ORDER, FieldPermissions.CONTACT, FieldPermissions.BANK);
        var response = raw(actor, "GET", path(target, false), null, null, null);
        var actual = node(response);
        evidence("old-guard-boundary-" + table + ".json", map("table", table, "rowId", Long.toString(target.id()),
                "actualPermissions", permissions.loadPermissions(actor.id()).stream().sorted().toList(),
                "httpStatus", response.statusCode(), "actualResponse", actual));
        int denied = table.equals("bas_customer") ? 404 : 403;
        assertThat(actual.path("code").intValue()).as("%s requires its real domain read policy", table).isEqualTo(denied);
        for (boolean root : new boolean[]{false, true})
            assertThat(call(actor, "GET", path(target, root) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(denied);
    }
}
