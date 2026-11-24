package com.lingmao.scm.module.production.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.biz.service.TagFilterService;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Producer tests only: real TCP/PG disconnect and memory evidence belong to the HTTP integration suite. */
@ExtendWith(MockitoExtension.class)
class ProductionExportStreamingTest {
    @Mock ProductionOrderMapper mapper;
    @Mock ProductionSupport support;
    @Mock TagFilterService tags;
    @Mock UserService users;
    @Mock DictDataService dictionaries;
    @InjectMocks ProductionOrderServiceImpl service;
    final List<Long> offsets = new ArrayList<>();
    final AtomicInteger fetched = new AtomicInteger();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "excel", new ProductionExcel(dictionaries));
        when(mapper.selectRowPage(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            IPage<ProductionRowVO> page = invocation.getArgument(0);
            assertThat(page.getSize()).isLessThanOrEqualTo(500);
            assertThat(page.searchCount()).isFalse();
            offsets.add(page.offset()); fetched.incrementAndGet();
            page.setRecords(IntStream.range((int) page.offset(), Math.min(510, (int) (page.offset() + page.getSize())))
                    .mapToObj(i -> { var row = new ProductionRowVO(); row.setId((long) i + 1); row.setNo("P" + i); row.setStatus("DRAFT"); return row; }).toList());
            return page;
        });
    }

    @Test void repeatedPassesRestartTheirPagesAndKeepAllSelectedRows() throws Exception {
        ProductionQuery query = new ProductionQuery(); query.setKind("OUTSOURCE");
        query.setIds(IntStream.rangeClosed(1, 510).mapToObj(i -> (long) i).toList());
        var plan = service.exportPlan(query);
        plan.validate();
        var bytes = new ByteArrayOutputStream(); plan.writeTo(bytes);
        assertThat(offsets).containsExactly(0L, 500L, 0L, 500L);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(510);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("P0");
            assertThat(sheet.getRow(510).getCell(0).getStringCellValue()).isEqualTo("P509");
        }
    }

    @Test void outputFailureNeverFetchesTheSecondMainPage() {
        ProductionQuery query = new ProductionQuery(); query.setKind("OUTSOURCE");
        var plan = service.exportPlan(query);
        OutputStream failed = new OutputStream() {
            @Override public void write(int value) throws IOException { check(); }
            @Override public void write(byte[] bytes, int off, int len) throws IOException { check(); }
            private void check() throws IOException { if (fetched.get() > 0) throw new IOException("synthetic disconnect"); }
        };
        assertThatThrownBy(() -> plan.writeTo(failed)).isInstanceOf(IOException.class);
        assertThat(offsets).containsExactly(0L);
    }
}
