package com.lingmao.scm.module.biz.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.DocNoticeVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocNotice;
import com.lingmao.scm.module.biz.mapper.DocNoticeMapper;
import com.lingmao.scm.module.biz.repository.NoticeSummaryMaintenance;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 上游变更提醒（架构 §4.4）：处理只成功一次、不能借别的单据处理；没处理的排前面；太长的截断 */
@ExtendWith(MockitoExtension.class)
class DocNoticeServiceImplTest {

    @Mock
    private DocNoticeMapper noticeMapper;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private UserService userService;
    @Mock
    private NoticeSummaryMaintenance summaryMaintenance;

    private DocNoticeServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DocNotice.class);
    }

    @BeforeEach
    void setUp() {
        service = new DocNoticeServiceImpl(noticeMapper, referenceService, userService,
                Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai")), summaryMaintenance);
    }

    /** 规则：已经被别人处理了（条件更新改 0 行，提醒是这张单的）→ 1002 */
    @Test
    void handle_alreadyHandled_1002() {
        when(noticeMapper.update(any(Wrapper.class))).thenReturn(0);
        when(noticeMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> service.handle(BizType.PURCHASE_ORDER, 11L, 99L, "改了数量"))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(1002);
    }

    /** 规则：提醒不是路径上这张单的（拿有权限的单据 id 去处理别的单据的提醒）→ 404 */
    @Test
    void handle_noticeOfAnotherDoc_404() {
        when(noticeMapper.update(any(Wrapper.class))).thenReturn(0);
        when(noticeMapper.exists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.handle(BizType.PURCHASE_ORDER, 11L, 99L, null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(404);
    }

    /** 规则：没处理的在前、各自新的在前；处理人翻成昵称 */
    @Test
    void list_pendingFirst() {
        DocNotice handled = notice(3L, 8L);
        DocNotice oldPending = notice(1L, null);
        DocNotice newPending = notice(2L, null);
        when(noticeMapper.selectList(any())).thenReturn(List.of(handled, oldPending, newPending));
        when(referenceService.describe(eq(BizType.ORDER_CHANGE), anyCollection())).thenReturn(Map.of());
        when(userService.nicknames(anyCollection())).thenReturn(Map.of(8L, "跟单小李"));

        List<DocNoticeVO> list = service.list(BizType.PRODUCTION_ORDER, 21L);

        assertThat(list).extracting(DocNoticeVO::id).containsExactly(2L, 1L, 3L);
        assertThat(list.get(2).handledByName()).isEqualTo("跟单小李");
    }

    /** 规则：内容超过 500 字截断（列就这么长），不让整个变更单审核失败 */
    @Test
    void add_longContent_truncated() {
        service.add(BizType.PURCHASE_ORDER, 11L, BizType.ORDER_CHANGE, 5L, "改".repeat(600), 7L);

        ArgumentCaptor<DocNotice> saved = ArgumentCaptor.captor();
        verify(noticeMapper).insert(saved.capture());
        assertThat(saved.getValue().getContent()).hasSize(500).endsWith("…");
        assertThat(saved.getValue().getOwnerId()).isEqualTo(7L);
    }

    private static DocNotice notice(long id, Long handledBy) {
        DocNotice n = new DocNotice();
        n.setId(id);
        n.setDocType(BizType.PRODUCTION_ORDER);
        n.setDocId(21L);
        n.setSourceType(BizType.ORDER_CHANGE);
        n.setSourceId(5L);
        n.setContent("数量从 100 改成 80");
        n.setHandledBy(handledBy);
        n.setHandledTime(handledBy == null ? null : OffsetDateTime.parse("2026-11-17T10:00:00+08:00"));
        return n;
    }
}
