package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.TestColors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工厂账号（甲方 11-27 答 Q02，RFC 019 §7）：走真 HTTP，过真的过滤器链、拦截器和异常处理。
 * 决定性的一条是把路由表里每个没标 @FactoryAccess 的接口（不管 GET 还是写）都用工厂账号调一遍，全部 403——
 * "登录就能调"的接口（选人、字典、首页、快捷跳转）没有权限码，只靠 FactoryScopeInterceptor 挡；以后新加的接口自动算进来
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class FactoryAccountIT {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PasswordEncoder passwords;
    /** 扫全部接口一次要调几十个导出，导出额度会先回 429；限流单独测，这里不让它挡在前面（和 AssistantTokenIT 一样） */
    @MockitoBean
    private SharedRateLimiter rateLimiter;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Reply(int status, JsonNode body) {
        int code() { return body.path("code").intValue(); }
    }

    /** 一家厂和它的一张生产中的单 */
    record Shop(long factory, long company, long order, long item, long node) {
    }

    @Test
    void factorySeesAndReportsOnlyItsOwnOrders() throws Exception {
        String s = tag();
        Shop mine = shop(s + "A");
        Shop other = shop(s + "B");
        String token = factoryLogin(mine.factory(), s);

        Reply me = send(token, "GET", "/api/auth/me", null, null);
        assertThat(me.code()).as(me.body().toString()).isZero();
        assertThat(id(me.body().path("data").path("factory").path("id"))).isEqualTo(mine.factory());
        assertThat(me.body().path("data").path("perms").size()).as("工厂账号没有权限码").isZero();
        assertThat(me.body().path("data").path("menus").size()).isZero();

        Reply list = send(token, "GET", "/api/factory-orders", null, null);
        assertThat(list.code()).as(list.body().toString()).isZero();
        List<Long> ids = new ArrayList<>();
        list.body().path("data").forEach(r -> ids.add(id(r.path("id"))));
        assertThat(ids).as("只列本厂的").contains(mine.order()).doesNotContain(other.order());

        Reply detail = send(token, "GET", "/api/factory-orders/" + mine.order(), null, null);
        assertThat(detail.code()).as(detail.body().toString()).isZero();
        JsonNode d = detail.body().path("data");
        assertThat(d.path("actions").toString()).contains("report", "cut");
        assertThat(d.path("items").size()).isEqualTo(1);
        assertThat(d.path("nodes").size()).isEqualTo(1);
        assertThat(d.has("unitPrice") || d.has("totalAmount") || d.has("orderNo")).as("不给单价、金额、订单").isFalse();
        assertThat(send(token, "GET", "/api/factory-orders/" + other.order(), null, null).code()).as("别家的单当不存在").isEqualTo(404);

        Map<String, Object> report = Map.of("action", "START", "actualStart", java.time.LocalDate.now().minusDays(1).toString(),
                "doneQty", 50, "remark", "工厂自己报");
        Reply ok = send(token, "POST", "/api/production-orders/" + mine.order() + "/nodes/" + mine.node() + "/reports", report, mine.company());
        assertThat(ok.code()).as(ok.body().toString()).isZero();
        assertThat(jdbc.queryForObject("SELECT done_qty FROM prd_node WHERE id = ?", Integer.class, mine.node())).isEqualTo(50);
        Reply denied = send(token, "POST", "/api/production-orders/" + other.order() + "/nodes/" + other.node() + "/reports", report,
                other.company());
        assertThat(denied.code()).as("别家的单报不了").isEqualTo(404);

        Reply cut = send(token, "PUT", "/api/production-orders/" + mine.order() + "/cut-qty",
                Map.of("items", List.of(Map.of("prdOrderItemId", mine.item(), "cutQty", 198))), mine.company());
        assertThat(cut.code()).as(cut.body().toString()).isZero();
        assertThat(jdbc.queryForObject("SELECT cut_qty FROM prd_order_item WHERE id = ?", Integer.class, mine.item())).isEqualTo(198);
        Reply cutOther = send(token, "PUT", "/api/production-orders/" + other.order() + "/cut-qty",
                Map.of("items", List.of(Map.of("prdOrderItemId", other.item(), "cutQty", 198))), other.company());
        assertThat(cutOther.code()).isEqualTo(404);

        assertThat(send(token, "GET", "/api/production-orders/" + mine.order(), null, null).status())
                .as("内部的详情（有单价、订单）不给工厂").isEqualTo(403);

        // 内部账号调工厂的接口：没有 scope:FACTORY，403
        String internal = internalLogin(s);
        assertThat(send(internal, "GET", "/api/factory-orders", null, null).code()).as("@PreAuthorize 拦的是 code 403").isEqualTo(403);
        // 工厂账号不出现在选人下拉里
        Reply options = send(internal, "GET", "/api/users/options?keyword=" + "itfac" + s, null, null);
        assertThat(options.body().path("data").size()).as("选人不列工厂账号").isZero();
    }

    /**
     * agy 审查（T-6c00a7）后加的几道：只能传照片、只能挂自己传的文件；弱网报工后能核对提交结果；
     * 实裁数不能填 0、不能一填就把单子变成已完成；详情不带内部的订单行 id
     */
    @Test
    void factoryGuards() throws Exception {
        String s = tag();
        Shop mine = shop(s + "A");
        String token = factoryLogin(mine.factory(), s);

        Reply pdf = upload(token, "a.pdf", "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(pdf.body().path("msg").stringValue()).as(pdf.body().toString()).contains("只能传照片");
        assertThat(upload(token, "a.pdf", "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).code())
                .as("工厂账号只能传照片").isEqualTo(400);
        Reply png = upload(token, "a.png", tinyPng());
        assertThat(png.code()).as(png.body().toString()).isZero();
        JsonNode file = png.body().path("data");

        String tenant = jdbc.queryForObject("SELECT tenant_key FROM sys_upload_file WHERE file_url = ?", String.class,
                stripSignature(file.path("url").stringValue()));
        String others = "/files/it-factory-other-" + s + ".png";
        jdbc.update("INSERT INTO sys_upload_file (file_url, tenant_key, owner_user_id, status) VALUES (?, ?, NULL, 'READY')", others, tenant);
        String reports = "/api/production-orders/" + mine.order() + "/nodes/" + mine.node() + "/reports";
        String start = java.time.LocalDate.now().minusDays(1).toString();
        Reply stolen = send(token, "POST", reports, Map.of("action", "START", "actualStart", start, "photos",
                List.of(Map.of("url", others, "name", "x.png", "size", 1))), mine.company());
        assertThat(stolen.code()).as("别人传的文件挂不上：" + stolen.body()).isEqualTo(400);
        assertThat(stolen.body().path("msg").stringValue()).contains("自己刚传的");
        Reply own = send(token, "POST", reports, Map.of("action", "START", "actualStart", start, "photos", List.of(Map.of("url", file.path("url").stringValue(),
                "thumbUrl", file.path("thumbUrl").stringValue(), "name", "a.png", "size", file.path("size").longValue()))), mine.company());
        assertThat(own.code()).as(own.body().toString()).isZero();

        Reply state = send(token, "GET", "/api/http-actions/" + UUID.randomUUID(), null, null);
        assertThat(state.code()).as("弱网报工后核对提交结果：" + state.body()).isZero();

        String cut = "/api/production-orders/" + mine.order() + "/cut-qty";
        assertThat(send(token, "PUT", cut, Map.of("items", List.of(Map.of("prdOrderItemId", mine.item(), "cutQty", 0))), mine.company()).code())
                .as("实裁数不能填 0").isEqualTo(400);
        jdbc.update("UPDATE prd_order_item SET returned_qty = 150 WHERE id = ?", mine.item());
        Reply finish = send(token, "PUT", cut, Map.of("items", List.of(Map.of("prdOrderItemId", mine.item(), "cutQty", 150))), mine.company());
        assertThat(finish.code()).as("一填就完成的不让工厂填：" + finish.body()).isEqualTo(400);
        assertThat(finish.body().path("msg").stringValue()).contains("回齐");
        assertThat(jdbc.queryForObject("SELECT status FROM prd_order WHERE id = ?", String.class, mine.order())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT cut_qty FROM prd_order_item WHERE id = ?", Integer.class, mine.item())).isNull();

        JsonNode item = send(token, "GET", "/api/factory-orders/" + mine.order(), null, null).body().path("data").path("items").get(0);
        assertThat(item.path("orderItemId").isNull() || item.path("orderItemId").isMissingNode()).as("不带订单行 id：" + item).isTrue();
    }

    /**
     * 路由表里每个没标 @FactoryAccess 的接口，工厂账号调都是 403：不只是带权限码的，"登录就能调"的也一样
     */
    @Test
    void everyOtherEndpoint_forbidden() throws Exception {
        String s = tag();
        Shop mine = shop(s + "A");
        String token = factoryLogin(mine.factory(), s);
        Set<String> ops = new TreeSet<>();
        Set<String> multipart = new java.util.HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (FactoryScopeInterceptor.allowed(e.getValue())) {
                continue;
            }
            boolean upload = e.getKey().getConsumesCondition().getConsumableMediaTypes().contains(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : e.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET, RequestMethod.POST) : methods) {
                    if (m != RequestMethod.HEAD && m != RequestMethod.OPTIONS && m != RequestMethod.TRACE) {
                        ops.add(m.name() + " " + path);
                        if (upload) multipart.add(m.name() + " " + path);
                    }
                }
            }
        }
        assertThat(ops).as("没标的接口").hasSizeGreaterThan(300)
                // 写死几条"登录就能调"的，免得拦截器和这里用同一个判断、漏了一起漏
                .contains("GET /api/users/options", "GET /api/production-orders/1", "GET /api/suppliers", "GET /api/board/home/todos");

        List<String> leaked = new ArrayList<>();
        for (String op : ops) {
            String[] parts = op.split(" ", 2);
            // 上传接口只认 multipart：发 JSON 在路由那一步就 400，到不了拦截器，测不出来
            Reply reply = multipart.contains(op) ? sendMultipart(token, parts[0], parts[1], mine.company())
                    : send(token, parts[0], parts[1], parts[0].equals("GET") ? null : Map.of(), mine.company());
            if (reply.status() != 403 || reply.code() != 403) leaked.add(op + " → " + reply.status() + " " + reply.body());
        }
        assertThat(leaked).as("工厂账号调得通的接口").isEmpty();
    }

    // ---------------------------------------------------------------- 工具

    private static String tag() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
    }

    /** 一家合作中的成衣厂（GARMENT）、它的一张生产中的外发加工单（一个 SKU 200 件、一个裁剪节点） */
    private Shop shop(String s) {
        long company = jdbc.queryForObject("INSERT INTO bas_company (code, name, short_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, "F" + s.substring(s.length() - 5), "IT 工厂主体" + s, "IT 工厂主体" + s);   // 主体编码最长 6
        long merch = jdbc.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, 'x', ?) RETURNING id",
                Long.class, "it-fm-" + s.toLowerCase(), "IT 跟单" + s);
        long factory = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 3) RETURNING id""", Long.class, "ITF" + s, "IT 制衣" + s + "厂", "IT 制衣" + s);
        jdbc.update("INSERT INTO sup_supplier_category (supplier_id, category) VALUES (?, 'GARMENT')", factory);
        long color = TestColors.insert(jdbc, "IT 工厂色" + s);
        long sizeGroup = jdbc.queryForObject("INSERT INTO bas_size_group (name, base_size) VALUES (?, 'M') RETURNING id",
                Long.class, "IT 工厂尺码" + s);
        jdbc.update("INSERT INTO bas_size_group_item (size_group_id, seq, size_code) VALUES (?, 1, 'M')", sizeGroup);
        long category = jdbc.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class, "IT 工厂品类" + s);
        long style = jdbc.queryForObject("""
                INSERT INTO sty_style (code, name, style_class, brand, category_id, year, season, source, size_group_id, status)
                VALUES (?, 'IT 工厂款', 'FS', 'IT', ?, 2026, 'AUTUMN', 'OWN', ?, 'ENABLED') RETURNING id""",
                Long.class, "ITFT" + s, category, sizeGroup);
        long styleSku = jdbc.queryForObject("INSERT INTO sty_style_sku (style_id, code, color_id, size_code) VALUES (?, ?, ?, 'M') RETURNING id",
                Long.class, style, "ITFT" + s + "-A-M", color);
        long order = jdbc.queryForObject("""
                INSERT INTO ord_order (no, company_id, order_type, order_date, required_date, sales_id, merch_id, status)
                VALUES (?, ?, 'STOCK', current_date, current_date + 60, ?, ?, 'APPROVED') RETURNING id""",
                Long.class, "DDF" + s, company, merch, merch);
        long orderItem = jdbc.queryForObject("""
                INSERT INTO ord_order_item (order_id, seq, style_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 200) RETURNING id""",
                Long.class, order, style, styleSku);
        long pack = jdbc.queryForObject(
                "INSERT INTO tp_tech_pack (style_id, stage, version, status) VALUES (?, 'PROD', 1, 'EFFECTIVE') RETURNING id", Long.class, style);
        long prd = jdbc.queryForObject("""
                INSERT INTO prd_order (no, company_id, kind, factory_id, process_mode, order_id, style_id, tech_pack_id, order_date, due_date,
                                       unit_price, tax_pct, merch_id, settle_method, status, total_qty)
                VALUES (?, ?, 'OUTSOURCE', ?, 'CMT', ?, ?, ?, current_date - 30, current_date + 30, 38, 3, ?, 'MONTHLY', 'RUNNING', 200)
                RETURNING id""", Long.class, "JGF" + s, company, factory, order, style, pack, merch);
        long item = jdbc.queryForObject("""
                INSERT INTO prd_order_item (prd_order_id, seq, order_item_id, style_sku_id, qty) VALUES (?, 1, ?, ?, 200) RETURNING id""",
                Long.class, prd, orderItem, styleSku);
        long node = jdbc.queryForObject("""
                INSERT INTO prd_node (prd_order_id, seq, node_code, node_name, plan_start, plan_finish)
                VALUES (?, 1, 'CUT', '裁剪', current_date - 5, current_date + 5) RETURNING id""", Long.class, prd);
        return new Shop(factory, company, prd, item, node);
    }

    /** 建一个工厂账号（已经改过初始密码）、登录 */
    private String factoryLogin(long factory, String s) throws Exception {
        String name = "itfac" + s;
        String secret = "Fac-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sys_user (username, password, nickname, partner_id, product_lines, pwd_must_change) VALUES (?, ?, ?, ?, '{}', false)",
                name, passwords.encode(secret), name, factory);
        return login(name, secret);
    }

    /** 内部账号：生产单全部权限的跟单 */
    private String internalLogin(String s) throws Exception {
        String name = "itint" + s;
        String secret = "Int-" + UUID.randomUUID();
        long user = jdbc.queryForObject("INSERT INTO sys_user (username, password, nickname, pwd_must_change) VALUES (?, ?, ?, false) RETURNING id",
                Long.class, name, passwords.encode(secret), name);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = 'merchandiser' AND NOT deleted", user);
        return login(name, secret);
    }

    private String login(String name, String secret) throws Exception {
        Reply login = send(null, "POST", "/api/auth/login", Map.of("username", name, "password", secret), null);
        assertThat(login.code()).as(login.body().toString()).isZero();
        return login.body().path("data").stringValue();
    }

    /** 2×2 的 PNG：上传要过文件头校验、生成缩略图 */
    private static byte[] tinyPng() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String stripSignature(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private Reply upload(String token, String name, byte[] content) throws Exception {
        String boundary = "itfactory" + UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/files")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(response.statusCode(), json.readTree(response.body()));
    }

    /** id 在 JSON 里是字符串（前端 JS 精度），转回 long */
    private static long id(JsonNode node) {
        return Long.parseLong(node.isString() ? node.stringValue() : String.valueOf(node.longValue()));
    }

    /** 空的 multipart 请求：只为过路由那一关，到拦截器 */
    private Reply sendMultipart(String token, String method, String path, Long company) throws Exception {
        String boundary = "itfactory" + UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.xlsx\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nx\r\n--" + boundary + "--\r\n";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Company-Id", String.valueOf(company));
        HttpResponse<byte[]> response = http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        return new Reply(response.statusCode(), raw.length == 0 ? json.createObjectNode() : json.readTree(raw));
    }

    private Reply send(String token, String method, String path, Object body, Long company) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (!method.equals("GET")) request.header("Idempotency-Key", UUID.randomUUID().toString());
        if (company != null) request.header("X-Company-Id", String.valueOf(company));
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        HttpResponse<byte[]> response = http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        JsonNode node;
        try {
            node = raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
        } catch (RuntimeException e) {
            node = json.createObjectNode().put("raw", new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new Reply(response.statusCode(), node);
    }
}
