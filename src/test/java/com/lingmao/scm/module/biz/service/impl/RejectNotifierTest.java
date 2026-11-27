package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocLog;
import com.lingmao.scm.module.biz.mapper.DocLogMapper;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.system.dto.NotifyEvent;
import com.lingmao.scm.module.system.service.NotifyService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单据被驳回 → 钉钉告诉提交的人（RFC 019 §4） */
class RejectNotifierTest {

    private final DocLogMapper docLogMapper = mock(DocLogMapper.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ReferenceService> refs = mock(ObjectProvider.class);
    private final ReferenceService referenceService = mock(ReferenceService.class);
    private final UserService userService = mock(UserService.class);
    private final NotifyService notifyService = mock(NotifyService.class);
    private final RejectNotifier notifier = new RejectNotifier(docLogMapper, refs, userService, notifyService,
            Clock.fixed(Instant.parse("2026-11-27T01:05:00Z"), ZoneId.of("Asia/Shanghai")));

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocLog.class);
    }

    @BeforeEach
    void setUp() {
        when(refs.getObject()).thenReturn(referenceService);
        when(notifyService.enabled(NotifyEvent.DOC_REJECTED)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(9L, "jti"), null, List.of()));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static DocLog submitBy(long userId) {
        DocLog log = new DocLog();
        log.setOperatorId(userId);
        return log;
    }

    /** 规则：发给最近一次提交的人；消息里有单号、驳回的人和时间、原因，点开到手机待办的这张单 */
    @Test
    void notifiesSubmitter() {
        when(docLogMapper.selectOne(any(), anyBoolean())).thenReturn(submitBy(7L));
        when(referenceService.describe(BizType.ORDER, List.of(5L))).thenReturn(Map.of(5L,
                new DocRef(BizType.ORDER, null, null, "需求订单", 5L, "DD2611270001", "DRAFT", "草稿", null, 7L, null)));
        when(userService.nicknames(List.of(9L))).thenReturn(Map.of(9L, "审核人"));

        notifier.rejected(BizType.ORDER, 5L, "reject", "交期太紧", 100L);

        ArgumentCaptor<String> md = ArgumentCaptor.forClass(String.class);
        verify(notifyService).send(eq(NotifyEvent.DOC_REJECTED), eq(7L), eq("ORDER:5:100"), eq("需求订单被驳回"), md.capture(),
                eq("/mobile-todo/5?type=ORDER"));
        assertThat(md.getValue()).contains("需求订单 DD2611270001 被驳回", "驳回：审核人 11-27 09:05", "原因：交期太紧");
    }

    /** 规则：自己驳回自己提交的、找不到提交的人，不发；推送关着连提交记录都不查 */
    @Test
    void skipsSelfAndMissing() {
        when(docLogMapper.selectOne(any(), anyBoolean())).thenReturn(submitBy(9L));
        notifier.rejected(BizType.PURCHASE_ORDER, 5L, "reject", "价格不对", 101L);
        when(docLogMapper.selectOne(any(), anyBoolean())).thenReturn(null);
        notifier.rejected(BizType.PURCHASE_ORDER, 5L, "reject", "价格不对", 102L);
        verify(notifyService, never()).send(any(), anyLong(), anyString(), anyString(), anyString(), anyString());

        when(notifyService.enabled(NotifyEvent.DOC_REJECTED)).thenReturn(false);
        notifier.rejected(BizType.PURCHASE_ORDER, 6L, "reject", "价格不对", 103L);
        verify(docLogMapper, org.mockito.Mockito.times(2)).selectOne(any(), anyBoolean());
    }

    /** 规则：驳回资料改动（change-reject）也发，标题说"改动被驳回"；电脑版地址要的 kind、parentId 带在链接上 */
    @Test
    void changeReject_andLinkCarriesKind() {
        assertThat(RejectNotifier.isReject("change-reject")).isTrue();
        assertThat(RejectNotifier.isReject("withdraw")).isFalse();
        when(docLogMapper.selectOne(any(), anyBoolean())).thenReturn(submitBy(7L));
        when(referenceService.describe(BizType.SUPPLIER, List.of(3L))).thenReturn(Map.of());

        notifier.rejected(BizType.SUPPLIER, 3L, "change-reject", null, 104L);

        ArgumentCaptor<String> md = ArgumentCaptor.forClass(String.class);
        verify(notifyService).send(eq(NotifyEvent.DOC_REJECTED), eq(7L), eq("SUPPLIER:3:104"), anyString(), md.capture(), anyString());
        assertThat(md.getValue()).contains("的改动被驳回", "原因：没写");
        DocRef inv = new DocRef(BizType.INV_DOC, "OUT", null, "出库单", 8L, "CK1", "DRAFT", "草稿", null, null, null);
        assertThat(RejectNotifier.path(BizType.INV_DOC, 8L, inv)).isEqualTo("/mobile-todo/8?type=INV_DOC&kind=OUT");
    }
}
