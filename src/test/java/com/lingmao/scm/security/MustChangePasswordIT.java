package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首次登录改密码（RFC 018 §2），走真 HTTP、真过滤器：管理员给的密码登录后，业务接口 403、看自己能看（带 mustChangePassword）；
 * 改了密码这次登录作废，用新密码重新登录，一切照常。
 * 注解和 @MockitoBean 跟 AssistantTokenIT 一样，两个共用一个应用
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class MustChangePasswordIT {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PasswordEncoder passwords;
    /** 同 AssistantTokenIT：限流不挡在前面（也为了两个 IT 共用一个应用） */
    @MockitoBean
    private SharedRateLimiter rateLimiter;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    record Reply(int status, JsonNode body) {
        int code() {
            return body.path("code").intValue();
        }
    }

    @AfterEach
    void close() {
        http.close();
    }

    @Test
    void initialPassword_onlyMeAndChange_thenNormal() throws Exception {
        String name = "mcp-" + UUID.randomUUID().toString().substring(0, 8);
        String initial = "Init-" + UUID.randomUUID().toString().substring(0, 8);
        long user = jdbc.queryForObject("""
                INSERT INTO sys_user (username, password, nickname, pwd_must_change) VALUES (?, ?, ?, true) RETURNING id""",
                Long.class, name, passwords.encode(initial), name);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = 'buyer' AND NOT deleted", user);

        String token = login(name, initial);
        Reply me = send(token, "GET", "/api/auth/me", null);
        assertThat(me.code()).isZero();
        assertThat(me.body().path("data").path("mustChangePassword").booleanValue()).isTrue();
        assertThat(me.body().path("data").path("perms").size()).isZero();
        assertThat(me.body().path("data").path("menus").size()).isZero();

        Reply business = send(token, "GET", "/api/users/options", null);   // 平时登录就能调的
        assertThat(business.status()).isEqualTo(403);
        assertThat(business.body().path("msg").stringValue()).isEqualTo(JwtAuthFilter.MUST_CHANGE_MESSAGE);

        String changed = "New-" + UUID.randomUUID().toString().substring(0, 8);
        Reply change = send(token, "PUT", "/api/auth/password", Map.of("oldPassword", initial, "newPassword", changed));
        assertThat(change.code()).as(change.body().toString()).isZero();
        assertThat(jdbc.queryForObject("SELECT pwd_must_change FROM sys_user WHERE id = ?", Boolean.class, user)).isFalse();
        assertThat(send(token, "GET", "/api/auth/me", null).code()).as("改了密码，这次登录作废").isEqualTo(401);

        String fresh = login(name, changed);
        Reply after = send(fresh, "GET", "/api/auth/me", null);
        assertThat(after.body().path("data").path("mustChangePassword").booleanValue()).isFalse();
        assertThat(after.body().path("data").path("perms").size()).isPositive();
        assertThat(send(fresh, "GET", "/api/users/options", null).code()).isZero();
    }

    private String login(String username, String password) throws Exception {
        Reply login = send(null, "POST", "/api/auth/login", Map.of("username", username, "password", password));
        assertThat(login.code()).as(login.body().toString()).isZero();
        return login.body().path("data").stringValue();
    }

    private Reply send(String token, String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (!method.equals("GET")) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        HttpResponse<byte[]> response = http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] raw = response.body();
        return new Reply(response.statusCode(), raw.length == 0 ? json.createObjectNode() : json.readTree(raw));
    }
}
