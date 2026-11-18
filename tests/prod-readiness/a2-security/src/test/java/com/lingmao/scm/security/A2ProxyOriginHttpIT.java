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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP through the configured trusted-peer boundary, including non-default published ports. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lingmao.proxy.trusted-cidrs=127.0.0.1/32", "lingmao.cors.allowed-origins="})
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class A2ProxyOriginHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper json;

    @Test void trustedHttpsKeepsTheBrowserPortAndIssuesSecureCookies() throws Exception {
        var response = login("https", "https://127.0.0.1:" + port);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("code").intValue()).isZero();
        var cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies.size()).isEqualTo(2);
        assertThat(cookies.stream().allMatch(value -> value.contains("Secure") && value.contains("HttpOnly"))).isTrue();
    }

    @Test void differentOriginOrSchemeRemainsForbiddenWithoutAnAllowlist() throws Exception {
        assertThat(login("https", "https://127.0.0.1.evil.invalid:" + port).statusCode()).isEqualTo(403);
        assertThat(login("https", "http://127.0.0.1:" + port).statusCode()).isEqualTo(403);
    }

    @Test void ordinaryHttpStillAcceptsItsOwnHostAndPortWithoutSecureCookies() throws Exception {
        var response = login("http", "http://127.0.0.1:" + port);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("code").intValue()).isZero();
        assertThat(response.headers().allValues("Set-Cookie").stream().noneMatch(value -> value.contains("Secure"))).isTrue();
    }

    private HttpResponse<String> login(String forwardedScheme, String origin) throws Exception {
        String username = "it-origin-" + UUID.randomUUID().toString().substring(0, 12);
        String password = "Origin-" + UUID.randomUUID().toString().substring(0, 18);
        jdbc.update("INSERT INTO sys_user(username,password,nickname) VALUES(?,?,?)", username, passwords.encode(password), username);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Origin", origin).header("X-Forwarded-Proto", forwardedScheme)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", username, "password", password))))
                    .build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
