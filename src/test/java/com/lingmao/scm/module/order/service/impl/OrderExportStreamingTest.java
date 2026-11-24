package com.lingmao.scm.module.order.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.service.TagFilterService;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.mapper.OrderItemMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExportStreamingTest {
    @Mock OrderMapper mapper;
    @Mock OrderItemMapper items;
    @Mock TagFilterService tags;
    @Mock UserService users;
    @Mock StyleService styles;
    @Mock DictDataService dictionaries;
    @InjectMocks OrderServiceImpl service;
    final List<Integer> offsets = new ArrayList<>();
    final AtomicInteger fetched = new AtomicInteger();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "excel", new OrderExcel(styles, dictionaries));
        when(mapper.selectExportHeads(any(), any(), any(), anyInt())).thenReturn(List.of(new OrderMapper.ExportHead(7L, null)));
        when(items.countExportStyles(any(), anyInt())).thenReturn(1500);
        when(mapper.selectRowPage(any(), any(), any(), any())).thenAnswer(invocation -> {
            IPage<OrderRowVO> page = invocation.getArgument(0);
            var row = new OrderRowVO(); row.setId(7L); row.setNo("ORD7"); row.setStatus("DRAFT");
            return page.setRecords(List.of(row));
        });
        when(items.selectExportStyles(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int offset = invocation.getArgument(1); int limit = invocation.getArgument(2);
            assertThat(limit).isLessThanOrEqualTo(500); offsets.add(offset); fetched.incrementAndGet();
            return IntStream.range(offset, Math.min(1500, offset + limit))
                    .mapToObj(i -> new OrderItemMapper.OrderStyle(7L, (long) i + 1)).toList();
        });
        when(styles.refs(any())).thenAnswer(invocation -> {
            Set<Long> ids = invocation.getArgument(0);
            return ids.stream().collect(Collectors.toMap(i -> i, i -> new StyleRef(i, "C".repeat(28) + String.format("%04d", i), null)));
        });
    }

    @Test void oversizedAggregateIsRejectedBeforeLoadingRemainingRelations() {
        var plan = service.exportPlan(new OrderQuery());
        assertThatThrownBy(plan::validate).isInstanceOf(BizException.class)
                .hasMessageContaining("需求订单").hasMessageContaining("第 2 行第 6 列（款号）").hasMessageContaining("32767");
        assertThat(offsets).containsExactly(0, 500);
    }

    @Test void heartbeatDetectsFailureBeforeASecondRelationBatchEvenWithoutAnyOutputRow() {
        var plan = service.exportPlan(new OrderQuery());
        var failed = new OutputStream() {
            @Override public void write(int value) throws IOException { check(); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException { check(); }
            private void check() throws IOException { if (fetched.get() > 0) throw new IOException("synthetic disconnect"); }
        };
        assertThatThrownBy(() -> plan.writeTo(failed)).isInstanceOf(IOException.class);
        assertThat(offsets).containsExactly(0);
    }
}
