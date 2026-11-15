package com.lingmao.scm.common.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * 上传文件的地址签名（架构 §8、RFC 001 §10）。库里存不带签名的 /files/2026/11/21/<随机名>.jpg；
 * 接口返回时加上过期时间和签名 ?e=<过期时间>&s=<签名>。生产由 Nginx 的 secure_link 校验（deploy/package/nginx/site.conf），
 * 开发环境没有 Nginx，由 SignedFileFilter 校验同一个签名：没签名、签名不对 403，过期 410。
 * <p>
 * 用法，每个模块都照这样：
 * <ul>
 *   <li>接口返回文件地址（图片、附件、证照……）：{@code vo.setUrl(fileUrlSigner.sign(row.getFileUrl()))}。
 *       缩略图（文件名多一个 _s）是另一个地址，也要单独 sign</li>
 *   <li>保存接口收到地址（前端把拿到的地址原样传回来）：{@code row.setFileUrl(fileUrlSigner.strip(dto.getUrl()))}，
 *       库里只存不带参数的 /files/...</li>
 * </ul>
 * 不要自己拼 "/files/" + ... 返回给前端，也不要把签过名的地址存进库：签名几天就过期。
 * <p>
 * 算法和 Nginx 的 {@code secure_link_md5 "$secure_link_expires$uri <密钥>"} 一致：MD5(过期时间 + 路径 + 空格 + 密钥)，
 * base64url 编码、去掉末尾的 =（FileUrlSignerTest 钉了一个照 Nginx 文档用 openssl 算出来的值）。
 * 用 MD5 是因为 Nginx 这个模块只支持它；密钥在最后，拿不到密钥算不出签名，MD5 的长度扩展攻击也用不上。
 * <p>
 * 过期时间取 7 天后的零点（东八区，按注入的 Clock）：同一天签出来的地址一模一样，浏览器缓存照样有效；
 * 地址至少能用 6 天，页面开着超过 6 天不刷新，图会打不开，刷新就好。
 */
@Component
public class FileUrlSigner {

    /** 上传文件地址的前缀；不是这个开头的（外部地址）不签名、不处理 */
    public static final String PREFIX = "/files/";

    private static final int VALID_DAYS = 7;

    /** 密钥原样写进 Nginx 配置（http.conf 的 set），只许这些字符，别的字符（引号、$、;）会把配置弄坏 */
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{32,}");

    /** Nginx 只认纯数字的过期时间；18 位以内不会溢出 long */
    private static final Pattern EXPIRES_FORMAT = Pattern.compile("\\d{1,18}");

    /** 校验结果，和 Nginx 的 $secure_link 对应：VALID = "1"，EXPIRED = "0"，INVALID = 空 */
    public enum Verdict { VALID, EXPIRED, INVALID }

    private final String key;
    private final Clock clock;

    public FileUrlSigner(@Value("${lingmao.upload.sign-key}") String key, Clock clock) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("没有配置 FILE_SIGN_KEY（上传文件地址的签名密钥）。部署包在 .env 里，安装、升级脚本会自动生成；"
                    + "开发环境在 application-local.yaml 里加一行，见 application-local.yaml.example");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalStateException("FILE_SIGN_KEY 要至少 32 位，只能用字母、数字、- 和 _：它原样写进 Nginx 配置，别的字符会把配置弄坏");
        }
        this.key = key;
        this.clock = clock;
    }

    /** /files/... 加上过期时间和签名。空值、外部地址原样返回；已经签过的先去掉旧签名再签，签两次和签一次一样 */
    public String sign(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return url;
        }
        String path = strip(url);
        String expires = String.valueOf(LocalDate.now(clock).plusDays(VALID_DAYS).atStartOfDay(clock.getZone()).toEpochSecond());
        return path + "?e=" + expires + "&s=" + Base64.getUrlEncoder().withoutPadding().encodeToString(md5(expires, path));
    }

    /** 去掉签名，还原成库里存的 /files/...。库里的地址不带参数，所以 ? 和 # 后面整个去掉。空值、外部地址原样返回 */
    public String strip(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return url;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                return url.substring(0, i);
            }
        }
        return url;
    }

    /**
     * 校验一个请求：path 是请求的路径（不带参数），expires、signature 是参数 e、s 的原值。
     * 规则照 Nginx：先比签名，对了再看过期；过期时间正好等于现在还算有效
     */
    public Verdict verify(String path, String expires, String signature) {
        if (path == null || expires == null || signature == null || !EXPIRES_FORMAT.matcher(expires).matches()) {
            return Verdict.INVALID;
        }
        long expiresAt = Long.parseLong(expires);
        if (expiresAt <= 0) {
            return Verdict.INVALID;
        }
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }
        // 用 Nginx 拿到的原文 expires 算（不是解析后的数字），两边对同一个地址的结论才一样；比较用定长时间的 isEqual
        if (!MessageDigest.isEqual(given, md5(expires, path))) {
            return Verdict.INVALID;
        }
        return expiresAt < clock.instant().getEpochSecond() ? Verdict.EXPIRED : Verdict.VALID;
    }

    private byte[] md5(String expires, String path) {
        try {
            return MessageDigest.getInstance("MD5").digest((expires + path + " " + key).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // 每个 JDK 都必须带 MD5，走不到这里
            throw new IllegalStateException(e);
        }
    }
}
