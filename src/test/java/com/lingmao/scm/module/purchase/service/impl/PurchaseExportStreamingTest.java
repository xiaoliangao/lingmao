package com.lingmao.scm.module.purchase.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.service.TagFilterService;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseExportStreamingTest {
    @Mock PurchaseOrderMapper mapper;
    @Mock PurchaseOrderItemMapper items;
    @Mock TagFilterService tags;
    @Mock UserService users;
    @Mock MaterialService materials;
    @Mock DictDataService dictionaries;
    @InjectMocks PurchaseOrderServiceImpl service;
    final List<Integer> offsets = new ArrayList<>();
    final AtomicInteger fetched = new AtomicInteger();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "excel", new PurchaseExcel(dictionaries));
        when(mapper.selectExportHeads(any(), any(), any(), anyInt())).thenReturn(List.of(new PurchaseOrderMapper.ExportHead(7L, null)));
        when(items.countExportRows(any(), anyInt())).thenReturn(1200);
        when(mapper.selectRowPage(any(), any(), any(), any())).thenAnswer(invocation -> {
            IPage<PurchaseRowVO> page = invocation.getArgument(0);
            assertThat(page.getSize()).isEqualTo(500);
            var row = new PurchaseRowVO(); row.setId(7L); row.setNo("PO7"); row.setStatus("DRAFT"); row.setPurchaseType("STOCK");
            return page.setRecords(List.of(row));
        });
        when(items.selectExportBatch(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0); int offset = invocation.getArgument(1); int limit = invocation.getArgument(2);
            assertThat(ids).containsExactly(7L); assertThat(limit).isLessThanOrEqualTo(500);
            offsets.add(offset); fetched.incrementAndGet();
            return IntStream.range(offset, Math.min(1200, offset + limit)).mapToObj(i -> {
                var row = new PurchaseOrderItem(); row.setId((long) i + 1); row.setPurchaseOrderId(7L);
                row.setSeq(i + 1); row.setMaterialSkuId(1L); row.setQty(BigDecimal.ONE); return row;
            }).toList();
        });
    }

    @Test void oneLargeParentKeepsAllDetailsAcrossRepeatedBoundedPasses() throws Exception {
        var plan = service.exportPlan(new PurchaseQuery());
        plan.validate(); var bytes = new ByteArrayOutputStream(); plan.writeTo(bytes);
        assertThat(offsets).containsExactly(0, 500, 1000, 0, 500, 1000);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1200);
            assertThat(sheet.getRow(1).getCell(9).getNumericCellValue()).isEqualTo(1);
            assertThat(sheet.getRow(1200).getCell(9).getNumericCellValue()).isEqualTo(1200);
        }
    }

    @Test void interruptedOutputStopsBeforeTheNextChildQuery() {
        var plan = service.exportPlan(new PurchaseQuery());
        var failed = new OutputStream() {
            @Override public void write(int value) throws IOException { check(); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException { check(); }
            private void check() throws IOException { if (fetched.get() > 0) throw new IOException("synthetic disconnect"); }
        };
        assertThatThrownBy(() -> plan.writeTo(failed)).isInstanceOf(IOException.class);
        assertThat(offsets).containsExactly(0);
    }
}
