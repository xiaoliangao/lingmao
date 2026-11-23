package com.lingmao.scm.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP helpers shared with the independent sample audit tests. No status or version is changed by SQL. */
abstract class S2SampleFixture extends A2BusinessFixture {
    final List<Map<String, Object>> sampleHttpEvidence = new ArrayList<>();

    Account sampleActor(Fixture f) throws Exception {
        return custom(f.company(), "ALL", "dev:sample:list", "dev:sample:add", "dev:sample:edit",
                "dev:sample:remove", "dev:sample:make", "dev:sample:review", "dev:sample:convert",
                "techpack:pack:list", "techpack:pack:add", "techpack:pack:edit", "techpack:pack:remove",
                "techpack:pack:publish", "techpack:cost:query", "style:style:list", "style:style:add",
                "style:style:edit", "biz:tag:list", "biz:tag:manage");
    }

    @Override
    Fixture fixture() {
        Fixture fixture = super.fixture();
        // Production migrations deliberately do not contain customer brands.
        // Every test owns its synthetic brand and must not rely on another class's seed order.
        String brand = "SAMPLE_TEST_" + fixture.company();
        jdbc.update("INSERT INTO sys_dict_data(type_code,label,value,sort) VALUES('brand',?,?,0)",
                "Synthetic sample brand " + fixture.company(), brand);
        return fixture;
    }

    Map<String, Object> sampleBody(Fixture f, Account designer) {
        String brand = jdbc.queryForObject("SELECT value FROM sys_dict_data WHERE type_code='brand' AND value=? AND status=1 AND NOT deleted",
                String.class, "SAMPLE_TEST_" + f.company());
        return map("companyId", f.company(), "name", "S2 anonymous sample " + suffix(), "brand", brand,
                "categoryId", f.category(), "year", 2026, "season", "AUTUMN", "designerId", designer.id(),
                "patternMakerId", designer.id(), "source", "OWN", "sizeGroupId", f.sizeGroup(),
                "colorIds", List.of(f.color()), "needDate", today().plusDays(10), "images", List.of(),
                "estTagPrice", "129.90", "estCost", "12.3456");
    }

    JsonNode createSample(Account actor, Map<String, Object> body) throws Exception {
        return sampleOk(actor, "POST", "/api/samples", body, ((Number) body.get("companyId")).longValue());
    }

    JsonNode sampleDetail(Account actor, long companyId, long sampleId) throws Exception {
        return sampleOk(actor, "GET", "/api/samples/" + sampleId + "?companyId=" + companyId, null, companyId);
    }

    JsonNode dispatchInternal(Account actor, long companyId, long sampleId, long makerId) throws Exception {
        JsonNode detail = sampleDetail(actor, companyId, sampleId), order = detail.path("currentOrder");
        return sampleOk(actor, "POST", "/api/sample-orders/" + id(order.path("id")) + "/dispatch?companyId=" + companyId,
                map("version", order.path("version").intValue(), "sampleVersionLock", detail.path("version").intValue(),
                        "makeWay", "INTERNAL", "makerId", makerId, "needDate", today().plusDays(10)), companyId);
    }

    JsonNode completeSend(Account actor, long companyId, long sampleId) throws Exception {
        JsonNode detail = sampleDetail(actor, companyId, sampleId), order = detail.path("currentOrder");
        JsonNode send = null;
        for (JsonNode node : order.path("nodes")) if (node.path("nodeCode").stringValue().equals("SEND")) send = node;
        assertThat(send).as("actual dispatched SEND node exists").isNotNull();
        return sampleOk(actor, "POST", "/api/sample-orders/" + id(order.path("id")) + "/nodes/"
                        + id(send.path("id")) + "/done?companyId=" + companyId,
                map("version", order.path("version").intValue(), "sampleVersionLock", detail.path("version").intValue(),
                        "doneDate", today()), companyId);
    }

    JsonNode reviewSample(Account actor, long companyId, long sampleId, String result) throws Exception {
        JsonNode detail = sampleDetail(actor, companyId, sampleId), order = detail.path("currentOrder");
        return sampleOk(actor, "POST", "/api/sample-orders/" + id(order.path("id")) + "/review?companyId=" + companyId,
                map("version", order.path("version").intValue(), "sampleVersionLock", detail.path("version").intValue(),
                        "result", result, "opinion", "Synthetic review " + result, "reviewDate", today(),
                        "reviewerIds", List.of(actor.id()), "photos", List.of()), companyId);
    }

    long createSamplePack(Account actor, long companyId, long sampleId) throws Exception {
        return id(sampleOk(actor, "POST", "/api/tech-packs", map("sampleId", sampleId, "companyId", companyId, "stage", "DEV"), companyId));
    }

    Map<String, Object> packBody(Fixture f, int version, String note, String price) {
        return packBody(f,version,note,price,List.of(f.color()));
    }

    private Map<String, Object> packBody(Fixture f, int version, String note, String price, List<Long> colors) {
        return map("versionLock", version, "versionNote", note, "utilPct", "100", "specPoints", List.of(),
                "bomItems", List.of(map("part", "body", "materialId", f.material(), "skuMode", "BY_COLOR",
                        "usageMode", "UNIFORM", "usageQty", "1.5", "lossPct", "0", "source", "PURCHASE",
                        "supplierId", f.supplier(), "refPrice", price,
                        "skus", colors.stream().map(color->map("colorId",color,"materialSkuId",f.materialSku())).toList())),
                "craftSections", List.of(), "processes", List.of());
    }

    int saveSamplePack(Account actor, Fixture f, long packId, String note, String price) throws Exception {
        JsonNode current = sampleOk(actor, "GET", "/api/tech-packs/" + packId, null, f.company());
        List<Long> colors=new ArrayList<>();current.path("colors").forEach(c->colors.add(id(c.path("id"))));
        assertThat(colors).as("real pack owner colors for a complete synthetic BOM").isNotEmpty();
        return sampleOk(actor, "PUT", "/api/tech-packs/" + packId,
                packBody(f, current.path("versionLock").intValue(), note, price, colors), f.company()).intValue();
    }

    JsonNode sampleOk(Account actor, String method, String path, Object body, Long companyId) throws Exception {
        JsonNode reply = sampleCall(actor, method, path, body, companyId, method.equals("GET") ? null : UUID.randomUUID().toString());
        assertThat(reply.path("code").intValue()).as("%s %s: %s", method, path, reply).isZero();
        return reply.path("data");
    }

    record FrozenBody(String json, JsonNode snapshot) { }

    static FrozenBody freezeBody(JsonMapper json, Object body) {
        if (body == null) return new FrozenBody(null, null);
        String serialized = json.writeValueAsString(body);
        return new FrozenBody(serialized, json.readTree(serialized));
    }

    JsonNode sampleCall(Account actor, String method, String path, Object body, Long companyId, String key) throws Exception {
        FrozenBody frozen = freezeBody(json, body);
        String trace = "s2-sample-" + suffix();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + actor.token())
                .header("X-Request-Id", trace);
        if (companyId != null) request.header("X-Company-Id", companyId.toString());
        if (key != null) request.header("Idempotency-Key", key);
        HttpResponse<byte[]> response = http.send(request.method(method, frozen.json() == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(frozen.json())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.headers().firstValue("X-Request-Id")).contains(trace);
        JsonNode reply = node(response);
        synchronized (sampleHttpEvidence) {
            sampleHttpEvidence.add(map("method", method, "path", path, "companyId", companyId, "requestId", trace,
                    "requestBody", frozen.snapshot(), "requestBodyJson", frozen.json(),
                    "httpStatus", response.statusCode(), "response", reply));
        }
        return reply;
    }

    void saveSampleHttp(String name) throws Exception {
        if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("evidence name must be a safe label");
        Path path = Path.of("target/a2-sample/http", name + ".json"); Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(sampleHttpEvidence));
    }
}
