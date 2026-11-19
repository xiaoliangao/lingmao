package com.lingmao.scm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端（进度第 5 批）：一张需求订单从录单走到回货，每一步由平时干这件事的角色登录、调真接口做，角色用迁移预置的模板（PRD §3.1），
 * 不另加权限：业务员录单提交 → 管理层看评审信息、审核 → 采购算料、确认、生成采购单、提交 → 管理层审采购单 → 仓管收料入库 →
 * 跟单排单、提交 → 管理层审生产单 → 仓管按生产单发料 → 跟单报完各节点、登记回货。
 * 每一步看单据状态、回写的数、库存，最后看首页上不再挂着这张单的待办。
 * <p>
 * 主数据（主体、仓库、供应商、加工厂、款式、物料、生效的资料包）直接写库，和上线时导进来的一样。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class FullFlowHttpIT {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PasswordEncoder passwords;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void close() {
        http.close();
    }

    /** 登录的人和前端一样，每个请求带上当前主体（X-Company-Id） */
    record Account(long id, String token, long company) {
    }

    @Test
    void orderToReceipt() throws Exception {
        Fixture f = fixture();
        LocalDate today = LocalDate.now();
        Account sales = account("sales", f), manager = account("manager", f), buyer = account("buyer", f),
                warehouse = account("warehouse", f), merch = account("merchandiser", f);

        // 业务员录单（M 60、L 40）、提交
        long order = id(ok(sales, "POST", "/api/orders", map("companyId", f.company, "orderType", "STOCK", "orderDate", today,
                "requiredDate", today.plusDays(45), "salesId", sales.id, "merchId", merch.id, "currency", "CNY",
                "items", List.of(map("styleSkuId", f.skuM, "qty", 60, "price", "89"), map("styleSkuId", f.skuL, "qty", 40, "price", "89")))
        ).path("id"));
        ok(sales, "POST", "/api/orders/" + order + "/submit", map("version", version(sales, "/api/orders/" + order)));
        String orderNo = ok(sales, "GET", "/api/orders/" + order, null).path("no").stringValue();

        // 管理层：首页待我审核里有它；评审信息说缺 150 米、加工厂在手 0；审核，承诺交期 40 天后
        assertThat(todoTitles(manager, "AUDIT")).contains("需求订单 " + orderNo);
        JsonNode review = ok(manager, "GET", "/api/board/orders/" + order + "/review", null);
        assertThat(review.path("shortages").get(0).path("gap").decimalValue()).isEqualByComparingTo("150");
        assertThat(id(review.path("loads").get(0).path("factoryId"))).isEqualTo(f.factory);
        ok(manager, "POST", "/api/orders/" + order + "/audit", map("version", version(manager, "/api/orders/" + order),
                "commitDate", today.plusDays(40)));
        assertThat(status("ord_order", order)).isEqualTo("APPROVED");

        // 采购：选订单算料（订单不是采购建的，采购是"本人"范围）→ 确认 → 生成采购单 → 提交
        assertThat(ok(buyer, "GET", "/api/mrp-calcs/order-options?keyword=" + orderNo, null).path("list").size()).isEqualTo(1);
        long calc = id(ok(buyer, "POST", "/api/mrp-calcs", map("orderIds", List.of(order))).path("id"));
        JsonNode calcDetail = ok(buyer, "GET", "/api/mrp-calcs/" + calc, null);
        JsonNode row = calcDetail.path("items").get(0);
        assertThat(row.path("needQty").decimalValue()).isEqualByComparingTo("150");
        assertThat(row.path("suggestQty").decimalValue()).isEqualByComparingTo("150");
        ok(buyer, "POST", "/api/mrp-calcs/" + calc + "/confirm", map("version", calcDetail.path("version").intValue()));
        JsonNode created = ok(buyer, "POST", "/api/purchase-orders/from-mrp", map("calcId", calc, "itemIds", List.of(id(row.path("id"))),
                "version", version(buyer, "/api/mrp-calcs/" + calc)));
        long po = id(created.get(0).path("id"));
        ok(buyer, "POST", "/api/purchase-orders/" + po + "/submit", map("version", version(buyer, "/api/purchase-orders/" + po)));

        // 管理层审采购单
        ok(manager, "POST", "/api/purchase-orders/" + po + "/audit", map("version", version(manager, "/api/purchase-orders/" + po)));
        assertThat(status("pur_order", po)).isEqualTo("APPROVED");

        // 仓管：按采购单收料 150 米、直接过账，进采购单上的仓库
        JsonNode inPrefill = ok(warehouse, "GET", "/api/inventory-docs/from-purchase/" + po + "?docType=PURCHASE_IN", null);
        long materialWarehouse = id(inPrefill.path("toWarehouseId"));
        withAction(warehouse, "/api/inventory-docs/from-purchase/" + po, map("docType", "PURCHASE_IN", "post", true,
                "items", List.of(map("sourceItemId", id(inPrefill.path("items").get(0).path("sourceItemId")), "materialSkuId", f.materialSku,
                        "qty", "150"))));
        assertThat(stock(materialWarehouse, f.materialSku)).isEqualByComparingTo("150");

        // 跟单：排单给默认加工厂、提交；管理层审
        JsonNode prefill = ok(merch, "GET", "/api/production-orders/schedule-prefill?orderId=" + order, null);
        List<Map<String, Object>> items = new ArrayList<>();
        prefill.path("styles").get(0).path("skus").forEach(s -> items.add(map("orderItemId", id(s.path("orderItemId")),
                "qty", s.path("remainQty").intValue())));
        JsonNode scheduled = ok(merch, "POST", "/api/production-orders/schedule", map("orderId", order, "blocks", List.of(map(
                "styleId", f.style, "factoryId", f.factory, "processMode", "CMT", "unitPrice", "38", "dueDate", today.plusDays(35),
                "merchId", merch.id, "items", items))));
        long prd = id(scheduled.get(0).path("id"));
        ok(merch, "POST", "/api/production-orders/" + prd + "/submit", map("version", version(merch, "/api/production-orders/" + prd)));
        ok(manager, "POST", "/api/production-orders/" + prd + "/audit", map("version", version(manager, "/api/production-orders/" + prd)));
        assertThat(status("prd_order", prd)).isEqualTo("APPROVED");

        // 仓管：按生产单发料 150 米，从刚才收料的仓库出
        JsonNode outPrefill = ok(warehouse, "GET", "/api/inventory-docs/from-production/" + prd + "?docType=ISSUE_OUT", null);
        assertThat(outPrefill.path("items").get(0).path("needQty").decimalValue()).isEqualByComparingTo("150");
        withAction(warehouse, "/api/inventory-docs/from-production/" + prd, map("docType", "ISSUE_OUT", "post", true,
                "fromWarehouseId", materialWarehouse, "items", List.of(map("sourceItemId", outPrefill.path("items").get(0).path("sourceItemId"),
                        "materialSkuId", f.materialSku, "qty", "150"))));
        assertThat(stock(materialWarehouse, f.materialSku)).isEqualByComparingTo("0");
        JsonNode production = ok(merch, "GET", "/api/production-orders/" + prd, null);
        assertThat(production.path("issueStatus").stringValue()).isEqualTo("DONE");

        // 跟单：各节点报完成，登记回货 M 60、L 40
        for (JsonNode node : production.path("nodes")) {
            ok(merch, "POST", "/api/production-orders/" + prd + "/nodes/" + id(node.path("id")) + "/reports",
                    map("action", "FINISH", "actualFinish", today, "doneQty", 100));
        }
        List<Map<String, Object>> returned = new ArrayList<>();
        production.path("items").forEach(i -> returned.add(map("prdOrderItemId", id(i.path("id")), "qty", i.path("qty").intValue())));
        withAction(merch, "/api/production-orders/" + prd + "/receipts", map("receiptDate", today, "items", returned));

        // 收尾：生产单、订单都已完成，回货数写回订单；采购单到齐；首页上没有这张单的待办了
        assertThat(status("prd_order", prd)).isEqualTo("DONE");
        assertThat(status("ord_order", order)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT sum(returned_qty) FROM ord_order_item WHERE order_id = ?", Integer.class, order)).isEqualTo(100);
        assertThat(ok(buyer, "GET", "/api/purchase-orders/" + po, null).path("receivedQty").decimalValue()).isEqualByComparingTo("150");
        assertThat(todoTitles(manager, "AUDIT")).noneMatch(t -> t.contains(orderNo));
        assertThat(todoTitles(merch, "UNSCHEDULED")).isEmpty();
        assertThat(todoTitles(merch, "PRODUCTION_LATE")).isEmpty();
        assertThat(ok(merch, "GET", "/api/board/home/deliveries", null).findValuesAsString("no")).doesNotContain(orderNo);
    }

    // ---------------------------------------------------------------- 主数据

    record Fixture(long company, long factory, long style, long skuM, long skuL, long materialSku) {
    }

    /** 主体、物料仓、面料供应商、成衣厂（款式的默认加工厂，带工厂仓）、一个款（一色两码）、一种面料、生效的生产 BOM（1.5 米 / 件） */
    private Fixture fixture() {
        String s = String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        long company = jdbc.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id", Long.class,
                "E" + s.substring(1), "端到端主体" + s, "端到端" + s);
        jdbc.update("INSERT INTO bas_warehouse (code, name, type) VALUES (?, ?, 'MATERIAL')", "E2W" + s, "端到端物料仓" + s);
        long fabric = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "E2S" + s, "端到端面料" + s, "端到端面料" + s);
        jdbc.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'FABRIC')", fabric);
        long factory = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "E2F" + s, "端到端制衣" + s, "端到端制衣" + s);
        jdbc.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'GARMENT')", factory);
        jdbc.update("INSERT INTO bas_warehouse (code, name, type, factory_id) VALUES (?, ?, 'FACTORY', ?)", "E2C" + s, "端到端制衣仓" + s, factory);
        long sizes = jdbc.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id", Long.class, "端到端尺码" + s);
        jdbc.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M'), (?, 2, 'L')", sizes, sizes);
        long category = jdbc.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "端到端品类" + s);
        String styleCode = "E2E" + s;
        long style = jdbc.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, tag_price, status,
                                       default_factory_id)
                VALUES (?, '端到端款', 'FS', 'E2E', ?, 2026, 'AUTUMN', 'OWN', ?, 299, 'ENABLED', ?) RETURNING id""",
                Long.class, styleCode, category, sizes, factory);
        long color = TestColors.insert(jdbc, "端到端色" + s);
        jdbc.update("INSERT INTO sty_style_color (style_id, color_id, seq) VALUES (?, ?, 1)", style, color);
        long skuM = jdbc.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, styleCode + "-A-M", color);
        long skuL = jdbc.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'L') RETURNING id",
                Long.class, style, styleCode + "-A-L", color);
        long materialCategory = jdbc.queryForObject("INSERT INTO bas_material_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                "端到端物料分类" + s);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit, reserve_pct, default_supplier_id,
                                          ref_price)
                VALUES (?, '端到端面料', ?, 'FABRIC', 'METER', 'METER', 0, ?, 20) RETURNING id""", Long.class, "E2M" + s, materialCategory, fabric);
        long materialSku = jdbc.queryForObject("INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, material, "E2M" + s + "-A", color);
        long pack = jdbc.queryForObject("INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id",
                Long.class, style);
        long bom = jdbc.queryForObject("""
                INSERT INTO tp_bom_item (tech_pack_id, seq, part, material_id, sku_mode, usage_qty, unit, supplier_id)
                VALUES (?, 1, '大身', ?, 'BY_COLOR', 1.5, 'METER', ?) RETURNING id""", Long.class, pack, material, fabric);
        jdbc.update("INSERT INTO tp_bom_item_sku (bom_item_id, color_id, material_sku_id) VALUES (?, ?, ?)", bom, color, materialSku);
        return new Fixture(company, factory, style, skuM, skuL, materialSku);
    }

    /** 用迁移预置的角色模板建一个人、登录；默认主体是这次的主体 */
    private Account account(String role, Fixture f) throws Exception {
        String name = "e2e-" + role + "-" + UUID.randomUUID().toString().substring(0, 8);
        String secret = "E2e-" + UUID.randomUUID();
        long user = jdbc.queryForObject("INSERT INTO sys_user (username, password, nickname, default_company_id) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, name, passwords.encode(secret), name, f.company);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = ? AND NOT deleted", user, role);
        JsonNode reply = send(null, "POST", "/api/auth/login", Map.of("username", name, "password", secret), null);
        assertThat(reply.path("code").intValue()).as("登录 %s", role).isZero();
        return new Account(user, reply.path("data").stringValue(), f.company);
    }

    // ---------------------------------------------------------------- HTTP

    private JsonNode ok(Account who, String method, String path, Object body) throws Exception {
        JsonNode reply = send(who, method, path, body, method.equals("POST") ? UUID.randomUUID().toString() : null);
        assertThat(reply.path("code").intValue()).as("%s %s → %s %s", method, path, reply.path("msg").asText(), reply.path("data")).isZero();
        return reply.path("data");
    }

    /** 由源单生成出入库单、登记回货：幂等键和 clientActionId 用同一个 */
    private void withAction(Account who, String path, Map<String, Object> body) throws Exception {
        String action = UUID.randomUUID().toString();
        body.put("clientActionId", action);
        JsonNode reply = send(who, "POST", path, body, action);
        assertThat(reply.path("code").intValue()).as("POST %s → %s %s", path, reply.path("msg").asText(), reply.path("data")).isZero();
    }

    private JsonNode send(Account who, String method, String path, Object body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (who != null) {
            request.header("Authorization", "Bearer " + who.token).header("X-Company-Id", String.valueOf(who.company));
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        return json.readTree(http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray()).body());
    }

    private int version(Account who, String path) throws Exception {
        return ok(who, "GET", path, null).path("version").intValue();
    }

    private List<String> todoTitles(Account who, String group) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JsonNode g : ok(who, "GET", "/api/board/home/todos", null).path("groups")) {
            if (g.path("key").stringValue().equals(group)) {
                g.path("items").forEach(i -> titles.add(i.path("title").stringValue()));
            }
        }
        return titles;
    }

    private String status(String table, long id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    private BigDecimal stock(long warehouseId, long skuId) {
        return jdbc.queryForObject("SELECT COALESCE(sum(qty), 0) FROM inv_stock WHERE warehouse_id = ? AND material_sku_id = ?",
                BigDecimal.class, warehouseId, skuId);
    }

    private static long id(JsonNode node) {
        return node.isString() ? Long.parseLong(node.stringValue()) : node.longValue();
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }
}
