package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.dingtalk.DingTalkClient;
import com.lingmao.scm.common.dingtalk.DingTalkClient.DingTalkException;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.jobs.OutboxWork;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 发件箱里的钉钉消息怎么发、失败了重不重试（RFC 019 §4） */
class DingTalkMessageHandlerTest {

    private final DingTalkClient client = mock(DingTalkClient.class);
    private final DingTalkRecipients recipients = mock(DingTalkRecipients.class);
    private final OutboxWork work = mock(OutboxWork.class);
    private final DingTalkMessageHandler handler = new DingTalkMessageHandler(client, recipients, JsonMapper.builder().build(),
            "https://scm.example.com/");
    private static final String PAYLOAD = """
            {"event":"DOC_REJECTED","userId":7,"title":"需求订单被驳回","markdown":"正文","path":"/mobile-todo/1?type=ORDER"}""";

    /** 规则：找到人就发，链接 = 外网地址 + 页面路径 */
    @Test
    void sends_withLink() {
        when(client.configured()).thenReturn(true);
        when(recipients.resolve(7)).thenReturn("u7");
        handler.execute(work, PAYLOAD);
        verify(client).send("u7", "需求订单被驳回", "正文", "打开", "https://scm.example.com/mobile-todo/1?type=ORDER");
        assertThat(DingTalkMessageHandler.link("", "/x")).isNull();
    }

    /** 规则：没配置、找不到人不重试（直接进死信，管理员看原因）；网络问题、钉钉报错按发件箱的规则重试 */
    @Test
    void failures_mapToCodes() {
        when(client.configured()).thenReturn(false);
        assertFailure(JobFailure.Code.NOT_CONFIGURED, true);

        when(client.configured()).thenReturn(true);
        when(recipients.resolve(7)).thenThrow(new DingTalkRecipients.NoRecipient("没手机号"));
        assertFailure(JobFailure.Code.NO_RECIPIENT, true);

        doReturn("u7").when(recipients).resolve(7);
        doThrow(new DingTalkException(-1, "连不上钉钉")).when(client).send("u7", "需求订单被驳回", "正文", "打开",
                "https://scm.example.com/mobile-todo/1?type=ORDER");
        assertFailure(JobFailure.Code.TEMPORARY_FAILURE, false);

        doThrow(new DingTalkException(88, "no permission")).when(client).send("u7", "需求订单被驳回", "正文", "打开",
                "https://scm.example.com/mobile-todo/1?type=ORDER");
        assertFailure(JobFailure.Code.REMOTE_REJECTED, false);
    }

    /** 规则：手机号去掉空格、横线和 +86；不是 11 位手机号的当没有 */
    @Test
    void mobile_normalized() {
        assertThat(DingTalkRecipients.mobile("+86 138-0000-0000")).isEqualTo("13800000000");
        assertThat(DingTalkRecipients.mobile("13800000000")).isEqualTo("13800000000");
        assertThat(DingTalkRecipients.mobile("0571-88888888")).isNull();
        assertThat(DingTalkRecipients.mobile(null)).isNull();
    }

    private void assertFailure(JobFailure.Code code, boolean permanent) {
        assertThatThrownBy(() -> handler.execute(work, PAYLOAD)).isInstanceOfSatisfying(JobFailure.class, f -> {
            assertThat(f.code()).isEqualTo(code);
            assertThat(f.permanent()).isEqualTo(permanent);
        });
    }
}
