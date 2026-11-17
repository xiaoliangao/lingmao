package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocGuard;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 单据公共接口：先过单据给的 DocGuard（存在、数据范围），不过就什么都不查、不改 */
@ExtendWith(MockitoExtension.class)
class DocCommonServiceImplTest {

    @Mock
    private DocLogService docLogService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private ReferenceService referenceService;
    @Mock
    private DocNoticeService docNoticeService;

    @InjectMocks
    private DocCommonServiceImpl service;

    /** 不在我数据范围里的单据：和单据自己的详情一样 404 */
    private final DocGuard notMine = id -> {
        throw new BizException(404, "单据不存在");
    };

    /** 规则：看不到的单，看记录、处理提醒都 404，不碰下面的 Service */
    @Test
    void guardFails_404_nothingQueried() {
        assertThatThrownBy(() -> service.logs(BizType.PURCHASE_ORDER, 11L, notMine))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(404);
        assertThatThrownBy(() -> service.handleNotice(BizType.PURCHASE_ORDER, 11L, 99L, "看过了", notMine))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(404);

        verifyNoInteractions(docLogService, attachmentService, referenceService, docNoticeService);
    }

    /** 规则：过了就照常转过去 */
    @Test
    void guardPasses_delegates() {
        service.references(BizType.PURCHASE_ORDER, 11L, id -> { });

        verify(referenceService).referrers(BizType.PURCHASE_ORDER, 11L);
    }
}
