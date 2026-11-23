package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.biz.service.TagService;
import com.lingmao.scm.module.biz.service.impl.TagAuditTargetGuard;
import com.lingmao.scm.module.system.dto.DataLogView;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 仅验证分派次数及损坏结果的负控；真实数据库/HTTP 证据在 S4TagAuditHttpIT。 */
class S4TagAuditDispatchTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TagService tags = mock(TagService.class);
    private final DeploymentNamespace namespace = mock(DeploymentNamespace.class);
    private final AuditTargetGuard fg = mock(AuditTargetGuard.class);
    private final AuditTargetGuard shared = mock(AuditTargetGuard.class);
    private final AuditTargetGuard sample = mock(AuditTargetGuard.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final TagAuditTargetGuard tagGuard = new TagAuditTargetGuard(tags, mock(TagRepository.class), jdbc, namespace, json);

    @Test void aHundredEventsForOneRootAuthorizeTheProviderOnlyOnce() {
        DataLogServiceImpl service = service();
        rows(IntStream.range(0, 100).mapToObj(n -> row(n + 1, "fg_doc", 42)).toList());
        assertThat(service.list(null, null, "fg_doc", 42L, null, null, 100)).hasSize(100);
        verify(tags, times(1)).checkReadable(TagObjectType.FG_DOC, 42);
    }

    @Test void directAssociationPrecheckIsNotRepeatedForEveryHistoricalRow() {
        DataLogServiceImpl service = service();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("FROM biz_tag_link l")) return List.of(new AuditTargetGuard.Root("fg_doc", 42));
            return IntStream.range(0, 100).mapToObj(n -> row(n + 1, "fg_doc", 42)).toList();
        }).when(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        assertThat(service.list("biz_tag_link", 9L, null, null, null, null, 100)).hasSize(100);
        verify(tags, times(1)).checkReadable(TagObjectType.FG_DOC, 42);
    }

    @Test void anImpossibleMixedRootResultFailsClosedBeforeAnyAccessRecord() {
        DataLogServiceImpl service = service();
        rows(List.of(row(1, "fg_doc", 42), row(2, "fg_doc", 43)));
        assertThatThrownBy(() -> service.list(null, null, "fg_doc", 42L, null, null, 100))
                .isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(tags, never()).checkReadable(any(), anyLong());
    }

    @Test void anEmptyCursorCannotAvoidTheParentPermissionCheck() {
        DataLogServiceImpl service = service();
        doThrow(new BizException(403, "synthetic missing domain read")).when(tags).checkReadable(TagObjectType.ORDER, 42);
        assertThatThrownBy(() -> service.list(null, null, "ord_order", 42L, null, 1L, 100))
                .isInstanceOf(BizException.class).extracting(error -> ((BizException) error).getCode()).isEqualTo(403);
        verify(jdbc, never()).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
    }

    @Test void missingFgIsPassedToTheActualRegisteredHistoricalGuardAndItsDenialIsFinal() {
        DataLogServiceImpl service = service();
        rows(List.of(row(1, "fg_doc", 42)));
        doThrow(new BizException(404, "synthetic absent FG")).when(tags).checkReadable(TagObjectType.FG_DOC, 42);
        doThrow(new BizException(404, "synthetic missing DELETE proof")).when(fg).checkHistoricalRoot("fg_doc", 42);
        assertThatThrownBy(() -> service.list(null, null, "fg_doc", 42L, null, null, 100))
                .isInstanceOf(BizException.class).extracting(error -> ((BizException) error).getCode()).isEqualTo(404);
        verify(fg, times(1)).checkHistoricalRoot("fg_doc", 42);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private DataLogServiceImpl service() {
        when(namespace.tenant()).thenReturn("audit-dispatch-unit");
        when(fg.tables()).thenReturn(Set.of("fg_doc", "fg_doc_item", "fg_stock"));
        when(shared.tables()).thenReturn(Set.of("bas_company", "bas_warehouse", "bas_customer", "inv_stock", "prc_price"));
        when(sample.tables()).thenReturn(Set.of("dev_sample", "dev_sample_order", "dev_sample_node", "dev_sample_review",
                "dev_sample_color", "dev_sample_order_color", "dev_sample_order_size", "dev_sample_reviewer"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        return new DataLogServiceImpl(jdbc, namespace, json, List.of(fg, tagGuard, shared, sample));
    }

    private void rows(List<DataLogView> values) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<DataLogView>>any(), any(Object[].class))).thenReturn(values);
    }

    private DataLogView row(int id, String root, long rootId) {
        return new DataLogView(Integer.toString(id), "biz_tag_link", "9", null, root, Long.toString(rootId), "UPDATE", null,
                json.createObjectNode(), "1", null, "unit-dispatch", null, "1", OffsetDateTime.parse("2026-11-22T00:00:00Z"));
    }
}
