package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.dingtalk.DingTalkClient;
import com.lingmao.scm.common.dingtalk.DingTalkClient.DingTalkException;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.TraceIdFilter;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.dto.NotifyMessageQuery;
import com.lingmao.scm.module.system.dto.NotifyMessageRow;
import com.lingmao.scm.module.system.dto.NotifySettingsVO;
import com.lingmao.scm.module.system.service.NotifyService;
import com.lingmao.scm.module.system.service.OutboxService;
import com.lingmao.scm.module.system.service.SysConfigService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 钉钉推送（RFC 019 §4）：入队、设置、消息记录。发是 DingTalkMessageHandler 的事 */
@Service
public class NotifyServiceImpl implements NotifyService {

    static final String KIND = "DINGTALK_MESSAGE";
    /** sys_config 里推哪几种：NotifyEvent 名字的 JSON 数组；没有这一行按甲方选的两种 */
    static final String EVENTS_KEY = "notify.events";
    private static final Set<NotifyEvent> DEFAULT_EVENTS = EnumSet.of(NotifyEvent.DOC_REJECTED, NotifyEvent.DUE_WARNING);

    /** 发件箱里存的：发给谁、说什么、点了去哪 */
    record Payload(String event, long userId, String title, String markdown, String path) {
    }

    private final OutboxService outbox;
    private final SysConfigService config;
    private final UserService userService;
    private final DingTalkClient client;
    private final DingTalkRecipients recipients;
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final JsonMapper json;
    private final String publicUrl;

    public NotifyServiceImpl(OutboxService outbox, SysConfigService config, UserService userService, DingTalkClient client,
                             DingTalkRecipients recipients, JdbcTemplate jdbc, DeploymentNamespace namespace, JsonMapper json,
                             @Value("${scm.public-url:}") String publicUrl) {
        this.outbox = outbox;
        this.config = config;
        this.userService = userService;
        this.client = client;
        this.recipients = recipients;
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.json = json;
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
    }

    @Override
    public boolean enabled(NotifyEvent event) {
        return client.configured() && events().contains(event);
    }

    @Override
    public void send(NotifyEvent event, long userId, String dedupeKey, String title, String markdown, String path) {
        if (!enabled(event)) {
            return;
        }
        String key = event.name() + ":" + dedupeKey;
        outbox.enqueue(KIND, key.length() > 200 ? key.substring(0, 200) : key,
                json.writeValueAsString(new Payload(event.name(), userId, title, markdown, path)),
                LoginUser.currentUserId(), MDC.get(TraceIdFilter.MDC_KEY));
    }

    @Override
    public NotifySettingsVO settings() {
        Set<NotifyEvent> on = events();
        List<NotifySettingsVO.EventOption> options = Arrays.stream(NotifyEvent.values())
                .map(e -> new NotifySettingsVO.EventOption(e.name(), e.label(), e.description(), on.contains(e)))
                .toList();
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE tenant_key = ? AND kind = ? AND status = 'DEAD_LETTER'",
                Long.class, namespace.tenant(), KIND);
        return new NotifySettingsVO(client.configured(), !publicUrl.isEmpty(), options, failed == null ? 0 : failed);
    }

    @Override
    public NotifySettingsVO setEvents(List<NotifyEvent> events) {
        List<String> names = events.stream().filter(Objects::nonNull).distinct().map(Enum::name).toList();
        config.put(EVENTS_KEY, json.writeValueAsString(names));
        return settings();
    }

    /** 现在开着的几种；设置读不出来按默认 */
    Set<NotifyEvent> events() {
        String raw = config.get(EVENTS_KEY);
        if (raw == null) {
            return DEFAULT_EVENTS;
        }
        try {
            Set<NotifyEvent> out = EnumSet.noneOf(NotifyEvent.class);
            for (String name : json.readValue(raw, String[].class)) {
                Arrays.stream(NotifyEvent.values()).filter(e -> e.name().equals(name)).findFirst().ifPresent(out::add);
            }
            return out;
        } catch (RuntimeException e) {
            return DEFAULT_EVENTS;
        }
    }

    @Override
    public PageResult<NotifyMessageRow> messages(NotifyMessageQuery query) {
        boolean failed = Boolean.TRUE.equals(query.getFailed());
        String where = " FROM sys_outbox WHERE tenant_key = ? AND kind = ?" + (failed ? " AND status = 'DEAD_LETTER'" : "");
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, namespace.tenant(), KIND);
        record Raw(long id, String event, Long userId, String title, String status, String errorCode, int attempts,
                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        }
        List<Raw> raws = jdbc.query("""
                SELECT id, payload->>'event', (payload->>'userId')::bigint, payload->>'title', status, error_code, attempts,
                       created_at, updated_at""" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new Raw(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getInt(7), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, OffsetDateTime.class)),
                namespace.tenant(), KIND, query.getSize(), (query.getPage() - 1) * query.getSize());
        Map<Long, String> names = userService.nicknames(raws.stream().map(Raw::userId).filter(Objects::nonNull).distinct().toList());
        List<NotifyMessageRow> rows = new ArrayList<>();
        for (Raw r : raws) {
            String label = Arrays.stream(NotifyEvent.values()).filter(e -> e.name().equals(r.event())).map(NotifyEvent::label)
                    .findFirst().orElse(r.event());
            rows.add(new NotifyMessageRow(r.id(), r.event(), label, r.userId(), r.userId() == null ? null : names.get(r.userId()),
                    r.title(), r.status(), r.errorCode(), r.attempts(), r.createdAt(), r.updatedAt()));
        }
        return new PageResult<>(rows, total == null ? 0 : total);
    }

    @Override
    public void retry(long id) {
        List<String> status = jdbc.queryForList("SELECT status FROM sys_outbox WHERE tenant_key = ? AND kind = ? AND id = ?",
                String.class, namespace.tenant(), KIND, id);
        if (status.isEmpty()) {
            throw new BizException(404, "没有这条消息");
        }
        if (!List.of("DEAD_LETTER", "CANCELLED").contains(status.getFirst())) {
            throw new BizException("这条还在发或者已经发出去了，不用重发");
        }
        outbox.retry(id);
    }

    @Override
    public String test(long userId) {
        if (!client.configured()) {
            throw new BizException("钉钉还没配置：部署配置里要填 SCM_DINGTALK_APP_KEY、SCM_DINGTALK_APP_SECRET、SCM_DINGTALK_AGENT_ID，见部署手册");
        }
        try {
            String to = recipients.resolve(userId);
            client.send(to, "测试消息", "#### 测试消息\n\n钉钉推送通了：单据被驳回、货期预警会这样发到钉钉。", "打开系统",
                    DingTalkMessageHandler.link(publicUrl, "/mobile-todo"));
        } catch (DingTalkRecipients.NoRecipient | DingTalkException e) {
            throw new BizException(e.getMessage());
        }
        String name = userService.nicknames(List.of(userId)).get(userId);
        return "已发到 " + (name == null ? "你" : name) + " 的钉钉";
    }
}
