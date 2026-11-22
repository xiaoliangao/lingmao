package com.lingmao.scm.common.file;

import com.lingmao.scm.common.auth.CallerScope;
import com.lingmao.scm.common.file.FileUrlSigner.Verdict;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUrlSignerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KEY = "test-file-sign-key-0123456789abcdef";
    private static final String PATH = "/files/2026/11/15/0b6f5c2e-3d4a-4f8e-9c1b-2a7d6e5f4c3b.jpg";
    private static final String THUMB = "/files/2026/11/15/0b6f5c2e-3d4a-4f8e-9c1b-2a7d6e5f4c3b_s.jpg";

    /** 2026-11-22 00:00 东八区：11-15 签出来的地址都在这个时间过期 */
    private static final long NOV_22 = 1795276800L;

    /**
     * 规则：和 Nginx 的 secure_link_md5 "$secure_link_expires$uri <密钥>" 算出来的一样。
     * 期望值不是用这个类算的，是照 Nginx 文档的办法用 openssl 算的：
     * echo -n '1795276800/files/2026/11/15/0b6f…3b.jpg test-file-sign-key-0123456789abcdef' \
     * | openssl md5 -binary | openssl base64 | tr +/ -_ | tr -d =
     * 缩略图是另一个 uri，签名不一样（这个值里有 _，base64url 的替换也验到了）
     */
    @Test
    void sign_matchesNginxSecureLink() {
        FileUrlSigner signer = signerAt("2026-11-15T10:00:00+08:00");

        assertThat(signer.sign(PATH)).isEqualTo(PATH + "?e=1795276800&s=C0eTHY3bqXS3P0oYrbeJAg");
        assertThat(signer.sign(THUMB)).isEqualTo(THUMB + "?e=1795276800&s=YnqgzdniS78fChl5_yL3Uw");
    }

    /** 规则：过期时间是 7 天后的零点（东八区）：同一天签出来的地址一模一样，浏览器缓存才有效；第二天就换了 */
    @Test
    void sign_sameDaySameUrl_nextDayChanges() {
        String morning = signerAt("2026-11-15T00:00:00+08:00").sign(PATH);

        assertThat(signerAt("2026-11-15T23:59:59+08:00").sign(PATH)).isEqualTo(morning);
        assertThat(morning).contains("?e=" + NOV_22 + "&");
        assertThat(signerAt("2026-11-16T00:00:00+08:00").sign(PATH)).contains("?e=" + (NOV_22 + 86400) + "&");
    }

    /** 规则：空值、外部地址原样返回；签过的再签一次和签一次一样；strip 还原成库里存的样子 */
    @Test
    void signAndStrip_edgeCases() {
        FileUrlSigner signer = signerAt("2026-11-15T10:00:00+08:00");
        String signed = signer.sign(PATH);

        assertThat(signer.sign(null)).isNull();
        assertThat(signer.sign("")).isEmpty();
        assertThat(signer.sign("https://example.com/a.jpg")).isEqualTo("https://example.com/a.jpg");
        assertThat(signer.sign(signed)).isEqualTo(signed);
        // 前端传回来的签过名的地址（几天前签的也一样）→ 存进库的样子
        assertThat(signer.strip(signed)).isEqualTo(PATH);
        assertThat(signer.strip(signerAt("2026-11-01T10:00:00+08:00").sign(PATH))).isEqualTo(PATH);
        assertThat(signer.strip(PATH)).isEqualTo(PATH);
        assertThat(signer.strip(null)).isNull();
        assertThat(signer.strip("https://example.com/a.jpg?x=1")).isEqualTo("https://example.com/a.jpg?x=1");
    }

    /**
     * 规则（照 Nginx）：签名对、没过期 VALID；过期时间正好是现在还算有效，过了一秒 EXPIRED；
     * 没签名、签名不对、换了文件、改了过期时间都是 INVALID
     */
    @Test
    void verify_likeNginx() {
        String s = "C0eTHY3bqXS3P0oYrbeJAg";
        String e = String.valueOf(NOV_22);

        assertThat(signerAt("2026-11-15T10:00:00+08:00").verify(PATH, e, s)).isEqualTo(Verdict.VALID);
        assertThat(signerAt("2026-11-22T00:00:00+08:00").verify(PATH, e, s)).isEqualTo(Verdict.VALID);
        assertThat(signerAt("2026-11-22T00:00:01+08:00").verify(PATH, e, s)).isEqualTo(Verdict.EXPIRED);

        FileUrlSigner signer = signerAt("2026-11-15T10:00:00+08:00");
        assertThat(signer.verify(PATH, null, null)).isEqualTo(Verdict.INVALID);
        assertThat(signer.verify(PATH, e, "D0eTHY3bqXS3P0oYrbeJAg")).isEqualTo(Verdict.INVALID);
        assertThat(signer.verify(THUMB, e, s)).isEqualTo(Verdict.INVALID);
        assertThat(signer.verify(PATH, String.valueOf(NOV_22 + 86400), s)).isEqualTo(Verdict.INVALID);
        assertThat(signer.verify(PATH, "-1", s)).isEqualTo(Verdict.INVALID);
        assertThat(signer.verify(PATH, e, "不是base64")).isEqualTo(Verdict.INVALID);
    }

    /**
     * 规则：拿助手令牌来的请求不发签名地址，返回 null（RFC 017 §3.3）：签名地址 7 天有效、不认人，发出去收不回来。
     * 外部地址照旧原样；同一个人用普通登录照旧签
     */
    @Test
    void assistantRequest_noSignedUrl() {
        FileUrlSigner signer = signerAt("2026-11-15T10:00:00+08:00");
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", null,
                    List.of(new SimpleGrantedAuthority("order:order:list"), new SimpleGrantedAuthority(CallerScope.ASSISTANT_AUTHORITY))));
            assertThat(signer.sign(PATH)).isNull();
            assertThat(signer.sign("https://example.com/a.jpg")).isEqualTo("https://example.com/a.jpg");

            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", null,
                    List.of(new SimpleGrantedAuthority("order:order:list"))));
            assertThat(signer.sign(PATH)).isEqualTo(PATH + "?e=1795276800&s=C0eTHY3bqXS3P0oYrbeJAg");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 规则：没配密钥、密钥太短或带了 Nginx 配置里会出问题的字符，启动时就报出来，说清楚缺什么 */
    @Test
    void badKey_failsFast() {
        Clock clock = Clock.system(ZONE);

        assertThatThrownBy(() -> new FileUrlSigner("", clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("没有配置 FILE_SIGN_KEY");
        assertThatThrownBy(() -> new FileUrlSigner("too-short", clock))
                .hasMessageStartingWith("FILE_SIGN_KEY 要至少 32 位");
        assertThatThrownBy(() -> new FileUrlSigner("0123456789abcdef0123456789abcdef\"; return 200", clock))
                .hasMessageStartingWith("FILE_SIGN_KEY 要至少 32 位");
    }

    private static FileUrlSigner signerAt(String time) {
        return new FileUrlSigner(KEY, Clock.fixed(OffsetDateTime.parse(time).toInstant(), ZONE));
    }
}
