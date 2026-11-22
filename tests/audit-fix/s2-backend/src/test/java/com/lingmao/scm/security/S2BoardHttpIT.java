package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 真 TCP + PostgreSQL：聚合口径、授权、分页、排序、ID 协议均通过实际响应验收。 */
class S2BoardHttpIT extends A2BusinessFixture {
    @Test void homeBlocksHaveRealValuesAndOmitUnauthorizedDomains() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        long order = sourceOrder(f, owner, "APPROVED", f.company());
        jdbc.update("UPDATE ord_order SET required_date=?,commit_date=? WHERE id=?", today(), today(), order);
        JsonNode kpi = ok(owner, "GET", "/api/board/home/kpi", null, f.company());
        assertThat(kpi.path("order").path("qty").longValue()).isEqualTo(10);
        assertThat(kpi.path("order").path("count").longValue()).isEqualTo(1);
        assertThat(kpi.path("dev").isNull()).isTrue();
        assertThat(kpi.path("production").isNull()).isTrue();
        assertThat(kpi.path("material").isNull()).isTrue();
        JsonNode deliveries = ok(owner, "GET", "/api/board/home/deliveries", null, f.company());
        assertThat(deliveries.size()).isEqualTo(1);
        assertThat(deliveries.get(0).path("ref").path("id").isString()).isTrue();
        assertThat(id(deliveries.get(0).path("ref").path("id"))).isEqualTo(order);
        assertThat(deliveries.toString()).doesNotContain("price", "totalAmount", "refCost");
        assertThat(call(owner, "GET", "/api/board/home/todos?ownerId=not-a-long", null, f.company()).path("code").intValue()).isEqualTo(400);
        Account none = custom(f.company(), "SELF");
        JsonNode empty = ok(none, "GET", "/api/board/home/kpi", null, f.company());
        for (String block : List.of("dev", "order", "production", "material")) assertThat(empty.path(block).isNull()).isTrue();
        assertThat(ok(none, "GET", "/api/board/home/todos", null, f.company()).path("groups").isEmpty()).isTrue();
        evidence("low-permission-kpi.json", kpi);
        evidence("low-permission-deliveries.json", deliveries);
    }

    @Test void oldestSubmittedAuditIsSelectedBeforeLimitAndReviewerExceptionDoesNotGrantAll() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "order:order:list");
        Account reviewer = custom(f.company(), "SELF", "order:order:list", "order:order:audit");
        Account stranger = custom(f.company(), "SELF", "order:order:list", "order:order:audit");
        OffsetDateTime anchor = today().atStartOfDay().atOffset(ZoneOffset.UTC).minusDays(90);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long order = sourceOrder(f, owner, "PENDING", f.company());
            expected.add(order);
            // Creation order intentionally disagrees with submission order.
            jdbc.update("UPDATE ord_order SET create_time=? WHERE id=?", anchor.plusDays(60 - i), order);
            jdbc.update("INSERT INTO biz_doc_log(doc_type,doc_id,action,to_status,create_time) VALUES('ORDER',?,'submit','PENDING',?)", order, anchor.plusDays(i));
            jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status) VALUES('ORDER',?,1,1,?,'CURRENT')", order, reviewer.id());
        }
        jdbc.update("""
                WITH generated AS (
                    INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,create_by)
                    SELECT ? || n,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,?,?,'PENDING',0,? FROM generate_series(1,1000) n
                    RETURNING id
                ) INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,user_id,status)
                  SELECT 'ORDER',id,1,1,?,'CURRENT' FROM generated
                """, "S2QUEUE" + suffix(), f.company(), owner.id(), owner.id(), owner.id(), reviewer.id());
        long wrongPermission = sourceOrder(f, owner, "PENDING", f.company());
        jdbc.update("INSERT INTO biz_approval_step(biz_type,doc_id,round,level_no,perm,status) VALUES('ORDER',?,1,1,'purchase:order:audit','CURRENT')", wrongPermission);
        JsonNode todos = ok(reviewer, "GET", "/api/board/home/todos", null, f.company());
        JsonNode audit = group(todos, "AUDIT");
        assertThat(audit.path("total").longValue()).isEqualTo(1025);
        assertThat(audit.path("items").size()).isEqualTo(20);
        List<Long> actual = new ArrayList<>();
        audit.path("items").forEach(item -> actual.add(id(item.path("ref").path("id"))));
        assertThat(actual).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(todos.path("canSeeAll").booleanValue()).isFalse();
        JsonNode narrowed = ok(reviewer, "GET", "/api/board/home/todos?mine=true&ownerId=" + owner.id(), null, f.company());
        assertThat(group(narrowed, "AUDIT").path("total").longValue()).isEqualTo(1025);
        assertThat(ok(reviewer, "GET", "/api/orders", null, f.company()).path("total").longValue()).isZero();
        long order = expected.getFirst();
        JsonNode review = ok(reviewer, "GET", "/api/board/orders/" + order + "/review", null, f.company());
        assertThat(review.path("packs").size()).isEqualTo(1);
        assertThat(review.path("packs").get(0).path("techPackId").isString()).isTrue();
        assertThat(review.path("shortages").get(0).path("gap").decimalValue()).isEqualByComparingTo("15.45");
        assertThat(review.toString()).doesNotContain("price", "refCost", "bankAccount", "totalAmount");
        assertThat(call(stranger, "GET", "/api/board/orders/" + order + "/review", null, f.company()).path("code").intValue()).isEqualTo(404);
        Account noList = custom(f.company(), "ALL", "order:order:audit");
        assertThat(call(noList, "GET", "/api/board/orders/" + order + "/review", null, f.company()).path("code").intValue()).isEqualTo(403);
        evidence("current-reviewer-review.json", review);
        evidence("oldest-1025-audit-preview.json", todos);
    }

    @Test void upstreamSeeksAuthorizedGroupsAndCapsPerDocumentWithoutLosingRemainingNotices() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "purchase:order:list", "purchase:order:edit");
        Account foreign = custom(f.company(), "SELF", "purchase:order:list");
        Account boss = custom(f.company(), "ALL", "purchase:order:list");
        long source = sourceOrder(f, owner, "APPROVED", f.company());
        OffsetDateTime time = today().atStartOfDay().atOffset(ZoneOffset.UTC);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 121; i++) {
            long purchase = sourcePurchase(f, owner); ids.add(purchase);
            notice(purchase, source, foreign.id(), time); // stale snapshot owner must not drive visibility.
        }
        long big = sourcePurchase(f, owner); ids.add(big);
        for (int i = 0; i < 125; i++) notice(big, source, foreign.id(), time.plusSeconds(i + 1));
        long hidden = sourcePurchase(f, foreign); notice(hidden, source, owner.id(), time.plusYears(1));
        JsonNode first = ok(owner, "GET", "/api/board/upstream?size=50", null, f.company());
        assertThat(first.path("total").longValue()).isEqualTo(122);
        assertThat(first.path("rows").size()).isEqualTo(50);
        JsonNode firstRow = first.path("rows").get(0);
        assertThat(id(firstRow.path("doc").path("id"))).isEqualTo(big);
        assertThat(firstRow.path("noticesTotal").longValue()).isEqualTo(125);
        assertThat(firstRow.path("notices").size()).isEqualTo(100);
        Set<Long> found = new HashSet<>(); JsonNode page = first;
        while (true) {
            page.path("rows").forEach(row -> assertThat(found.add(id(row.path("doc").path("id")))).isTrue());
            if (!page.path("hasMore").booleanValue()) break;
            String cursor = page.path("nextCursor").stringValue();
            page = ok(owner, "GET", "/api/board/upstream?size=50&cursor=" + cursor, null, f.company());
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(page.path("nextCursor").isNull()).isTrue();
        String cursor = first.path("nextCursor").stringValue();
        assertThat(call(owner, "GET", "/api/board/upstream?size=51&cursor=" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(call(owner, "GET", "/api/board/upstream?size=50&cursor=x" + cursor, null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(call(owner, "GET", "/api/board/upstream?size=101", null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(ok(foreign, "GET", "/api/board/upstream?ownerId=" + owner.id(), null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(ok(boss, "GET", "/api/board/upstream?ownerId=" + owner.id(), null, f.company()).path("total").longValue()).isEqualTo(122);
        assertThat(ok(boss, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();
        JsonNode home = group(ok(owner, "GET", "/api/board/home/todos", null, f.company()), "UPSTREAM");
        assertThat(home.path("total").longValue()).isEqualTo(122);
        assertThat(home.path("items").size()).isEqualTo(20);
        // Real domain action processes the returned preview only. 25 unread records still exist.
        for (JsonNode notice : firstRow.path("notices")) {
            ok(owner, "POST", "/api/purchase-orders/" + big + "/notices/" + notice.path("id").stringValue() + "/handle",
                    Map.of("remark", "S2 preview handled"), f.company());
        }
        JsonNode after = ok(owner, "GET", "/api/board/upstream?size=100", null, f.company());
        JsonNode remaining = null;
        for (JsonNode row : after.path("rows")) if (id(row.path("doc").path("id")) == big) remaining = row;
        assertThat(remaining).isNotNull();
        assertThat(remaining.path("noticesTotal").longValue()).isEqualTo(25);
        assertThat(remaining.path("notices").size()).isEqualTo(25);
        evidence("upstream-preview-first-page.json", first);
        evidence("upstream-after-preview-handled.json", after);
    }

    @Test void selfCreatorVisibilitySurvivesDelegatedOwnerButMineNarrowingAndModuleAclRemainEffective() throws Exception {
        Fixture f = fixture();
        Account creator = custom(f.company(), "SELF", "purchase:order:list");
        Account buyer = custom(f.company(), "SELF", "purchase:order:list");
        long source = sourceOrder(f, creator, "APPROVED", f.company());
        long purchase = sourcePurchase(f, creator);
        jdbc.update("UPDATE pur_order SET buyer_id=? WHERE id=?", buyer.id(), purchase);
        notice(purchase, source, creator.id(), today().atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThat(ok(creator, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(ok(creator, "GET", "/api/board/upstream?mine=true", null, f.company()).path("total").longValue()).isZero();
        assertThat(ok(creator, "GET", "/api/board/upstream?ownerId=" + buyer.id(), null, f.company()).path("total").longValue()).isEqualTo(1);
        assertThat(group(ok(creator, "GET", "/api/board/home/todos", null, f.company()), "UPSTREAM").path("total").longValue()).isEqualTo(1);
        Account none = custom(f.company(), "ALL", "order:order:audit");
        assertThat(ok(none, "GET", "/api/board/upstream", null, f.company()).path("total").longValue()).isZero();
    }

    @Test void certificateCountsActualCertificatesAndSortsBeforeTwentyLimit() throws Exception {
        Fixture f = fixture();
        Account owner = custom(f.company(), "SELF", "supplier:supplier:list");
        jdbc.update("UPDATE sup_supplier SET buyer_id=? WHERE id=?", owner.id(), f.supplier());
        for (int i = 0; i < 25; i++) jdbc.update("INSERT INTO sup_supplier_cert(supplier_id,cert_type,file_url,file_name,valid_until) VALUES(?,'LICENSE','/files/s2/anonymous.png',?,?)",
                f.supplier(), "S2 certificate " + i, today().minusDays(30).plusDays(i));
        Account other = custom(f.company(), "SELF", "supplier:supplier:list");
        long foreign = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,buyer_id) VALUES(?,'S2 other certificates','S2 other',?) RETURNING id",
                Long.class, "S2CERT" + suffix(), other.id());
        jdbc.update("INSERT INTO sup_supplier_cert(supplier_id,cert_type,file_url,file_name,valid_until) VALUES(?,'LICENSE','/files/s2/anonymous.png','S2 foreign certificate',?)",
                foreign, today().minusDays(365));
        JsonNode certs = group(ok(owner, "GET", "/api/board/home/todos", null, f.company()), "CERT");
        assertThat(certs.path("total").longValue()).isEqualTo(25);
        assertThat(certs.path("items").size()).isEqualTo(20);
        assertThat(certs.path("items").get(0).path("sub").stringValue()).contains(Integer.toString(today().minusDays(30).getDayOfMonth()));
        for (JsonNode item : certs.path("items")) assertThat(id(item.path("ref").path("id"))).isEqualTo(f.supplier());
        Account all = custom(f.company(), "ALL", "supplier:supplier:list");
        JsonNode allCerts = group(ok(all, "GET", "/api/board/home/todos", null, f.company()), "CERT");
        assertThat(allCerts.path("total").longValue()).isGreaterThanOrEqualTo(26);
        assertThat(id(allCerts.path("items").get(0).path("ref").path("id"))).isEqualTo(foreign);
        assertThat(group(ok(all, "GET", "/api/board/home/todos?ownerId=" + owner.id(), null, f.company()), "CERT").path("total").longValue()).isEqualTo(25);
        evidence("certificate-25-preview.json", certs);
    }

    @Test void longOrderIdentifiersRemainStringsAndErrorsRecoverOnNextRealRequest() throws Exception {
        Fixture f = fixture(); Account owner = custom(f.company(), "SELF", "order:order:list");
        long large = 9_007_199_254_740_993L;
        jdbc.update("""
                INSERT INTO ord_order(id,no,company_id,order_type,order_date,required_date,sales_id,merch_id,status,total_qty,total_amount,create_by)
                OVERRIDING SYSTEM VALUE VALUES(?,? ,?,'STOCK',CURRENT_DATE,CURRENT_DATE,?,?,'APPROVED',1,10,?)
                """, large, "S2LONG" + suffix(), f.company(), owner.id(), owner.id(), owner.id());
        JsonNode deliveries = ok(owner, "GET", "/api/board/home/deliveries", null, f.company());
        assertThat(deliveries.get(0).path("ref").path("id").stringValue()).isEqualTo(Long.toString(large));
        assertThat(call(owner, "GET", "/api/board/upstream?type=ORDER", null, f.company()).path("code").intValue()).isEqualTo(400);
        assertThat(ok(owner, "GET", "/api/board/home/kpi", null, f.company()).path("order").path("count").longValue()).isEqualTo(1);
        evidence("long-id-delivery.json", deliveries);
    }

    private void notice(long purchase, long source, long owner, OffsetDateTime time) {
        jdbc.update("INSERT INTO biz_doc_notice(doc_type,doc_id,source_type,source_id,content,owner_id,create_time) VALUES('PURCHASE_ORDER',?,'ORDER',?,'S2 anonymous quantity change',?,?)",
                purchase, source, owner, time);
    }
    private static JsonNode group(JsonNode todos, String key) {
        for (JsonNode group : todos.path("groups")) if (key.equals(group.path("key").stringValue())) return group;
        throw new AssertionError("Missing group " + key + " in " + todos);
    }
    private void evidence(String name, JsonNode body) throws Exception {
        Path directory = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }
}
