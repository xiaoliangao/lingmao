package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual TCP, PostgreSQL and XLSX checks for the eleven existing independent business lists. */
@Import(S2TagFiltersHttpIT.ProbeConfig.class)
class S2TagFiltersHttpIT extends S2SampleFixture {
    @Autowired SqlProbe probe;
    @Autowired com.lingmao.scm.module.order.service.OrderService orders;

    record Target(String type, long id, Long company, String endpoint, String query, String code, boolean export) {
        String url(String suffix, String filter) { return endpoint + suffix + "?" + query + "&" + filter; }
    }

    @Test void elevenListsApplyAndBeforePaginationAndNineExportsUseTheSameIntersection() throws Exception {
        Fixture f = fixture(); Account actor = actor(f, "SELF");
        // Each real reader stays below the production export quota; no limiter is disabled.
        List<Account> exportReaders = List.of(actor(f, "ALL"), actor(f, "ALL")); int exportIndex = 0;
        String a = "filter-a-" + suffix(), b = "filter-b-" + suffix(), c = "filter-c-" + suffix();
        List<Target> targets = targets(f, actor);
        JsonNode saved = null;
        for (Target target : targets) saved = attach(actor, target, List.of(a, b));
        String both = ids(saved.path("tags"));
        long disjoint = id(attach(actor, styleCopy(f), List.of(c)).path("tags").get(0).path("id"));
        String andNone = saved.path("tags").get(0).path("id").stringValue() + "," + disjoint;
        List<Object> evidence = new ArrayList<>();
        for (Target target : targets) {
            JsonNode first = ok(actor, "GET", target.url("", "tagIds=" + both + "&size=1"), null, target.company());
            assertPage(first, 1, target.id());
            JsonNode next = ok(actor, "GET", target.url("", "tagIds=" + both + "&size=1&page=2"), null, target.company());
            assertPage(next, 1);
            assertPage(ok(actor, "GET", target.url("", "tagIds=" + both + "," + both), null, target.company()), 1, target.id());
            assertPage(ok(actor, "GET", target.url("", "tagIds=" + andNone), null, target.company()), 0);
            assertPage(ok(actor, "GET", target.url("", "tagIds=" + both + "&tagFilter.tenant=other&tagFilter.ids=9223372036854775807"), null, target.company()), 1, target.id());
            // Filtering must not relax cost/PII projection. These values are in the actual fixture rows.
            assertThat(first.toString()).doesNotContain("456.78", "987.65", "777.77", "6228480000000012345");
            if (target.type().equals("MATERIAL")) assertThat(first.path("list").get(0).has("refPrice")).isFalse();
            if (target.export()) {
                Account reader = exportReaders.get(exportIndex++ % exportReaders.size());
                assertThat(export(reader, target.url("/export", "tagIds=" + both), target.company())).contains(target.code());
                assertThat(export(reader, target.url("/export", "tagIds=" + andNone), target.company())).doesNotContain(target.code());
            }
            evidence.add(map("type", target.type(), "pageOne", first, "pageTwo", next, "andDisjointTotal", 0,
                    "exportChecked", target.export()));
        }
        writeEvidence("eleven-lists", evidence);
    }

    @Test void selfScopeCompanyCountersTotalsAndExportsRemainIntersected() throws Exception {
        Fixture f = fixture(); Account owner = actor(f, "SELF"), outsider = actor(f, "SELF");
        String name = "scope-filter-" + suffix();
        List<Target> own = targets(f, owner).stream().filter(t -> t.company() != null).toList();
        List<Target> hidden = targets(f, outsider).stream().filter(t -> t.company() != null).toList();
        String tag = null;
        for (Target target : own) tag = attach(owner, target, List.of(name)).path("tags").get(0).path("id").stringValue();
        for (Target target : hidden) attach(outsider, target, List.of(name));
        // Company filtering only applies where the original list has an explicit companyId contract.
        // Order/purchase lists intentionally span companies within DataScope; the header is not an ACL.
        var foreignBody = unpricedSample(f, owner); foreignBody.put("companyId", f.otherCompany());
        JsonNode foreignSample = createSample(owner, foreignBody);
        Target foreign = new Target("SAMPLE", id(foreignSample.path("id")), f.otherCompany(), "/api/samples",
                "companyId=" + f.otherCompany(), foreignSample.path("code").stringValue(), false);
        attach(owner, foreign, List.of(name));
        for (Target target : own) {
            assertPage(ok(owner, "GET", target.url("", "tagIds=" + tag), null, f.company()), 1, target.id());
            Target invisible = hidden.stream().filter(t -> t.type().equals(target.type())).findFirst().orElseThrow();
            if (target.export()) {
                String sheet = export(owner, target.url("/export", "tagIds=" + tag), f.company());
                assertThat(sheet).contains(target.code()).doesNotContain(invisible.code(), foreign.code());
            }
        }
        JsonNode counts = ok(owner, "GET", "/api/orders/counts?tagIds=" + tag, null, f.company());
        assertThat(counts.path("DRAFT").longValue()).isEqualTo(1);
        // Totals is an internal homepage service query, not a public /orders/totals route.
        authenticate(owner);
        var totalsQuery = new com.lingmao.scm.module.order.dto.OrderQuery(); totalsQuery.setTagIds(List.of(Long.valueOf(tag)));
        JsonNode totals = json.valueToTree(orders.totals(totalsQuery));
        assertThat(totals.path("count").longValue()).isEqualTo(1); assertThat(totals.path("qty").longValue()).isEqualTo(10);
        assertThat(ok(owner, "GET", "/api/production-orders/counts?kind=OUTSOURCE&tagIds=" + tag, null, f.company()).path("DRAFT").longValue()).isEqualTo(1);
        assertThat(ok(owner, "GET", "/api/samples/counts?companyId=" + f.company() + "&tagIds=" + tag, null, f.company()).path("ALL").longValue()).isEqualTo(1);
        assertPage(ok(owner, "GET", foreign.url("", "tagIds=" + tag), null, f.otherCompany()), 1, foreign.id());
        // An explicit selected export ID is also intersected; it does not bypass SELF or the tag predicate.
        Target ownOrder = own.stream().filter(t -> t.type().equals("ORDER")).findFirst().orElseThrow();
        Target hiddenOrder = hidden.stream().filter(t -> t.type().equals("ORDER")).findFirst().orElseThrow();
        assertThat(call(owner, "GET", ownOrder.url("/export", "tagIds=" + tag + "&ids=" + hiddenOrder.id()), null, f.company()).path("code").intValue()).isEqualTo(404);
        writeEvidence("scope-counters-export", map("httpCounts", counts, "serviceTotals", totals, "hiddenTargets", hidden.size(), "foreignSampleExcluded", true));
    }

    @Test void archivedIdKeepsItsHistoricalResultsAndSameNameRecreationIsSeparate() throws Exception {
        Fixture f = fixture(); Account actor = actor(f, "SELF"); String name = "archived-filter-" + suffix();
        Target old = style(f.style()), replacement = styleCopy(f);
        JsonNode original = attach(actor, old, List.of(name)); String oldId = ids(original.path("tags"));
        JsonNode warmed = ok(actor, "GET", "/api/tags/filter-options?keyword=" + name, null, null);
        assertThat(warmed.get(0).path("archived").booleanValue()).isFalse();
        ok(actor, "POST", "/api/tags/" + oldId + "/archive", Map.of("version", 0), null);
        String newId = ids(attach(actor, replacement, List.of(name)).path("tags"));
        assertThat(newId).isNotEqualTo(oldId);
        JsonNode options = ok(actor, "GET", "/api/tags/filter-options?keyword=" + name, null, null);
        assertThat(options.size()).isEqualTo(2);
        assertThat(ids(options)).contains(oldId, newId);
        assertThat(options.get(0).path("archived").booleanValue() || options.get(1).path("archived").booleanValue()).isTrue();
        assertThat(ids(ok(actor, "GET", "/api/tags?keyword=" + name, null, null))).isEqualTo(newId);
        assertThat(ids(ok(actor, "GET", "/api/tags/filter-options?keyword=" + name, null, null))).isEqualTo(ids(options));
        assertThat(ids(ok(actor, "GET", "/api/tags/filter-options?keyword=unmatched&includeIds=" + oldId + "&size=1", null, null))).isEqualTo(oldId);
        assertPage(ok(actor, "GET", old.url("", "tagIds=" + oldId), null, null), 1, old.id());
        assertPage(ok(actor, "GET", old.url("", "tagIds=" + newId), null, null), 1, replacement.id());
        assertPage(ok(actor, "GET", old.url("", "tagIds=" + oldId + "," + newId), null, null), 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_link WHERE tag_id=?", Long.class, Long.valueOf(oldId))).isEqualTo(1);
        writeEvidence("archived-history", map("oldId", oldId, "newId", newId, "filterOptions", options));
    }

    @Test void filterInputsAreBoundedAuthorizedAndCannotChooseAnotherTenant() throws Exception {
        Fixture f = fixture(); Account actor = actor(f, "SELF");
        String tag = ids(attach(actor, style(f.style()), List.of("input-filter-" + suffix())).path("tags"));
        Account noTags = custom(f.company(), "SELF", "style:style:list");
        assertThat(call(noTags, "GET", "/api/styles?tagIds=" + tag, null, null).path("code").intValue()).isEqualTo(403);
        ok(noTags, "GET", "/api/styles", null, null);
        ok(noTags, "GET", "/api/styles?tagIds=", null, null);
        assertThat(call(noTags, "GET", "/api/tags/filter-options", null, null).path("code").intValue()).isEqualTo(403);
        for (String value : List.of("-1", "0", "no-id", String.join(",", java.util.Collections.nCopies(31, tag)))) {
            assertThat(call(actor, "GET", "/api/styles?tagIds=" + value, null, null).path("code").intValue()).isEqualTo(400);
        }
        assertThat(call(actor, "GET", "/api/styles?tagIds=" + tag + ",9223372036854775807", null, null).path("code").intValue()).isEqualTo(404);
        // This schema has one trusted deployment row; it cannot host a second legal tenant.
        long foreign = Long.MAX_VALUE;
        assertThat(call(actor, "GET", "/api/styles?tagIds=" + foreign + "&tenantKey=other", null, null).path("code").intValue()).isEqualTo(404);
        assertThat(call(actor, "GET", "/api/tags/filter-options?includeIds=" + foreign, null, null).path("code").intValue()).isEqualTo(404);
        assertPage(ok(actor, "GET", "/api/styles?tagIds=" + tag + "&tenantKey=other", null, null), 1, f.style());
        // Unknown bean properties cannot replace the server-prepared immutable filter.
        JsonNode forged = call(actor, "GET", "/api/styles?tagIds=" + tag + "&tagFilter.tenant=other&tagFilter.ids=" + foreign, null, null);
        assertThat(forged.path("code").intValue()).isIn(0, 400);
        if (forged.path("code").intValue() == 0) assertPage(forged.path("data"), 1, f.style());
        assertThat(call(actor, "GET", "/api/tags/filter-options?size=101", null, null).path("code").intValue()).isEqualTo(400);
        assertThat(call(actor, "GET", "/api/tags/filter-options?includeIds=" + String.join(",", java.util.Collections.nCopies(101, tag)), null, null).path("code").intValue()).isEqualTo(400);
        writeEvidence("validation-and-permission", map("forgedInternalFilter", forged, "foreignTagRejected", true, "rawLimit", 30));
    }

    @Test void filteringAddsNoPerRowQueriesAndUsesBoundSqlBeforeLimit() throws Exception {
        Fixture f = fixture(); Account actor = actor(f, "SELF"); String name = "batch-filter-" + suffix();
        String tag = ids(attach(actor, style(f.style()), List.of(name)).path("tags"));
        for (int i = 0; i < 5; i++) attach(actor, styleCopy(f), List.of(name));
        List<Object> evidence = new ArrayList<>();
        for (int size : List.of(1, 6)) {
            probe.statements.clear(); probe.active = true;
            JsonNode page;
            try { page = ok(actor, "GET", "/api/styles?tagIds=" + tag + "&size=" + size, null, null); }
            finally { probe.active = false; }
            assertThat(page.path("total").longValue()).isEqualTo(6);
            assertThat(page.path("list").size()).isEqualTo(size);
            List<String> tagSql = probe.statements.stream().filter(sql -> sql.contains("biz_tag_object")).toList();
            assertThat(tagSql).hasSize(2); // one COUNT and one page, independent of result rows
            assertThat(tagSql).allSatisfy(sql -> assertThat(sql.replaceAll("\\s+", "")).contains("tag_object.tenant_key=?", "tag_link.tag_idIN", "tag_object.object_type='STYLE'"));
            assertThat(tagSql).filteredOn(sql -> sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT COUNT("))
                    .singleElement().satisfies(sql -> assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("LIMIT"));
            assertThat(tagSql).filteredOn(sql -> sql.toUpperCase(Locale.ROOT).contains("LIMIT"))
                    .singleElement().satisfies(sql -> assertThat(sql.indexOf("biz_tag_object")).isLessThan(sql.toUpperCase(Locale.ROOT).indexOf("LIMIT")));
            assertThat(probe.statements.stream().filter(sql -> sql.contains("biz_tag_link") && !sql.contains("sty_style"))).isEmpty();
            evidence.add(map("pageSize", size, "total", page.path("total"), "filteredQueries", tagSql));
        }
        writeEvidence("bounded-sql", evidence);
    }

    private Account actor(Fixture f, String scope) throws Exception {
        return custom(f.company(), scope, "biz:tag:list", "biz:tag:manage", "style:style:list", "style:style:edit", "style:style:export",
                "basic:material:list", "basic:material:edit", "basic:material:export", "supplier:supplier:list", "supplier:supplier:edit", "supplier:supplier:export",
                "order:order:list", "order:order:edit", "order:order:export", "purchase:mrp:list", "purchase:mrp:edit",
                "purchase:order:list", "purchase:order:edit", "purchase:order:export", "inventory:doc:list", "inventory:doc:edit", "inventory:doc:export",
                "inventory:count:list", "inventory:count:edit", "inventory:count:export", "production:order:list", "production:order:edit", "production:order:export",
                "fg:doc:list", "fg:doc:edit", "fg:doc:export", "dev:sample:list", "dev:sample:add", "dev:sample:edit");
    }

    private List<Target> targets(Fixture f, Account actor) throws Exception {
        long order = sourceOrder(f, actor, "DRAFT", f.company()), source = sourceOrder(f, actor, "APPROVED", f.company());
        long purchase = sourcePurchase(f, actor); jdbc.update("UPDATE pur_order SET status='DRAFT' WHERE id=?", purchase);
        long production = sourceProduction(f, actor, source, "DRAFT");
        String mrpNo = "TGFMRP" + suffix(), invNo = "TGFINV" + suffix(), countNo = "TGFCNT" + suffix(), fgNo = "TGFFG" + suffix();
        long mrp = jdbc.queryForObject("INSERT INTO mrp_calc(no,company_id,status,create_by) VALUES(?,?,'CONFIRMED',?) RETURNING id", Long.class, mrpNo, f.company(), actor.id());
        jdbc.update("INSERT INTO mrp_calc_order(calc_id,order_id) VALUES(?,?)", mrp, source);
        long inv = jdbc.queryForObject("INSERT INTO inv_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, invNo, f.company(), f.warehouse(), actor.id());
        jdbc.update("INSERT INTO inv_doc_item(doc_id,seq,material_sku_id,qty) VALUES(?,1,?,2)", inv, f.materialSku());
        long countWarehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,'Tag filter count','MATERIAL') RETURNING id", Long.class, "TGFCOUNT" + suffix());
        long count = jdbc.queryForObject("INSERT INTO inv_count(no,company_id,warehouse_id,count_date,counter_id,status,create_by) VALUES(?,?,?,CURRENT_DATE,?,'DRAFT',?) RETURNING id", Long.class, countNo, f.company(), countWarehouse, actor.id(), actor.id());
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,'Tag filter finished goods','PRODUCT') RETURNING id", Long.class, "TGFW" + suffix());
        long fg = jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status,create_by) VALUES(?,?,'IN','OTHER_IN',?,CURRENT_DATE,'DRAFT',?) RETURNING id", Long.class, fgNo, f.company(), warehouse, actor.id());
        jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES(?,1,?,2)", fg, f.styleSku());
        JsonNode sample = createSample(actor, unpricedSample(f, actor));
        return List.of(style(f.style()), new Target("MATERIAL", f.material(), null, "/api/materials", "", f.materialCode(), true),
                new Target("SUPPLIER", f.supplier(), null, "/api/suppliers", "", code("sup_supplier", f.supplier()), true), order(order, f.company()),
                new Target("MRP_CALC", mrp, f.company(), "/api/mrp-calcs", "", mrpNo, false),
                new Target("PURCHASE_ORDER", purchase, f.company(), "/api/purchase-orders", "", no("pur_order", purchase), true),
                new Target("INV_DOC", inv, f.company(), "/api/inventory-docs", "kind=IN", invNo, true),
                new Target("INV_COUNT", count, f.company(), "/api/inventory-counts", "", countNo, true),
                new Target("PRODUCTION_ORDER", production, f.company(), "/api/production-orders", "kind=OUTSOURCE", no("prd_order", production), true),
                new Target("FG_DOC", fg, f.company(), "/api/fg-docs", "kind=IN&companyId=" + f.company(), fgNo, true),
                new Target("SAMPLE", id(sample.path("id")), f.company(), "/api/samples", "companyId=" + f.company(), sample.path("code").stringValue(), false));
    }
    private Target style(long id) { return new Target("STYLE", id, null, "/api/styles", "", code("sty_style", id), true); }
    private Map<String, Object> unpricedSample(Fixture f, Account actor) {
        var body = sampleBody(f, actor); body.remove("estCost"); body.remove("estTagPrice"); return body;
    }
    private Target order(long id, long company) { return new Target("ORDER", id, company, "/api/orders", "", no("ord_order", id), true); }
    private Target styleCopy(Fixture f) {
        return style(jdbc.queryForObject("INSERT INTO sty_style(code,name,style_class,brand,year,season,source,category_id,size_group_id,status) VALUES(?,'Filter copy','FS','TG',2026,'AUTUMN','OWN',?,?,'DRAFT') RETURNING id", Long.class, "TGFST" + suffix(), f.category(), f.sizeGroup()));
    }
    private String code(String table, long id) { return jdbc.queryForObject("SELECT code FROM " + table + " WHERE id=?", String.class, id); }
    private String no(String table, long id) { return jdbc.queryForObject("SELECT no FROM " + table + " WHERE id=?", String.class, id); }
    private JsonNode attach(Account actor, Target target, List<String> names) throws Exception {
        return ok(actor, "POST", "/api/tags/objects/" + target.type() + "/" + target.id() + "/replace",
                map("version", 0, "names", names, "tagVersions", Map.of(), "retainIds", List.of()), target.company());
    }
    private static String ids(JsonNode tags) {
        List<String> values = new ArrayList<>(); tags.forEach(tag -> values.add(tag.path("id").stringValue())); return String.join(",", values);
    }
    private static void assertPage(JsonNode page, long total, long... expectedIds) {
        assertThat(page.path("total").longValue()).as(page.toString()).isEqualTo(total);
        List<Long> actual = new ArrayList<>(); page.path("list").forEach(row -> actual.add(id(row.path("id"))));
        assertThat(actual).containsExactly(java.util.Arrays.stream(expectedIds).boxed().toArray(Long[]::new));
    }
    private String export(Account actor, String url, Long company) throws Exception {
        var response = raw(actor, "GET", url, null, company, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("spreadsheetml");
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(response.body()))) {
            DataFormatter formatter = new DataFormatter(); List<String> cells = new ArrayList<>();
            book.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> cells.add(formatter.formatCellValue(cell)))));
            return cells.stream().collect(Collectors.joining("\n"));
        }
    }
    private void writeEvidence(String name, Object value) throws Exception {
        Path dir = Path.of("target/a2-tags/filters"); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    @TestConfiguration static class ProbeConfig { @Bean SqlProbe tagFilterSqlProbe() { return new SqlProbe(); } }
    @Aspect
    static class SqlProbe {
        volatile boolean active;
        final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();
        // COUNT MappedStatements can retain an earlier Spring context's MyBatis Configuration.
        // The shared interceptor is installed in every test context; enter its capture scope
        // on the actual HTTP service thread so cached COUNT and current page SQL are both seen.
        @Around("execution(* com.lingmao.scm.module.style.service.impl.StyleServiceImpl.page(..))")
        Object capturePage(ProceedingJoinPoint point) throws Throwable {
            if (!active) return point.proceed();
            var captured = SqlStatementCapture.capture(() -> {
                try { return new Attempt(point.proceed(), null); }
                catch (Throwable failure) { return new Attempt(null, failure); }
            });
            captured.statements().forEach(sql -> statements.add(sql.getSql()));
            if (captured.result().failure() != null) throw captured.result().failure();
            return captured.result().value();
        }
        private record Attempt(Object value, Throwable failure) { }
    }
}
