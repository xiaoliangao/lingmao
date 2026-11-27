package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.dto.NotifyMessageQuery;
import com.lingmao.scm.module.system.dto.NotifyMessageRow;
import com.lingmao.scm.module.system.dto.NotifySettingsVO;

import java.util.List;

/**
 * 钉钉推送（甲方 11-27 答 Q65，RFC 019 §4，架构 §14.3）。
 * <p>
 * 业务在自己的事务里调 {@link #send}：只往 sys_outbox 插一行（同一个事务，业务回滚了消息也没有），
 * 后台任务（DingTalkMessageHandler）再去发，失败按发件箱的规则重试。钉钉没配好、这种消息没开时什么都不插。
 */
public interface NotifyService {

    /** 这种消息现在发不发：钉钉配好了、而且管理员没关掉这种 */
    boolean enabled(NotifyEvent event);

    /**
     * 给一个人发一条。同一个 dedupeKey 只发一次（重试、重复调用都不会多发）。
     *
     * @param markdown 正文（钉钉的 markdown，几行就行）
     * @param path     点"打开"去系统的哪一页，如 /mobile-todo/12?type=ORDER；没配外网地址时不带按钮
     */
    void send(NotifyEvent event, long userId, String dedupeKey, String title, String markdown, String path);

    NotifySettingsVO settings();

    NotifySettingsVO setEvents(List<NotifyEvent> events);

    PageResult<NotifyMessageRow> messages(NotifyMessageQuery query);

    /** 发失败的那条重发（重试次数清零） */
    void retry(long id);

    /** 给自己发一条测试消息，当场发、不进发件箱：配好以后试一下通不通。不通的报钉钉的原话；返回"已发到 xx 的钉钉" */
    String test(long userId);
}
