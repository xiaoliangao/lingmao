package com.lingmao.scm.common.dingtalk;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉企业内部应用的三个接口（RFC 019 §4）：取 access_token、按手机号查 userid、发工作通知。
 * <p>
 * 配置在部署的环境变量里（密钥不进库、不进页面）：{@code SCM_DINGTALK_APP_KEY}、{@code SCM_DINGTALK_APP_SECRET}、
 * {@code SCM_DINGTALK_AGENT_ID}，三个都有才算配好；没配好时什么都不发（{@link #configured()}）。
 * access_token 两小时有效，缓存着，剩不到 5 分钟时重新取。
 * <p>
 * 出错一律抛 {@link DingTalkException}：网络不通是 errcode -1，钉钉返回的 errcode、errmsg 原样带上（不含密钥）
 */
@Component
public class DingTalkClient {

    /** 按手机号查不到人（不在这个企业、已离职） */
    private static final int USER_NOT_FOUND = 60121;

    private final String baseUrl;
    private final String appKey;
    private final String appSecret;
    private final Long agentId;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;

    private String token;
    private Instant tokenUntil = Instant.EPOCH;

    public DingTalkClient(@Value("${scm.dingtalk.base-url:https://oapi.dingtalk.com}") String baseUrl,
                          @Value("${scm.dingtalk.app-key:}") String appKey,
                          @Value("${scm.dingtalk.app-secret:}") String appSecret,
                          @Value("${scm.dingtalk.agent-id:}") String agentId,
                          JsonMapper json, Clock clock) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.appKey = appKey.strip();
        this.appSecret = appSecret.strip();
        this.agentId = parseAgentId(agentId);
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** AgentId 是一串数字；填错了不让应用起不来，当没配，日志里说一声 */
    private static Long parseAgentId(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            LoggerFactory.getLogger(DingTalkClient.class).warn("dingtalk_agent_id_invalid：SCM_DINGTALK_AGENT_ID 应该是一串数字，钉钉推送先不开");
            return null;
        }
    }

    /** 三个配置都有 */
    public boolean configured() {
        return !appKey.isEmpty() && !appSecret.isEmpty() && agentId != null;
    }

    /** 按手机号查钉钉 userid；钉钉里没这个人返回 null */
    public String userIdByMobile(String mobile) {
        JsonNode body = post("/topapi/v2/user/getbymobile", Map.of("mobile", mobile), true);
        return body.path("result").path("userid").asString(null);
    }

    /**
     * 发一条工作通知给一个人。url 不是 null 时是卡片（下面一个按钮，点了打开 url），否则是一段 markdown。
     * 钉钉收了就算成功（它是异步发的，送没送到另查，一期不查）
     */
    public void send(String userId, String title, String markdown, String buttonTitle, String url) {
        Map<String, Object> msg = new LinkedHashMap<>();
        if (url != null) {
            msg.put("msgtype", "action_card");
            msg.put("action_card", Map.of("title", title, "markdown", markdown, "single_title", buttonTitle, "single_url", url));
        } else {
            msg.put("msgtype", "markdown");
            msg.put("markdown", Map.of("title", title, "text", markdown));
        }
        post("/topapi/message/corpconversation/asyncsend_v2", Map.of("agent_id", agentId, "userid_list", userId, "msg", msg), false);
    }

    private synchronized String token() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenUntil.minus(Duration.ofMinutes(5)))) {
            return token;
        }
        String url = baseUrl + "/gettoken?appkey=" + enc(appKey) + "&appsecret=" + enc(appSecret);
        JsonNode body = call(HttpRequest.newBuilder(URI.create(url)).GET());
        String got = body.path("access_token").asString("");
        if (got.isBlank()) {
            throw new DingTalkException(-1, "钉钉没给 access_token");   // 不缓存空的（agy T-e19e38 #12）
        }
        token = got;
        tokenUntil = now.plusSeconds(body.path("expires_in").asLong(7200));
        return token;
    }

    private JsonNode post(String path, Map<String, ?> payload, boolean notFoundIsEmpty) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path + "?access_token=" + enc(token())))
                .header("Content-Type", "application/json;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        try {
            return call(req);
        } catch (DingTalkException e) {
            if (notFoundIsEmpty && e.errcode() == USER_NOT_FOUND) {
                return json.createObjectNode();
            }
            if (e.errcode() == 40014 || e.errcode() == 42001) {
                // token 失效了（被别处重新取过）：下次重新取
                synchronized (this) {
                    token = null;
                }
            }
            throw e;
        }
    }

    private JsonNode call(HttpRequest.Builder req) {
        HttpResponse<String> res;
        try {
            res = http.send(req.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DingTalkException(-1, "连不上钉钉：" + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DingTalkException(-1, "发送被中断");
        }
        if (res.statusCode() != 200) {
            throw new DingTalkException(-1, "钉钉返回 HTTP " + res.statusCode());
        }
        JsonNode body;
        try {
            body = json.readTree(res.body());
        } catch (RuntimeException e) {
            // 代理、防火墙拦了钉钉的域名会回 200 的网页：按发送失败算，不成 500（#12）
            throw new DingTalkException(-1, "钉钉返回的不是 JSON");
        }
        int errcode = body.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new DingTalkException(errcode, body.path("errmsg").asString(""));
        }
        return body;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** 钉钉报错：errcode -1 是网络、HTTP 层的；别的是钉钉的返回码 */
    public static final class DingTalkException extends RuntimeException {
        private final int errcode;

        public DingTalkException(int errcode, String errmsg) {
            super(errcode == -1 ? errmsg : "钉钉报错 " + errcode + "：" + errmsg);
            this.errcode = errcode;
        }

        public int errcode() {
            return errcode;
        }
    }
}
