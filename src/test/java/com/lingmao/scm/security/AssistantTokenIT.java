package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.common.result.R;
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
    private LoginSessionStore sessions;
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

    /** 刷新登录令牌时，助手令牌改挂到新令牌下面：刷新不打断正在用的助手，新令牌退出时它一起失效 */
    @Test
    void refreshRotation_reparentsAssistantToken() {
        long user = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        long version = jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id = ?", Long.class, user);
        String oldLogin = UUID.randomUUID().toString(), newLogin = UUID.randomUUID().toString(), assist = UUID.randomUUID().toString();
        sessions.saveVersion(oldLogin, user, version, Instant.now());
        sessions.saveScoped(assist, user, LoginUser.SCOPE_ASSISTANT, oldLogin, Instant.now().plusSeconds(1800));

        sessions.reparent(oldLogin, newLogin);
        sessions.removeAccess(oldLogin);
        sessions.saveVersion(newLogin, user, version, Instant.now());
        assertThat(sessions.lookup(assist)).isEqualTo(new LoginSessionStore.Session(user, LoginUser.SCOPE_ASSISTANT));

        sessions.removeAccess(newLogin);
        assertThat(sessions.lookup(assist)).isNull();
    }

    /**
     * 只读：路由表里每个非 GET 接口，用助手令牌调一遍，全部 403（过滤器拦，不进 Controller）；
     * 每个不返回 R 的 GET（导出、下载、打印、模板、预览）也全部 403（拦截器拦）
     */
    @Test
    void everyWriteAndFileEndpoint_forbidden() throws Exception {
        String assist = exchange(account("buyer")).path("token").stringValue();
        Set<String> writes = new TreeSet<>(), files = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            boolean returnsR = R.class.isAssignableFrom(e.getValue().getMethod().getReturnType());
            for (String pattern : e.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.values()) : methods) {
                    if (m != RequestMethod.GET && m != RequestMethod.HEAD && m != RequestMethod.OPTIONS && m != RequestMethod.TRACE) {
                        writes.add(m.name() + " " + path);
                    } else if (m == RequestMethod.GET && !returnsR) {
                        files.add("GET " + path);
                    }
                }
            }
        }
        assertThat(writes).as("路由表里的写接口").hasSizeGreaterThan(200);
        assertThat(files).as("返回文件的 GET").anyMatch(s -> s.endsWith("/export"));

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
