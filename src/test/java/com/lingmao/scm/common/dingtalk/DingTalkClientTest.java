package com.lingmao.scm.common.dingtalk;

import com.lingmao.scm.common.dingtalk.DingTalkClient.DingTalkException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉钉三个接口（RFC 019 §4）：本机起一个假的钉钉，看请求发得对不对、错误怎么报 */
class DingTalkClientTest {

    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String sendReply = "{\"errcode\":0,\"task_id\":1}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gettoken", ex -> {
            tokenCalls.incrementAndGet();
            reply(ex, "{\"errcode\":0,\"access_token\":\"tk" + tokenCalls.get() + "\",\"expires_in\":7200}");
        });
        server.createContext("/topapi/v2/user/getbymobile", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(ex, body.contains("13800000000") ? "{\"errcode\":0,\"result\":{\"userid\":\"u1\"}}"
                    : "{\"errcode\":60121,\"errmsg\":\"找不到该用户\"}");
        });
        server.createContext("/topapi/message/corpconversation/asyncsend_v2", ex -> {
            bodies.add(ex.getRequestURI().getQuery() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, sendReply);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private DingTalkClient client(String agentId) {
        return new DingTalkClient("http://127.0.0.1:" + server.getAddress().getPort() + "/", "key", "secret", agentId, JsonMapper.builder().build(),
                Clock.fixed(Instant.parse("2026-11-27T01:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    /** 规则：三个参数都有才算配好；AgentId 不是数字当没配，不让应用起不来 */
    @Test
    void configuredNeedsAllThree() {
        assertThat(client("123").configured()).isTrue();
        assertThat(client("").configured()).isFalse();
        assertThat(client("abc").configured()).isFalse();
    }

    /** 规则：按手机号查到 userid；钉钉里没这个人（60121）返回 null，不当错误；token 取一次缓存着 */
    @Test
    void lookupByMobile_tokenCached() {
        DingTalkClient c = client("123");
        assertThat(c.userIdByMobile("13800000000")).isEqualTo("u1");
        assertThat(c.userIdByMobile("13900000000")).isNull();
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    /** 规则：带链接发卡片（按钮打开链接），带 token 和 agent_id；钉钉报错带上 errcode */
    @Test
    void send_actionCard_andErrors() {
        DingTalkClient c = client("123");
        c.send("u1", "需求订单被驳回", "#### 需求订单 DD1 被驳回", "打开", "https://scm.example.com/mobile-todo/1?type=ORDER");
        assertThat(bodies).singleElement().satisfies(b -> {
            assertThat(b).startsWith("access_token=tk1 ");
            assertThat(b).contains("\"agent_id\":123", "\"userid_list\":\"u1\"", "\"msgtype\":\"action_card\"",
                    "\"single_url\":\"https://scm.example.com/mobile-todo/1?type=ORDER\"");
        });

        c.send("u1", "测试", "正文", "打开", null);
        assertThat(bodies.get(1)).contains("\"msgtype\":\"markdown\"").doesNotContain("action_card");

        sendReply = "{\"errcode\":88,\"errmsg\":\"no permission\"}";
        assertThatThrownBy(() -> c.send("u1", "t", "m", "打开", null))
                .isInstanceOfSatisfying(DingTalkException.class, e -> assertThat(e.errcode()).isEqualTo(88));

        // token 失效：这次报错，下次重新取
        sendReply = "{\"errcode\":42001,\"errmsg\":\"expired\"}";
        assertThatThrownBy(() -> c.send("u1", "t", "m", "打开", null)).isInstanceOf(DingTalkException.class);
        sendReply = "{\"errcode\":0}";
        c.send("u1", "t", "m", "打开", null);
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    /** 规则：连不上是 errcode -1（发件箱按临时问题重试） */
    @Test
    void unreachable_isMinusOne() {
        DingTalkClient c = new DingTalkClient("http://127.0.0.1:1", "key", "secret", "1", JsonMapper.builder().build(), Clock.systemUTC());
        assertThatThrownBy(() -> c.userIdByMobile("13800000000"))
                .isInstanceOfSatisfying(DingTalkException.class, e -> assertThat(e.errcode()).isEqualTo(-1));
    }
}
