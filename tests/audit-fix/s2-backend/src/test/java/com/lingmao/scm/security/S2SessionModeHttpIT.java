package com.lingmao.scm.security;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP/IP 兼容入口：无 Web Locks 时只发访问令牌，浏览器 Cookie 不得被读写影响。 */
class S2SessionModeHttpIT extends A2BusinessFixture {
    @Test void accessOnlyLoginHasNoRefreshFamilyAndLogoutCannotRevokeAnotherCookieSession() throws Exception {
        Fixture f = fixture(); Account account = custom(f.company(), "SELF");
        long before = families(account.id());
        var login = auth("POST", "/login", credentials(account), "access-only", null, null);
        assertThat(node(login).path("code").intValue()).isZero();
        assertThat(login.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(families(account.id())).isEqualTo(before);
        String access = node(login).path("data").stringValue();
        assertThat(access).isNotBlank();
        Account other = custom(f.company(), "SELF");
        var cookieLogin = auth("POST", "/login", credentials(other), "cookie", null, null);
        String cookies = cookies(cookieLogin);
        assertThat(cookieLogin.headers().allValues("Set-Cookie")).hasSize(2);
        var logout = auth("POST", "/logout", Map.of(), "access-only", access, cookies);
        assertThat(node(logout).path("code").intValue()).isZero();
        assertThat(logout.headers().allValues("Set-Cookie")).isEmpty();
        Account ended = new Account(account.id(), account.role(), access);
        assertThat(raw(ended, "GET", "/api/auth/me", null, null, null).statusCode()).isEqualTo(401);
        var refreshOther = auth("POST", "/refresh", Map.of(), "cookie", null, cookies);
        assertThat(node(refreshOther).path("code").intValue()).isZero();
        assertThat(refreshOther.headers().allValues("Set-Cookie")).hasSize(2);
    }

    @Test void accessOnlyPasswordChangeRevokesOwnSessionsWithoutWritingCookies() throws Exception {
        Fixture f = fixture(); Account account = custom(f.company(), "SELF");
        var changed = auth("PUT", "/password", Map.of("oldPassword", "A2-Business-123456", "newPassword", "S2-New-Password-123456"),
                "access-only", account.token(), null);
        assertThat(node(changed).path("code").intValue()).isZero();
        assertThat(changed.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(raw(account, "GET", "/api/auth/me", null, null, null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=?", Long.class, account.id())).isEqualTo(1);
    }

    @Test void unknownModeAndAccessOnlyRefreshFailBeforeAnyCookieMutation() throws Exception {
        Fixture f = fixture(); Account account = custom(f.company(), "SELF");
        long before = families(account.id());
        var invalidLogin = auth("POST", "/login", credentials(account), "invalid", null, null);
        assertThat(node(invalidLogin).path("code").intValue()).isEqualTo(400);
        assertThat(invalidLogin.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(families(account.id())).isEqualTo(before);
        for (String mode : List.of("access-only", "invalid")) {
            var refresh = auth("POST", "/refresh", Map.of(), mode, null, null);
            assertThat(node(refresh).path("code").intValue()).isEqualTo(400);
            assertThat(refresh.headers().allValues("Set-Cookie")).isEmpty();
        }
        var unknownLogout = auth("POST", "/logout", Map.of(), "invalid", account.token(), null);
        assertThat(node(unknownLogout).path("code").intValue()).isEqualTo(400);
        assertThat(unknownLogout.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(raw(account, "GET", "/api/auth/me", null, null, null).statusCode()).isEqualTo(200);
        var unknownPassword = auth("PUT", "/password", Map.of("oldPassword", "A2-Business-123456", "newPassword", "S2-Unused-123456"),
                "invalid", account.token(), null);
        assertThat(node(unknownPassword).path("code").intValue()).isEqualTo(400);
        assertThat(unknownPassword.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=?", Long.class, account.id())).isZero();
    }

    private long families(long user) { return jdbc.queryForObject("SELECT count(*) FROM sys_refresh_session WHERE user_id=?", Long.class, user); }
    private Map<String, String> credentials(Account actor) {
        return Map.of("username", jdbc.queryForObject("SELECT username FROM sys_user WHERE id=?", String.class, actor.id()), "password", "A2-Business-123456");
    }
    private HttpResponse<byte[]> auth(String method, String path, Object body, String mode, String access, String cookies) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth" + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Requested-With", "XMLHttpRequest").header("X-Session-Mode", mode);
        if (access != null) builder.header("Authorization", "Bearer " + access);
        if (cookies != null) builder.header("Cookie", cookies);
        return http.send(builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static String cookies(HttpResponse<byte[]> response) {
        return String.join("; ", response.headers().allValues("Set-Cookie").stream().map(cookie -> cookie.split(";", 2)[0]).toList());
    }
}
