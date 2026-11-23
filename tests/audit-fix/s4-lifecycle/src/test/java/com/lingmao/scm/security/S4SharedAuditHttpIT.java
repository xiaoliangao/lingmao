package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 共享旧模型的真实审计入口；通过也不代表具备公司成员 ACL 或启用了客户业务域。 */
class S4SharedAuditHttpIT extends S4SharedAuditFixture {
    @Test void sharedMastersRequireBasicReadAndStillStripContactFields() throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "system:audit:query", "basic:basics:list");
        Account contact = custom(f.company(), "ALL", "system:audit:query", "basic:basics:list", FieldPermissions.CONTACT);
        List<Object> results = new ArrayList<>();
        for (String table : List.of("bas_company", "bas_warehouse")) {
            Target target = target(f, table);
            JsonNode hidden = history(reader, target, false, null);
            assertThat(hidden.isEmpty()).isFalse();
            assertThat(hidden.toString()).doesNotContain("phone", "address", "credit_code", "13800138000", "SYNTHETIC-TAX");
            JsonNode allowed = history(contact, target, true, null);
            assertThat(allowed.get(0).path("diff").path("phone").path("new").asText()).isEqualTo("13800138000");
            // These real tables have no company-owner column; headers do not manufacture one.
            assertThat(history(reader, target, false, f.company())).isEqualTo(hidden);
            assertThat(history(reader, target, false, f.otherCompany())).isEqualTo(hidden);
            jdbc.update("UPDATE " + table + " SET status=0 WHERE id=?", target.id());
            JsonNode disabled = history(reader, target, false, null);
            assertThat(disabled.get(0).path("diff").path("status").path("new").intValue()).isZero();
            jdbc.update("DELETE FROM " + table + " WHERE id=?", target.id());
            assertThat(jdbc.queryForObject("SELECT action FROM sys_data_log WHERE tenant_key=? AND table_name=? AND row_id=? ORDER BY id DESC LIMIT 1",
                    String.class, namespace.tenant(), table, target.id())).isEqualTo("DELETE");
            for (boolean root : new boolean[]{false, true})
                assertThat(call(reader, "GET", path(target, root) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(404);
            results.add(map("table", table, "hiddenActualJson", hidden, "contactActualJson", allowed,
                    "disabledActualJson", disabled, "scope", "existing shared deployment master; not company membership ACL"));
        }
        evidence("shared-master-contact-and-deletion.json", results);
    }

    @Test void stockReadsUseARealBalanceAndKeepExistingSharedWarehouseScope() throws Exception {
        Fixture f = fixture(); Target target = target(f, "inv_stock");
        Account reader = custom(f.company(), "ALL", "system:audit:query", "inventory:stock:list");
        jdbc.update("UPDATE inv_stock SET qty=13.125,movement_revision=movement_revision+1 WHERE id=?", target.id());
        JsonNode rows = history(reader, target, false, null);
        assertThat(rows.get(0).path("diff").path("qty").path("old").decimalValue()).isEqualByComparingTo("12.125");
        assertThat(rows.get(0).path("diff").path("qty").path("new").decimalValue()).isEqualByComparingTo("13.125");
        assertThat(history(reader, target, true, f.otherCompany())).isEqualTo(rows);
        assertThat(call(reader, "GET", path(new Target("inv_stock", Long.MAX_VALUE), false), null, null).path("code").intValue()).isEqualTo(404);
        jdbc.update("DELETE FROM inv_stock WHERE id=?", target.id());
        assertThat(call(reader, "GET", path(target, false), null, null).path("code").intValue()).isEqualTo(404);
        evidence("shared-stock-real-row.json", map("history", rows, "scope", "warehouse/SKU/ownership balance has no company-owner column"));
    }

    @Test void actualMaterialAndProcessTypesRequireTheirOwnPricePermissions() throws Exception {
        Fixture f = fixture(); Target material = price(f, false), process = price(f, true);
        Account cost = custom(f.company(), "ALL", "system:audit:query", "price:price:list", FieldPermissions.COST);
        Account production = custom(f.company(), "ALL", "system:audit:query", "price:price:list", FieldPermissions.PRODUCTION);
        Account orderOnly = custom(f.company(), "ALL", "system:audit:query", "price:price:list", FieldPermissions.ORDER);
        Account noField = custom(f.company(), "ALL", "system:audit:query", "price:price:list");
        List<Object> results = new ArrayList<>();
        for (Target target : List.of(material, process)) {
            boolean isMaterial = target.equals(material);
            Account allowed = isMaterial ? cost : production, wrongDomain = isMaterial ? production : cost;
            JsonNode rows = history(allowed, target, false, null);
            assertThat(rows.get(0).path("diff").path("price").path("new").decimalValue())
                    .isEqualByComparingTo(isMaterial ? "23.4567" : "17.1234");
            String domain = jdbc.queryForObject("SELECT price_domain FROM sys_data_log WHERE tenant_key=? AND table_name='prc_price' AND row_id=? ORDER BY id DESC LIMIT 1",
                    String.class, namespace.tenant(), target.id());
            assertThat(domain).isEqualTo(isMaterial ? "COST" : "PRODUCTION");
            for (Account denied : List.of(wrongDomain, orderOnly, noField)) {
                for (boolean root : new boolean[]{false, true}) {
                    JsonNode refused = call(denied, "GET", path(target, root), null, null);
                    assertThat(refused.path("code").intValue()).isEqualTo(403);
                    assertThat(refused.path("data").isNull()).isTrue();
                    assertThat(refused.toString()).doesNotContain("\"diff\"", "\"price\"", "23.4567", "17.1234");
                    assertThat(call(denied, "GET", path(target, root) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(403);
                }
            }
            jdbc.update("UPDATE prc_price SET voided=true,void_reason='synthetic correction' WHERE id=?", target.id());
            JsonNode voided = history(allowed, target, true, null);
            assertThat(voided.get(0).path("diff").path("voided").path("new").asBoolean()).isTrue();
            results.add(map("type", isMaterial ? "MATERIAL" : "PROCESS", "actualDomain", domain,
                    "readerPermissions", permissions.loadPermissions(allowed.id()).stream().sorted().toList(), "history", rows, "voidedHistory", voided));
            jdbc.update("DELETE FROM prc_price WHERE id=?", target.id());
            assertThat(call(allowed, "GET", path(target, false), null, null).path("code").intValue()).isEqualTo(404);
        }
        evidence("actual-price-domains.json", results);
    }

    @Test void dormantCustomerRemainsClosedEvenWithEveryOtherSharedReadPermission() throws Exception {
        Fixture f = fixture(); Target target = target(f, "bas_customer");
        Account reader = custom(f.company(), "ALL", "system:audit:query", "basic:basics:list", "inventory:stock:list",
                "price:price:list", FieldPermissions.COST, FieldPermissions.PRODUCTION, FieldPermissions.CONTACT);
        List<Object> results = new ArrayList<>();
        for (boolean root : new boolean[]{false, true}) {
            JsonNode refused = call(reader, "GET", path(target, root), null, null);
            assertThat(refused.path("code").intValue()).isEqualTo(404);
            assertThat(refused.path("data").isNull()).isTrue();
            assertThat(call(reader, "GET", path(target, root) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(404);
            results.add(refused);
        }
        evidence("customer-not-enabled.json", map("realId", Long.toString(target.id()), "responses", results,
                "storedAuditCount", jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE tenant_key=? AND table_name='bas_customer' AND row_id=?", Long.class, namespace.tenant(), target.id())));
    }

    @Test void longIdsAndTrustedAuditNamespaceStayBoundWithoutCreatingBusinessTenancy() throws Exception {
        Fixture f = fixture(); Account reader = custom(f.company(), "ALL", "system:audit:query", "basic:basics:list");
        long id = 9_007_199_254_741_099L + Long.parseLong(suffix().substring(0,8), 16);
        jdbc.update("INSERT INTO bas_company(id,code,name,short_name) OVERRIDING SYSTEM VALUE VALUES(?,?,'Synthetic long-id shared company','S4')",
                id, suffix().substring(0,6).toUpperCase(java.util.Locale.ROOT));
        Target target = new Target("bas_company", id);
        String foreign = "audit_fixture_" + UUID.randomUUID(), marker = "s4-foreign-" + UUID.randomUUID();
        long foreignEvent = jdbc.queryForObject("""
                INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,request_id,transaction_id)
                SELECT ?,table_name,row_id,root_table,root_id,price_domain,action,diff,?,txid_current()
                FROM sys_data_log WHERE tenant_key=? AND table_name='bas_company' AND row_id=? ORDER BY id DESC LIMIT 1 RETURNING id
                """, Long.class, foreign, marker, namespace.tenant(), id);
        JsonNode rows = history(reader, target, false, null);
        assertThat(rows.isEmpty()).isFalse();
        for (JsonNode row : rows) {
            assertThat(row.path("id").isString()).isTrue();
            assertThat(row.path("rowId").isString()).isTrue();
            assertThat(row.path("rowId").asText()).isEqualTo(Long.toString(id));
            assertThat(row.path("id").asText()).isNotEqualTo(Long.toString(foreignEvent));
            assertThat(row.path("requestId").asText()).isNotEqualTo(marker);
        }
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM sys_deployment_namespace WHERE singleton", String.class)).isEqualTo(namespace.tenant());
        evidence("trusted-audit-namespace-and-long-id.json", map("actualResponse", rows, "foreignAuditFixtureId", Long.toString(foreignEvent),
                "foreignAuditFixtureNamespace", foreign, "persistedBusinessNamespace", namespace.tenant(),
                "evidenceBoundary", "query excludes an injected foreign audit row; no second business tenant was initialized"));
    }

    @Test void globalAuditAndAllScopeRemainMandatoryForSharedObjects() throws Exception {
        Fixture f = fixture(); Target target = target(f, "bas_company");
        Account self = custom(f.company(), "SELF", "system:audit:query", "basic:basics:list");
        Account noAudit = custom(f.company(), "ALL", "basic:basics:list");
        for (Account denied : List.of(self, noAudit)) for (boolean root : new boolean[]{false, true})
            assertThat(call(denied, "GET", path(target, root) + "&beforeId=1", null, null).path("code").intValue()).isEqualTo(403);
    }
}
