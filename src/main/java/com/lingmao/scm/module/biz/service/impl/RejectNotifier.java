package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocLog;
import com.lingmao.scm.module.biz.mapper.DocLogMapper;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.service.NotifyService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 单据被驳回 → 钉钉告诉提交的人（甲方 11-27 答 Q65，RFC 019 §4）。在写操作记录的地方调（DocLogServiceImpl），
 * 所以所有单据的驳回都走到这里：需求订单、变更单、采购单、生产单、盘点单、供应商准入（reject）和供应商资料改动（change-reject）。
 * <p>
 * 发给谁：reject 是这张单最近一次"提交"的人，change-reject 是最近一次提改动的人；没有、或者就是驳回的人自己，不发。
 * 和驳回在同一个事务里入队（驳回回滚了消息也没有）
 */
@Component
@RequiredArgsConstructor
class RejectNotifier {

    static final String CHANGE_REJECT = "change-reject";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final DocLogMapper docLogMapper;
    // ReferenceService 问各域要单号，各域又用操作记录：用到时再取，避免启动时绕成圈
    private final ObjectProvider<ReferenceService> referenceService;
    private final UserService userService;
    private final NotifyService notifyService;
    private final Clock clock;

    static boolean isReject(String action) {
        return "reject".equals(action) || CHANGE_REJECT.equals(action);
    }

    /** logId：这条驳回记录的 id，去重用（同一张单驳回两次是两条消息） */
    void rejected(BizType type, long docId, String action, String reason, Long logId) {
        if (!notifyService.enabled(NotifyEvent.DOC_REJECTED)) {
            return;
        }
        boolean change = CHANGE_REJECT.equals(action);
        DocLog submit = docLogMapper.selectOne(new LambdaQueryWrapper<DocLog>()
                .eq(DocLog::getDocType, type).eq(DocLog::getDocId, docId)
                .in(DocLog::getAction, change ? List.of("change") : List.of("submit"))
                .orderByDesc(DocLog::getId).last("LIMIT 1"), false);
        Long to = submit == null ? null : submit.getOperatorId();
        Long me = LoginUser.currentUserId();
        if (to == null || to.equals(me)) {
            return;
        }
        DocRef ref = referenceService.getObject().describe(type, List.of(docId)).get(docId);
        String label = ref != null && ref.typeLabel() != null ? ref.typeLabel() : type.label(null);
        String no = ref != null && ref.no() != null ? " " + ref.no() : "";
        String who = me == null ? null : userService.nicknames(List.of(me)).get(me);
        StringBuilder md = new StringBuilder("#### ").append(label).append(no).append(change ? " 的改动被驳回" : " 被驳回").append("\n\n");
        // 带上时间：钉钉同一天给同一个人的内容一样的消息只送一条，同一张单一天驳回两次也要都收到
        md.append("驳回：").append(who == null ? "" : who + " ").append(TIME.format(LocalDateTime.now(clock))).append("\n\n");
        md.append("原因：").append(reason == null || reason.isBlank() ? "没写" : reason.strip()).append("\n\n改好了重新提交。");
        notifyService.send(NotifyEvent.DOC_REJECTED, to, type.name() + ":" + docId + ":" + logId, label + "被驳回", md.toString(),
                path(type, docId, ref));
    }

    /** 手机待办的单据页：手机上看不了的种类，页面按 kind、parentId 拼电脑版的地址 */
    static String path(BizType type, long docId, DocRef ref) {
        StringBuilder p = new StringBuilder("/mobile-todo/").append(docId).append("?type=").append(type.name());
        if (ref != null && ref.kind() != null) {
            p.append("&kind=").append(ref.kind());
        }
        if (ref != null && ref.parentId() != null) {
            p.append("&parentId=").append(ref.parentId());
        }
        return p.toString();
    }
}
