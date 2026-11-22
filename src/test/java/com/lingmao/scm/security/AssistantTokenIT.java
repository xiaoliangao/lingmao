package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.module.system.service.impl.SessionHousekeeping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手令牌（RFC 017 §3.1、§3.7、§8 前三行）：走真 HTTP，过真的过滤器链、拦截器和异常处理。
 * 决定性的一条是把 app 里每个非 GET 接口、每个返回文件的 GET 接口都用助手令牌调一遍，全部 403——
 * 接口清单从 Spring 的路由表取（openapi.json 也是从它生成的，还多了没写进文档的），以后新加的接口自动算进来
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class AssistantTokenIT {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private RefreshSessionService refreshSessions;
    @Autowired
    private SessionHousekeeping housekeeping;
    /** 扫全部接口一次要调几十个导出，导出额度（每分钟 12 次）会先回 429；限流单独在 SensitiveRateFilterTest 里测，这里不让它挡在前面 */
    @MockitoBean
    private SharedRateLimiter rateLimiter;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Reply(int status, JsonNode body) {
        int code() { return body.path("code").intValue(); }
    }

    record Account(long id, String secret, String token) {
    }

    @BeforeEach
    void aiOn() {
        setAi(true);
    }

    @AfterEach
    void aiOff() {
        setAi(false);
        http.close();
    }

    /** 采购（预置角色带"使用智能助手"）换到令牌，能读自己的登录信息；令牌挂在这次登录下面、30 分钟 */
    @Test
    void exchange_readsAsTheUser() throws Exception {
        Account buyer = account("buyer");
        JsonNode issued = exchange(buyer);
        String assist = issued.path("token").stringValue();

        Reply me = send(assist, "GET", "/api/auth/me", null);
        assertThat(me.code()).isZero();
        assertThat(me.body().path("data").path("perms").toString()).contains(JwtAuthFilter.ASSISTANT_PERM);
        Instant expires = Instant.parse(issued.path("expiresAt").stringValue());
        assertThat(Duration.between(Instant.now(), expires).toMinutes()).isBetween(28L, 30L);
        assertThat(issued.path("expiresAt").stringValue()).endsWith("+08:00");
        String parent = jdbc.queryForObject("SELECT parent_jti FROM sys_login_session WHERE jti = ?", String.class,
                jwtUtil.parse(assist).getId());
        assertThat(parent).isEqualTo(jwtUtil.parse(buyer.token()).getId());

        // 助手令牌不能拿去再换（非 GET 在过滤器就拦了）
        assertThat(send(assist, "POST", "/api/auth/assistant-token", Map.of()).status()).isEqualTo(403);
    }

    /** 统一开关：关着换不到；关之前发出去、还没过期的令牌，下一个请求也 403；开回来接着能用 */
    @Test
    void aiSwitchOff_blocksExchangeAndIssuedTokens() throws Exception {
        Account buyer = account("buyer");
        String assist = exchange(buyer).path("token").stringValue();

        setAi(false);
        Reply blocked = send(assist, "GET", "/api/auth/me", null);
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.body().path("msg").stringValue()).contains("AI 功能没有打开");
        Reply refused = send(buyer.token(), "POST", "/api/auth/assistant-token", Map.of());
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo(403);
        // 普通登录不受影响
        assertThat(send(buyer.token(), "GET", "/api/auth/me", null).code()).isZero();

        jdbc.update("DELETE FROM sys_config WHERE config_key = 'ai.switch'");
        assertThat(send(assist, "GET", "/api/auth/me", null).status()).as("没有这一行等于关").isEqualTo(403);
        assertThat(send(buyer.token(), "POST", "/api/auth/assistant-token", Map.of()).status()).as("没有这一行换不到").isEqualTo(403);

        setAi(true);
        assertThat(send(assist, "GET", "/api/auth/me", null).code()).isZero();
    }

    /** 角色里没有"使用智能助手"：换不到；换到以后被收回，下一个请求 403 */
    @Test
    void withoutPermission_forbidden() throws Exception {
        Account nobody = account(null);
        assertThat(send(nobody.token(), "POST", "/api/auth/assistant-token", Map.of()).code()).isEqualTo(403);

        Account buyer = account("buyer");
        String assist = exchange(buyer).path("token").stringValue();
        long role = jdbc.queryForObject("SELECT id FROM sys_role WHERE code = 'buyer' AND NOT deleted", Long.class);
        long menu = jdbc.queryForObject("SELECT id FROM sys_menu WHERE perms = ? AND NOT deleted", Long.class, JwtAuthFilter.ASSISTANT_PERM);
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ? AND menu_id = ?", role, menu);
        try {
            Reply reply = send(assist, "GET", "/api/auth/me", null);
            assertThat(reply.status()).isEqualTo(403);
            assertThat(reply.body().path("msg").stringValue()).contains("没有使用智能助手的权限");
        } finally {
            jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?) ON CONFLICT DO NOTHING", role, menu);
        }
    }

    /** 改密码、换它的那次登录退出：助手令牌一起失效（401）；登记的已经过期也是 401 */
    @Test
    void passwordChangeLogoutAndExpiry_invalidate() throws Exception {
        Account a = account("buyer");
        String assist = exchange(a).path("token").stringValue();
        Reply changed = send(a.token(), "PUT", "/api/auth/password", Map.of("oldPassword", a.secret(), "newPassword", "Nw9-" + UUID.randomUUID().toString().substring(0, 8)));
        assertThat(changed.code()).as(changed.body().toString()).isZero();
        assertThat(send(assist, "GET", "/api/auth/me", null).status()).isEqualTo(401);

        Account b = account("buyer");
        String assistB = exchange(b).path("token").stringValue();
        Reply out = sendWith(b.token(), "POST", "/api/auth/logout", Map.of(), Map.of("X-Requested-With", "XMLHttpRequest"));
        assertThat(out.code()).as(out.body().toString()).isZero();
        assertThat(send(assistB, "GET", "/api/auth/me", null).status()).isEqualTo(401);

        Account c = account("buyer");
        String assistC = exchange(c).path("token").stringValue();
        jdbc.update("UPDATE sys_login_session SET expires_at = now() - interval '1 second' WHERE jti = ?", jwtUtil.parse(assistC).getId());
        assertThat(send(assistC, "GET", "/api/auth/me", null).status()).isEqualTo(401);
    }

    /** 刷新登录令牌（走 RefreshSessionService.rotate）：助手令牌改挂到新令牌下面，照样能用；新令牌退出时它一起失效 */
    @Test
    void refreshRotation_reparentsAssistantToken() throws Exception {
        Account a = account("buyer");
        var family = refreshSessions.issue(a.token());
        String assist = exchange(new Account(a.id(), a.secret(), family.accessToken())).path("token").stringValue();

        var rotated = refreshSessions.rotate(family.refreshToken(), family.browserToken());
        assertThat(send(family.accessToken(), "GET", "/api/auth/me", null).status()).as("旧登录令牌作废").isEqualTo(401);
        assertThat(send(assist, "GET", "/api/auth/me", null).code()).as("助手令牌不受刷新打断").isZero();
        assertThat(jdbc.queryForObject("SELECT parent_jti FROM sys_login_session WHERE jti = ?", String.class, jwtUtil.parse(assist).getId()))
                .isEqualTo(jwtUtil.parse(rotated.accessToken()).getId());

        Reply out = sendWith(rotated.accessToken(), "POST", "/api/auth/logout", Map.of(), Map.of("X-Requested-With", "XMLHttpRequest"));
        assertThat(out.code()).as(out.body().toString()).isZero();
        assertThat(send(assist, "GET", "/api/auth/me", null).status()).isEqualTo(401);
    }

    /**
     * 换它的登录令牌到点了：定时清理先留着那一行，助手令牌照样用到它自己的 30 分钟（不提前 401）；
     * 过期的登录令牌自己认不出、换不了；助手令牌也过期以后两行一起清掉
     */
    @Test
    void parentExpiry_keepsAssistantTokenUntilItsOwnExpiry() throws Exception {
        Account a = account("buyer");
        String assist = exchange(a).path("token").stringValue();
        String parent = jwtUtil.parse(a.token()).getId();
        String child = jwtUtil.parse(assist).getId();
        jdbc.update("UPDATE sys_login_session SET expires_at = now() - interval '1 minute' WHERE jti = ?", parent);

        housekeeping.runUntil(() -> false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_login_session WHERE jti = ?", Integer.class, parent)).as("还有子令牌，先不删").isEqualTo(1);
        assertThat(send(assist, "GET", "/api/auth/me", null).code()).as("助手令牌不受登录到点影响").isZero();
        assertThat(send(a.token(), "GET", "/api/auth/me", null).status()).as("过期的登录令牌认不出").isEqualTo(401);
        assertThat(send(a.token(), "POST", "/api/auth/assistant-token", Map.of()).status()).as("也换不了").isEqualTo(401);

        jdbc.update("UPDATE sys_login_session SET expires_at = now() - interval '1 minute' WHERE jti = ?", child);
        housekeeping.runUntil(() -> false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_login_session WHERE jti IN (?, ?)", Integer.class, parent, child)).isZero();
    }

    /** 同一张资质证书：普通登录拿到签名地址，助手令牌拿到的地址是空的（签名地址 7 天有效、不认人，不发给助手） */
    @Test
    void signedFileUrls_withheldFromAssistant() throws Exception {
        Account a = account("buyer");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long supplier = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13) RETURNING id""", Long.class, "AST" + tag, "助手测试" + tag, "助手测试" + tag);
        jdbc.update("INSERT INTO sup_supplier_cert (supplier_id, cert_type, file_url, file_name) VALUES (?, 'LICENSE', ?, 'license.jpg')",
                supplier, "/files/2026/11/22/" + tag + ".jpg");
        try {
            Reply mine = send(a.token(), "GET", "/api/suppliers/" + supplier + "/certs", null);
            assertThat(mine.code()).as(mine.body().toString()).isZero();
            assertThat(mine.body().path("data").get(0).path("fileUrl").stringValue()).startsWith("/files/2026/11/22/" + tag + ".jpg?e=");

            String assist = exchange(a).path("token").stringValue();
            Reply theirs = send(assist, "GET", "/api/suppliers/" + supplier + "/certs", null);
            assertThat(theirs.code()).as(theirs.body().toString()).isZero();
            assertThat(theirs.body().path("data").get(0).path("fileName").stringValue()).isEqualTo("license.jpg");
            assertThat(theirs.body().path("data").get(0).path("fileUrl").isNull()).as("助手拿不到文件地址").isTrue();
        } finally {
            jdbc.update("DELETE FROM sup_supplier_cert WHERE supplier_id = ?", supplier);
            jdbc.update("DELETE FROM sup_supplier WHERE id = ?", supplier);
        }
    }

    /**
     * 供应商联系方式、银行信息不发给外部模型（用户 11-22 定）：采购（预置角色两个权限都有）自己登录看得到联系人和遮过的账号，
     * 同一个人用助手令牌查同一家，联系人、电话、微信、地址、开户行、户名、账号都没有；名称、信用代码照常
     */
    @Test
    void supplierContactAndBank_withheldFromAssistant() throws Exception {
        Account a = account("buyer");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long supplier = jdbc.queryForObject("""
                INSERT INTO sup_supplier (code, name, short_name, status, settle_method, tax_pct, credit_code,
                                          contact, phone, wechat, address, bank_name, bank_account, bank_account_name)
                VALUES (?, ?, ?, 'ACTIVE', 'MONTHLY', 13, ?, '张三', '13800000000', 'zs-wx', '示例路 1 号', '示例银行', '6222000011112222', '示例户名')
                RETURNING id""", Long.class, "AST" + tag, "助手测试" + tag, "助手测试" + tag, "91330000AST" + tag.substring(0, 7));
        try {
            JsonNode mine = send(a.token(), "GET", "/api/suppliers/" + supplier, null).body().path("data");
            assertThat(mine.path("contact").stringValue()).isEqualTo("张三");
            assertThat(mine.path("bankAccount").stringValue()).as("自己看是遮过的").startsWith("6222").endsWith("2222").contains("*");

            String assist = exchange(a).path("token").stringValue();
            Reply theirs = send(assist, "GET", "/api/suppliers/" + supplier, null);
            assertThat(theirs.code()).as(theirs.body().toString()).isZero();
            JsonNode d = theirs.body().path("data");
            assertThat(d.path("name").stringValue()).isEqualTo("助手测试" + tag);
            assertThat(d.path("creditCode").stringValue()).isEqualTo("91330000AST" + tag.substring(0, 7));
            for (String field : List.of("contact", "phone", "wechat", "address", "bankName", "bankAccount", "bankAccountName")) {
                assertThat(d.path(field).isNull() || d.path(field).isMissingNode()).as("助手拿不到 " + field + "：" + d).isTrue();
            }
            Reply list = send(assist, "GET", "/api/suppliers?keyword=" + tag, null);
            assertThat(list.code()).as(list.body().toString()).isZero();
            assertThat(list.body().path("data").path("total").intValue()).as("确实搜中了这一家").isEqualTo(1);
            assertThat(list.body().path("data").path("list").toString()).doesNotContain("张三", "13800000000", "示例银行", "6222");

            // 按联系人名搜：自己（有看联系方式的权限）搜得到，助手搜不到，不然拿名字一搜就知道是哪家的联系人（agy 复查 T-a43c80 #1）
            String byContact = "/api/suppliers?keyword=" + java.net.URLEncoder.encode("张三", java.nio.charset.StandardCharsets.UTF_8);
            assertThat(send(a.token(), "GET", byContact, null).body().path("data").path("list").toString()).contains("助手测试" + tag);
            assertThat(send(assist, "GET", byContact, null).body().path("data").path("list").toString()).doesNotContain("助手测试" + tag);
        } finally {
            jdbc.update("DELETE FROM sup_supplier WHERE id = ?", supplier);
        }
    }

    /** 账号停用：助手令牌和登录一起失效；换它的登录令牌没了（并发退出漏删的那种）：它也不再认 */
    @Test
    void disabledUserOrMissingParent_invalidate() throws Exception {
        Account a = account("buyer");
        String assist = exchange(a).path("token").stringValue();
        jdbc.update("UPDATE sys_user SET status = 0 WHERE id = ?", a.id());
        assertThat(send(assist, "GET", "/api/auth/me", null).status()).isEqualTo(401);

        Account b = account("buyer");
        String assistB = exchange(b).path("token").stringValue();
        jdbc.update("DELETE FROM sys_login_session WHERE jti = ?", jwtUtil.parse(b.token()).getId());
        assertThat(send(assistB, "GET", "/api/auth/me", null).status()).as("挂在已删掉的登录下面").isEqualTo(401);
    }

    /**
     * 只读：路由表里每个非 GET 接口，用助手令牌调一遍，全部 403（过滤器拦，不进 Controller）；
     * 每个不让助手调的 GET（不返回 R 的导出、下载、模板，拿响应对象自己写的，标了 @NoAssistant 的预览、银行账号）也全部 403（拦截器拦）
     */
    @Test
    void everyWriteAndFileEndpoint_forbidden() throws Exception {
        String assist = exchange(account("buyer")).path("token").stringValue();
        Set<String> writes = new TreeSet<>(), files = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            boolean allowed = AssistantScopeInterceptor.allowed(e.getValue());
            for (String pattern : e.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.values()) : methods) {
                    if (m != RequestMethod.GET && m != RequestMethod.HEAD && m != RequestMethod.OPTIONS && m != RequestMethod.TRACE) {
                        writes.add(m.name() + " " + path);
                    } else if (m == RequestMethod.GET && !allowed) {
                        files.add("GET " + path);
                    }
                }
            }
        }
        assertThat(writes).as("路由表里的写接口").hasSizeGreaterThan(200);
        // 写死几条，免得拦截器和这里用同一个判断、漏了一起漏：导出、盘点表、工艺单 Excel 预览、供应商银行账号
        assertThat(files).as("不让助手调的 GET").contains("GET /api/materials/export", "GET /api/inventory-counts/1/sheet",
                "GET /api/tech-packs/1/files/1/preview", "GET /api/suppliers/1/bank");

        List<String> leaked = new ArrayList<>();
        for (String op : writes) {
            String[] parts = op.split(" ", 2);
            Reply reply = send(assist, parts[0], parts[1], Map.of());
            if (reply.status() != 403 || reply.code() != 403) leaked.add(op + " → " + reply.status() + " " + reply.body());
        }
        for (String op : files) {
            Reply reply = send(assist, "GET", op.substring(4), null);
            if (reply.status() != 403 || reply.code() != 403) leaked.add(op + " → " + reply.status() + " " + reply.body());
        }
        assertThat(send(assist, "HEAD", "/api/auth/me", null).status()).as("HEAD 也不是 GET").isEqualTo(403);
        assertThat(leaked).as("助手令牌调得通的写接口、文件接口").isEmpty();
    }

    // ---------------------------------------------------------------- 工具

    private void setAi(boolean on) {
        jdbc.update("""
                INSERT INTO sys_config (config_key, config_value, remark) VALUES ('ai.switch', ?::jsonb, 'AI 功能统一开关')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, "{\"enabled\": " + on + "}");
    }

    /** 建一个用户、登录；role 是 null 时不给角色 */
    private Account account(String role) throws Exception {
        String name = "ast-" + UUID.randomUUID().toString().substring(0, 8);
        String secret = "Ast-" + UUID.randomUUID();
        long user = jdbc.queryForObject("INSERT INTO sys_user (username, password, nickname) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, passwords.encode(secret), name);
        if (role != null) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = ? AND NOT deleted", user, role);
        }
        Reply login = send(null, "POST", "/api/auth/login", Map.of("username", name, "password", secret));
        assertThat(login.code()).as(login.body().toString()).isZero();
        return new Account(user, secret, login.body().path("data").stringValue());
    }

    private JsonNode exchange(Account who) throws Exception {
        Reply reply = send(who.token(), "POST", "/api/auth/assistant-token", Map.of());
        assertThat(reply.code()).as(reply.body().toString()).isZero();
        return reply.body().path("data");
    }

    private Reply send(String token, String method, String path, Object body) throws Exception {
        return sendWith(token, method, path, body, Map.of());
    }

    private Reply sendWith(String token, String method, String path, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (!method.equals("GET")) request.header("Idempotency-Key", UUID.randomUUID().toString());
        headers.forEach(request::header);
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
