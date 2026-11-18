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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lingmao.proxy.trusted-cidrs=", "lingmao.cors.allowed-origins=https://client.example.test"})
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class A2InputHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired SharedRateLimiter rates;
    private static final String PASSWORD = "A2-Input-Pass-123";

    @Test void untrustedForwardedHeadersDoNotSetSecureCookiesAndForeignTenantIsRejected() throws Exception {
        String username = account();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var login = send(client, "/api/auth/login", loginBody(username), null, Map.of("X-Forwarded-Proto", "https", "X-Forwarded-For", "203.0.113.19"), false);
            assertThat(json.readTree(login.body()).get("code").intValue()).isZero();
            assertThat(login.headers().allValues("Set-Cookie")).allSatisfy(c -> assertThat(c).doesNotContain("; Secure"));
            var foreign = send(client, "/api/auth/login", loginBody(username), null, Map.of("X-Tenant-Id", "another-installation"), false);
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThat(foreign.headers().firstValue("X-Request-Id")).isPresent();
        }
    }

    @Test void unknownDtoPropertiesAndKnownLengthOrChunkedLargeJsonFailBeforeMutation() throws Exception {
        String username = account();
        try (HttpClient client = HttpClient.newHttpClient()) {
            String hostile = json.writeValueAsString(Map.of("username", username, "password", PASSWORD, "status", "APPROVED", "paid_amount", 999999));
            assertThat(send(client, "/api/auth/login", hostile, null, Map.of(), false).statusCode()).isEqualTo(400);
            String large = "{\"username\":\"" + "x".repeat(2 * 1024 * 1024) + "\",\"password\":\"anything\"}";
            for (boolean chunked : new boolean[]{false, true}) {
                var response = send(client, "/api/auth/login", large, null, Map.of(), chunked);
                assertThat(response.statusCode()).as("chunked=%s", chunked).isEqualTo(413);
                assertThat(json.readTree(response.body()).get("code").intValue()).isEqualTo(413);
                assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
                assertThat(response.headers().firstValue("X-Request-Id")).isEqualTo(response.headers().firstValue("X-Trace-Id"));
            }
            String trailing = loginBody(username) + " ".repeat(2 * 1024 * 1024);
            assertThat(send(client, "/api/auth/login", trailing, null, Map.of(), true).statusCode()).isEqualTo(413);
            assertThat(send(client, "/api/auth/login", loginBody(username) + " {}", null, Map.of(), true).statusCode()).isEqualTo(400);
        }
    }

    @Test void corsOnlyAllowsExactConfiguredOriginAndExplicitHeaders() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String origin : new String[]{"https://client.example.test", "https://client.example.test.evil.invalid"}) {
                var request = HttpRequest.newBuilder(URI.create(base() + "/api/auth/refresh")).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-requested-with").build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (origin.equals("https://client.example.test")) {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                    assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
                } else { assertThat(response.statusCode()).isEqualTo(403); assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty(); }
            }
        }
    }

    @Test void spoofedIpsDoNotEscapePerUserLimitAndOfficeColleaguesKeepTheirOwnQuota() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String first = login(client, account());
            for (int i = 0; i < 6; i++) {
                var response = send(client, "/api/auth/password", "{\"oldPassword\":\"wrong\",\"newPassword\":\"Valid-Changed-123\"}", first,
                        Map.of("X-Forwarded-For", "203.0.113." + (i + 1)), false, "PUT");
                assertThat(response.statusCode()).isNotEqualTo(429);
            }
            var limited = send(client, "/api/auth/password", "{\"oldPassword\":\"wrong\",\"newPassword\":\"Valid-Changed-123\"}", first,
                    Map.of("X-Forwarded-For", "203.0.113.250"), false, "PUT");
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(limited.headers().firstValue("X-Request-Id")).isPresent();
            String colleague = login(client, account());
            assertThat(send(client, "/api/auth/password", "{\"oldPassword\":\"wrong\",\"newPassword\":\"Valid-Changed-123\"}", colleague,
                    Map.of(), false, "PUT").statusCode()).isNotEqualTo(429);
        }
    }

    @Test void realUploadAndAsyncDownloadEndpointsUseTheSharedUserQuota() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String username = account(); String token = login(client, username);
            Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
            for (int i = 0; i < 60; i++) assertThat(rates.acquire("upload:" + userId + ":127.0.0.1", 60, Duration.ofMinutes(1))).isZero();
            for (String path : new String[]{"/api/files", "/api/materials/import", "/api/styles/import",
                    "/api/suppliers/import", "/api/orders/import", "/api/inventory-counts/123/import"})
                assertThat(send(client, path, "{}", token, Map.of(), false).statusCode()).as(path).isEqualTo(429);
            for (int i = 0; i < 12; i++) assertThat(rates.acquire("export:" + userId + ":127.0.0.1", 12, Duration.ofMinutes(1))).isZero();
            for (String path : new String[]{"/api/export-jobs/123/file", "/api/inventory-counts/123/sheet"})
                assertThat(send(client, path, "", token, Map.of(), false, "GET").statusCode()).as(path).isEqualTo(429);
        }
    }

    @Test void redisOomFailsSecurityGatesClosedWithStructured503AndTrace() throws Exception {
        String username = account();
        try (HttpClient client = HttpClient.newHttpClient(); var connection = redis.getConnectionFactory().getConnection()) {
            String token = login(client, username);
            String originalMax = connection.serverCommands().getConfig("maxmemory").getProperty("maxmemory");
            String originalPolicy = connection.serverCommands().getConfig("maxmemory-policy").getProperty("maxmemory-policy");
            try {
                connection.serverCommands().setConfig("maxmemory-policy", "noeviction");
                connection.serverCommands().setConfig("maxmemory", "1");
                var response = send(client, "/api/files", "{}", token, Map.of(), false);
                assertThat(response.statusCode()).isEqualTo(503);
                assertThat(json.readTree(response.body()).get("code").intValue()).isEqualTo(503);
                assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
                assertThat(response.headers().firstValue("Retry-After")).contains("3");
                var failures = new LoginFailCounter(redis, new com.lingmao.scm.common.scope.DeploymentNamespace("oomtest"));
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> failures.recordFailure(username)).isInstanceOf(SecurityStateUnavailable.class);
            } finally {
                connection.serverCommands().setConfig("maxmemory", originalMax);
                connection.serverCommands().setConfig("maxmemory-policy", originalPolicy);
            }
        }
    }

    private String account() {
        String name = "a2i_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("INSERT INTO sys_user(username,password,nickname) VALUES(?,?,?)", name, passwords.encode(PASSWORD), name);
        return name;
    }
    private String loginBody(String username) { return json.writeValueAsString(Map.of("username", username, "password", PASSWORD)); }
    private String login(HttpClient client, String username) throws Exception {
        var reply = send(client, "/api/auth/login", loginBody(username), null, Map.of(), false);
        assertThat(json.readTree(reply.body()).get("code").intValue()).isZero();
        return json.readTree(reply.body()).get("data").stringValue();
    }
    private String base() { return "http://127.0.0.1:" + port; }
    private HttpResponse<String> send(HttpClient client, String path, String body, String token, Map<String, String> headers, boolean chunked) throws Exception {
        return send(client, path, body, token, headers, chunked, "POST");
    }
    private HttpResponse<String> send(HttpClient client, String path, String body, String token, Map<String, String> headers, boolean chunked, String method) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                : HttpRequest.BodyPublishers.ofString(body);
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
