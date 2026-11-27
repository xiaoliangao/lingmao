package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.dingtalk.DingTalkClient;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.SysConfigService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 钉钉推送的开关和入队（RFC 019 §4） */
@ExtendWith(MockitoExtension.class)
class NotifyServiceImplTest {

    @Mock
    private OutboxService outbox;
    @Mock
    private SysConfigService config;
    @Mock
    private UserService userService;
    @Mock
    private DingTalkClient client;
    @Mock
    private DingTalkRecipients recipients;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private DeploymentNamespace namespace;

    private final JsonMapper json = JsonMapper.builder().build();
    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotifyServiceImpl(outbox, config, userService, client, recipients, jdbc, namespace, json, "https://scm.example.com/");
        lenient().when(namespace.tenant()).thenReturn("t");
    }

    /** 规则：钉钉没配好什么都不入队（试用期就是这样），也不去读设置 */
    @Test
    void notConfigured_sendsNothing() {
        when(client.configured()).thenReturn(false);
        service.send(NotifyEvent.DOC_REJECTED, 7, "ORDER:1:2", "t", "m", "/mobile-todo/1?type=ORDER");
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), any(), any());
    }

    /** 规则：只发开着的种类；去重键带上种类；没设置时两种都开，设置读不出来也按两种 */
    @Test
    void onlyEnabledEvents_areQueued() {
        when(client.configured()).thenReturn(true);
        when(config.get(NotifyServiceImpl.EVENTS_KEY)).thenReturn("[\"DUE_WARNING\"]");
        service.send(NotifyEvent.DOC_REJECTED, 7, "ORDER:1:2", "t", "m", null);
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), any(), any());

        service.send(NotifyEvent.DUE_WARNING, 7, "7:2026-11-27", "货期预警", "正文", "/mobile-todo?tab=alerts");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outbox).enqueue(eq("DINGTALK_MESSAGE"), eq("DUE_WARNING:7:2026-11-27"), payload.capture(), any(), any());
        assertThat(payload.getValue()).contains("\"userId\":7", "\"path\":\"/mobile-todo?tab=alerts\"", "\"event\":\"DUE_WARNING\"");

        when(config.get(NotifyServiceImpl.EVENTS_KEY)).thenReturn(null);
        assertThat(service.enabled(NotifyEvent.DOC_REJECTED)).isTrue();
        when(config.get(NotifyServiceImpl.EVENTS_KEY)).thenReturn("not json");
        assertThat(service.enabled(NotifyEvent.DOC_REJECTED)).isTrue();
        when(config.get(NotifyServiceImpl.EVENTS_KEY)).thenReturn("[]");
        assertThat(service.enabled(NotifyEvent.DUE_WARNING)).isFalse();
    }

    /** 规则：只有发不出去的（死信、取消）能重发；还在发、发出去了的报错 */
    @Test
    void retry_onlyFailed() {
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of("SUCCEEDED"));
        assertThatThrownBy(() -> service.retry(5)).isInstanceOf(BizException.class);
        verify(outbox, never()).retry(5);

        when(jdbc.queryForList(anyString(), eq(String.class), any(), any(), any())).thenReturn(List.of("DEAD_LETTER"));
        service.retry(5);
        verify(outbox).retry(5);
    }

    /** 规则：测试消息当场发，钉钉没配好、找不到人报一句人话 */
    @Test
    void test_reportsReason() {
        when(client.configured()).thenReturn(false);
        assertThatThrownBy(() -> service.test(1)).isInstanceOf(BizException.class).hasMessageContaining("SCM_DINGTALK_APP_KEY");

        when(client.configured()).thenReturn(true);
        when(recipients.resolve(1)).thenThrow(new DingTalkRecipients.NoRecipient("admin 的账号上没有手机号"));
        assertThatThrownBy(() -> service.test(1)).isInstanceOf(BizException.class).hasMessageContaining("没有手机号");
    }
}
