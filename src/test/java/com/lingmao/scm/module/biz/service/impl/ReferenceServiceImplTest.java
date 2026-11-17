package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/** 引用查询（架构 §4.4）：各域的 ReferrerProvider 收齐，有下游就 1005 带清单；状态由下游的域翻好，负责人昵称这里统一填 */
@ExtendWith(MockitoExtension.class)
class ReferenceServiceImplTest {

    /** 采购单的叫法：RUNNING 叫"部分到货" */
    private static final StatusLabels<DocStatus> PO_STATUS = StatusLabels.DOC.rename(DocStatus.RUNNING, "部分到货");

    @Mock
    private ObjectProvider<ReferrerProvider> providers;
    @Mock
    private UserService userService;

    private ReferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReferenceServiceImpl(providers, userService);
    }

    /** 规则：哪个域报了下游就拦住，1005 的 data 是清单，状态翻成那种单据的叫法，带负责人昵称；没报的域不影响 */
    @Test
    void assertNoReferrer_hasDownstream_1005WithRefs() {
        ReferrerProvider purchase = (type, id) -> type == BizType.MRP_CALC && id == 3L
                ? List.of(DocRef.of(BizType.PURCHASE_ORDER, 11L, "CG202611170001", DocStatus.RUNNING, PO_STATUS, 7L))
                : List.of();
        ReferrerProvider production = (type, id) -> List.of();
        when(providers.orderedStream()).thenAnswer(inv -> Stream.of(production, purchase));
        when(userService.nicknames(anyCollection())).thenReturn(Map.of(7L, "采购小王"));

        assertThatThrownBy(() -> service.assertNoReferrer(BizType.MRP_CALC, 3L, "反审核"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能反审核")
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getCode()).isEqualTo(1005);
                    @SuppressWarnings("unchecked")
                    List<DocRef> refs = (List<DocRef>) biz.getData();
                    assertThat(refs).singleElement().satisfies(r -> {
                        assertThat(r.typeLabel()).isEqualTo("采购单");
                        assertThat(r.statusLabel()).isEqualTo("部分到货");
                        assertThat(r.statusTone()).isEqualTo(StatusTone.PROGRESS);
                        assertThat(r.ownerName()).isEqualTo("采购小王");
                    });
                });
    }

    /** 规则：没有下游就放行；一张表两种单据的 typeLabel 按 kind 叫 */
    @Test
    void assertNoReferrer_none_passes_kindLabel() {
        when(providers.orderedStream()).thenAnswer(inv -> Stream.of((ReferrerProvider) (type, id) -> List.of()));

        assertThatCode(() -> service.assertNoReferrer(BizType.ORDER, 1L, "取消")).doesNotThrowAnyException();
        assertThat(DocRef.of(BizType.PRODUCTION_ORDER, 1L, "JG1", DocStatus.APPROVED, StatusLabels.DOC, null)
                .withKind("OUTSOURCE").typeLabel()).isEqualTo("外发加工单");
    }

    /** 规则：describe 问单据所在的域；那个域还没实现的给一个只有类型和 id 的，不报错 */
    @Test
    void describe_missingProvider_placeholder() {
        ReferrerProvider change = new ReferrerProvider() {
            @Override
            public List<DocRef> referrers(BizType type, long id) {
                return List.of();
            }

            @Override
            public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
                return type == BizType.ORDER_CHANGE && ids.contains(5L)
                        ? Map.of(5L, DocRef.of(BizType.ORDER_CHANGE, 5L, "BG202611170001", DocStatus.APPROVED, StatusLabels.DOC, null))
                        : Map.of();
            }
        };
        when(providers.orderedStream()).thenAnswer(inv -> Stream.of(change));

        Map<Long, DocRef> refs = service.describe(BizType.ORDER_CHANGE, List.of(5L, 6L));

        assertThat(refs.get(5L).no()).isEqualTo("BG202611170001");
        assertThat(refs.get(6L).no()).isEmpty();
        assertThat(refs.get(6L).typeLabel()).isEqualTo("订单变更单");
    }
}
