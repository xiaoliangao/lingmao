package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 按款接口不能接收明细 ID 后静默丢弃；混主体明细导出必须整批拒绝。 */
class S2FgQueryContractsHttpIT extends A2BusinessFixture {
    @Test void pendingReceiptDateRangeRejectsReversedCalendarDates() throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "fg:doc:add");
        var response = raw(reader, "GET", "/api/fg-docs/pending-receipts?companyId=" + f.company()
                + "&receiptDateFrom=2026-11-22&receiptDateTo=2026-11-21", null, f.company(), null);
        JsonNode body = node(response);
        Path evidence = Path.of("test-artifacts/full-audit/fg/root-receipt-date-contract.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, json.writerWithDefaultPrettyPrinter().writeValueAsString(map(
                "httpStatus", response.statusCode(), "body", body,
                "requestId", response.headers().firstValue("X-Request-Id").orElse(null))));
        assertThat(body.path("code").intValue()).isEqualTo(400);
        assertThat(body.path("msg").asText()).contains("开始日期不能晚于结束日期");
    }

    @Test void styleViewRejectsUnsupportedDetailIdsAndDetailExportRejectsMixedCompanies() throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "fg:stock:list", "fg:stock:export");
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'PRODUCT') RETURNING id",
                Long.class, "FGQ" + suffix(), "FG query contract warehouse");
        long own = jdbc.queryForObject("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty) VALUES(?,?,?,7) RETURNING id",
                Long.class, f.company(), warehouse, f.styleSku());
        long foreign = jdbc.queryForObject("INSERT INTO fg_stock(company_id,warehouse_id,style_sku_id,qty) VALUES(?,?,?,111) RETURNING id",
                Long.class, f.otherCompany(), warehouse, f.styleSku());

        JsonNode ordinary = ok(reader, "GET", "/api/fg-stocks/by-style?companyId=" + f.company()
                + "&warehouseId=" + warehouse + "&styleId=" + f.style(), null, f.company());
        assertThat(ordinary.path("list").size()).isOne();
        assertThat(ordinary.path("list").get(0).path("totalQty").longValue()).isEqualTo(7);
        List<Map<String, Object>> denied = new ArrayList<>();
        for (String endpoint : List.of("/api/fg-stocks/by-style", "/api/fg-stocks/by-style/export")) {
            for (String ids : List.of(Long.toString(own), own + "," + foreign)) {
                var response = raw(reader, "GET", endpoint + "?companyId=" + f.company() + "&ids=" + ids,
                        null, f.company(), null);
                JsonNode body = node(response);
                denied.add(map("endpoint", endpoint, "httpStatus", response.statusCode(), "body", body,
                        "requestId", response.headers().firstValue("X-Request-Id").orElse(null)));
                queryEvidence(map("normalCompanyScopedMatrix", ordinary, "unsupportedIds", denied));
                assertThat(body.path("code").intValue()).as("%s must not ignore detail IDs", endpoint).isEqualTo(400);
                assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
            }
        }
        var mixed = raw(reader, "GET", "/api/fg-stocks/export?companyId=" + f.company() + "&ids=" + own + "," + foreign,
                null, f.company(), null);
        JsonNode mixedBody = node(mixed);
        queryEvidence(map("normalCompanyScopedMatrix", ordinary, "unsupportedIds", denied,
                "mixedDetailExport", map("httpStatus", mixed.statusCode(), "body", mixedBody)));
        assertThat(mixedBody.path("code").intValue()).isEqualTo(404);
        assertThat(mixed.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_stock WHERE id=?", Integer.class, own)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT qty FROM fg_stock WHERE id=?", Integer.class, foreign)).isEqualTo(111);

    }

    private void queryEvidence(Map<String, Object> data) throws Exception {
        Path evidence = Path.of("test-artifacts/full-audit/fg/root-query-contracts.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, json.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }
}
