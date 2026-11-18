package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** T10: actual TCP requests, actual cookies, real row locks and credential changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lingmao.proxy.trusted-cidrs=127.0.0.1/32,::1/128")
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class A2RefreshHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired JwtUtil jwt;
    private static final String PASSWORD = "A2-Valid-Pass-123";

    @Test void concurrentOldCookieRequestsReplayOneSuccessorWithBoundedGrace() throws Exception {
        var account = account(false);
        try (HttpClient client = HttpClient.newHttpClient(); HttpClient second = HttpClient.newHttpClient()) {
            Reply login = login(client, account, PASSWORD, false);
            String oldCookie = login.cookies;
            assertThat(login.body).doesNotContain("refreshToken", "browserToken");
            assertThat(login.headers).allSatisfy(h -> assertThat(h).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth"));
            CountDownLatch start = new CountDownLatch(1);
            Reply one, two;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var a = pool.submit(() -> { start.await(); return refresh(client, oldCookie, true); });
                var b = pool.submit(() -> { start.await(); return refresh(second, oldCookie, true); });
                start.countDown();
                one = a.get(10, TimeUnit.SECONDS); two = b.get(10, TimeUnit.SECONDS);
            }
            assertThat(one.status).isEqualTo(200);
            assertThat(two.status).isEqualTo(200);
            assertThat(one.access).isEqualTo(two.access).isNotEqualTo(login.access);
            assertThat(one.cookies).isEqualTo(two.cookies).isNotEqualTo(oldCookie);
            assertThat(jdbc.queryForObject("SELECT grace_replays FROM sys_refresh_session WHERE user_id=?", Integer.class, account.id)).isEqualTo(1);
            assertThat(refresh(client, oldCookie, true).status).isEqualTo(200);
            assertThat(refresh(client, oldCookie, true).status).isEqualTo(401);
            assertThat(request(client, "GET", "/api/auth/me", null, one.access, null, Map.of()).status).isEqualTo(200);
            // An expired/replayed predecessor must never revoke the valid successor.
            Thread.sleep(3_100);
            assertThat(refresh(client, oldCookie, true).status).isEqualTo(401);
            assertThat(refresh(second, one.cookies, true).status).isEqualTo(200);
            String persisted = jdbc.queryForObject("SELECT token_hash || browser_hash || rotation_nonce FROM sys_refresh_session WHERE user_id=?", String.class, account.id);
            assertThat(persisted).doesNotContain(cookieValue(oldCookie, SessionCookies.REFRESH), cookieValue(oldCookie, SessionCookies.BROWSER));
        }
    }

    @Test void requiresAjaxHeaderAndBrowserBindingAndHonorsTrustedHttps() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            Reply login = login(client, account(false), PASSWORD, true);
            assertThat(login.headers).allSatisfy(h -> assertThat(h).contains("Secure"));
            assertThat(refresh(client, login.cookies, false).status).isEqualTo(401);
            String wrong = login.cookies.replace(cookieValue(login.cookies, SessionCookies.BROWSER), "a".repeat(43));
            assertThat(refresh(client, wrong, true).status).isEqualTo(401);
            assertThat(request(client, "POST", "/api/auth/refresh", "{}", null, login.cookies,
                    Map.of("X-Requested-With", "XMLHttpRequest", "Sec-Fetch-Site", "cross-site")).status).isEqualTo(401);
            assertThat(refresh(client, login.cookies, true).status).isEqualTo(200);
        }
    }

    @Test void actualPasswordChangeRevokesEveryAccessAndRefreshFamily() throws Exception {
        var account = account(false);
        try (HttpClient client = HttpClient.newHttpClient()) {
            Reply first = login(client, account, PASSWORD, false), second = login(client, account, PASSWORD, false);
            Reply changed = request(client, "PUT", "/api/auth/password",
                    json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", "A2-New-Pass-123")),
                    first.access, first.cookies, Map.of());
            assertThat(json.readTree(changed.body).get("code").intValue()).isZero();
            assertThat(changed.headers).allSatisfy(h -> assertThat(h).contains("Max-Age=0"));
            assertThat(jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=?", Long.class, account.id)).isEqualTo(1L);
            for (Reply old : java.util.List.of(first, second)) {
                assertThat(refresh(client, old.cookies, true).status).isEqualTo(401);
                assertThat(request(client, "GET", "/api/auth/me", null, old.access, null, Map.of()).status).isEqualTo(401);
            }
            assertThat(login(client, account, "A2-New-Pass-123", false).status).isEqualTo(200);
        }
    }

    @Test void actualAdminDisableAndResetEndpointsRevokeIssuedSessions() throws Exception {
        var admin = account(true); var target = account(false);
        try (HttpClient client = HttpClient.newHttpClient()) {
            Reply operator = login(client, admin, PASSWORD, false), login = login(client, target, PASSWORD, false);
            Reply reset = request(client, "POST", "/api/users/" + target.id + "/reset-password",
                    "{\"password\":\"A2-Reset-Pass-123\"}", operator.access, null, Map.of());
            assertThat(json.readTree(reset.body).get("code").intValue()).as(reset.body).isZero();
            assertThat(refresh(client, login.cookies, true).status).isEqualTo(401);
            assertThat(request(client, "GET", "/api/auth/me", null, login.access, null, Map.of()).status).isEqualTo(401);
            Reply afterReset = login(client, target, "A2-Reset-Pass-123", false);
            Reply disabled = request(client, "PUT", "/api/users/" + target.id,
                    "{\"nickname\":\"Disabled A2\",\"status\":0}", operator.access, null, Map.of());
            assertThat(json.readTree(disabled.body).get("code").intValue()).as(disabled.body).isZero();
            assertThat(refresh(client, afterReset.cookies, true).status).isEqualTo(401);
            assertThat(request(client, "GET", "/api/auth/me", null, afterReset.access, null, Map.of()).status).isEqualTo(401);
        }
    }

    @Test void logoutWithExpiredAccessStillRevokesCookieFamily() throws Exception {
        var account = account(false);
        try (HttpClient client = HttpClient.newHttpClient()) {
            Reply login = login(client, account, PASSWORD, false);
            String jti = jwt.parse(login.access).getId();
            String expired = jwt.createToken(account.id, jti, java.time.Instant.now().minus(Duration.ofDays(1)));
            Reply logout = request(client, "POST", "/api/auth/logout", "{}", expired, login.cookies,
                    Map.of("X-Requested-With", "XMLHttpRequest"));
            assertThat(logout.status).isEqualTo(200);
            assertThat(json.readTree(logout.body).get("code").intValue()).isZero();
            assertThat(logout.headers).allSatisfy(h -> assertThat(h).contains("Max-Age=0"));
            assertThat(refresh(client, login.cookies, true).status).isEqualTo(401);
            assertThat(request(client, "GET", "/api/auth/me", null, login.access, null, Map.of()).status).isEqualTo(401);
        }
    }

    @Test void concurrentAdminResetWinsOverSelfChangeThatVerifiedTheOldHash() throws Exception {
        var admin = account(true); var target = account(false);
        try (HttpClient first = HttpClient.newHttpClient(); HttpClient second = HttpClient.newHttpClient();
             var blocker = dataSource.getConnection(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Reply operator = login(first, admin, PASSWORD, false), user = login(second, target, PASSWORD, false);
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM sys_user WHERE id=? FOR UPDATE")) {
                lock.setLong(1, target.id); lock.executeQuery().close();
            }
            var reset = pool.submit(() -> request(first, "POST", "/api/users/" + target.id + "/reset-password",
                    "{\"password\":\"A2-Reset-Race-123\"}", operator.access, null, Map.of()));
            awaitPasswordWaiters(1);
            var change = pool.submit(() -> request(second, "PUT", "/api/auth/password",
                    json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", "A2-Stale-Race-123")),
                    user.access, user.cookies, Map.of()));
            awaitPasswordWaiters(2);
            blocker.commit();
            assertThat(json.readTree(reset.get(10, TimeUnit.SECONDS).body).get("code").intValue()).isZero();
            assertThat(change.get(10, TimeUnit.SECONDS).status).isEqualTo(401);
            assertThat(passwords.matches("A2-Reset-Race-123", jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, target.id))).isTrue();
            assertThat(jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=?", Long.class, target.id)).isEqualTo(1L);
        }
    }

    private void awaitPasswordWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%UPDATE sys_user%'", Integer.class);
            if (waiting != null && waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("password updates did not reach the PostgreSQL lock queue");
    }

    private Account account(boolean admin) {
        String name = "a2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long id = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,?,?) RETURNING id", Long.class,
                name, passwords.encode(PASSWORD), name);
        if (admin) jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT ?,id FROM sys_role WHERE code='admin' AND NOT deleted", id);
        return new Account(id, name);
    }

    private Reply login(HttpClient client, Account account, String password, boolean https) throws Exception {
        Reply response = request(client, "POST", "/api/auth/login", json.writeValueAsString(Map.of("username", account.name, "password", password)),
                null, null, https ? Map.of("X-Forwarded-Proto", "https") : Map.of());
        assertThat(json.readTree(response.body).get("code").intValue()).as(response.body).isZero();
        assertThat(response.cookies).contains("scm_refresh=", "scm_browser=");
        return response;
    }

    private Reply refresh(HttpClient client, String cookies, boolean header) throws Exception {
        return request(client, "POST", "/api/auth/refresh", "{}", null, cookies,
                header ? Map.of("X-Requested-With", "XMLHttpRequest") : Map.of());
    }

    private Reply request(HttpClient client, String method, String path, String body, String access, String cookies,
                          Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (body != null) builder.header("Content-Type", "application/json");
        if (access != null) builder.header("Authorization", "Bearer " + access);
        if (cookies != null) builder.header("Cookie", cookies);
        headers.forEach(builder::header);
        var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        var parsed = json.readTree(response.body());
        var setCookies = response.headers().allValues("Set-Cookie");
        Map<String, String> values = new LinkedHashMap<>();
        for (String cookie : setCookies) { String pair = cookie.split(";", 2)[0]; values.put(pair.split("=", 2)[0], pair); }
        return new Reply(response.statusCode(), response.body(), parsed.has("data") && parsed.get("data").isString() ? parsed.get("data").stringValue() : null,
                String.join("; ", values.values()), setCookies);
    }

    private static String cookieValue(String header, String name) {
        return java.util.Arrays.stream(header.split("; ")).filter(v -> v.startsWith(name + "=")).findFirst().orElseThrow().split("=", 2)[1];
    }
    private record Account(long id, String name) { }
    private record Reply(int status, String body, String access, String cookies, java.util.List<String> headers) { }
}
