package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.dingtalk.DingTalkClient;
import com.lingmao.scm.common.dingtalk.DingTalkClient.DingTalkException;
import com.lingmao.scm.common.jobs.JobFailure;
import com.lingmao.scm.common.jobs.OutboxHandler;
import com.lingmao.scm.common.jobs.OutboxWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 发件箱里的钉钉消息（kind = DINGTALK_MESSAGE，RFC 019 §4）：找到收件人的钉钉 userid，发一条工作通知。
 * <ul>
 *   <li>钉钉没配好、收件人找不到：不重试，直接进死信（管理后台能看到原因、能重发）</li>
 *   <li>网络不通、钉钉报错：按发件箱的规则重试（30 秒起翻倍，5 次后进死信）；钉钉的 errcode 写进日志</li>
 * </ul>
 */
@Component
public class DingTalkMessageHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(DingTalkMessageHandler.class);

    private final DingTalkClient client;
    private final DingTalkRecipients recipients;
    private final JsonMapper json;
    private final String publicUrl;

    public DingTalkMessageHandler(DingTalkClient client, DingTalkRecipients recipients, JsonMapper json,
                                  @Value("${scm.public-url:}") String publicUrl) {
        this.client = client;
        this.recipients = recipients;
        this.json = json;
        this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
    }

    @Override
    public String kind() {
        return NotifyServiceImpl.KIND;
    }

    @Override
    public void execute(OutboxWork work, String payload) {
        if (!client.configured()) {
            throw new JobFailure(JobFailure.Code.NOT_CONFIGURED, true);
        }
        NotifyServiceImpl.Payload p = json.readValue(payload, NotifyServiceImpl.Payload.class);
        try {
            String to = recipients.resolve(p.userId());
            work.checkpoint();
            client.send(to, p.title(), p.markdown(), "打开", link(publicUrl, p.path()));
        } catch (DingTalkRecipients.NoRecipient e) {
            log.warn("dingtalk_no_recipient outbox={} user={} reason={}", work.id(), p.userId(), e.getMessage());
            throw new JobFailure(JobFailure.Code.NO_RECIPIENT, true);
        } catch (DingTalkException e) {
            log.warn("dingtalk_failed outbox={} user={} {}", work.id(), p.userId(), e.getMessage());
            throw new JobFailure(e.errcode() == -1 ? JobFailure.Code.TEMPORARY_FAILURE : JobFailure.Code.REMOTE_REJECTED, false);
        }
    }

    /** 外网地址 + 页面路径；没配外网地址返回 null（消息不带按钮） */
    static String link(String publicUrl, String path) {
        if (publicUrl.isEmpty() || path == null) {
            return null;
        }
        return publicUrl + (path.startsWith("/") ? path : "/" + path);
    }
}
