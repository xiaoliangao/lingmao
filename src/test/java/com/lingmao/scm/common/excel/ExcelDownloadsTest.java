package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExcelDownloadsTest {
    private static ExcelSchema<String> schema() {
        return ExcelSchema.of("导出", () -> "", ExcelColumn.<String>text("值", value -> value, null));
    }
    private static PlatformTransactionManager manager() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); return manager;
    }
    @Test void validationAndAuditFailuresCannotCommitDownloadHeaders() throws Exception {
        var transaction = manager(); var downloads = new ExcelDownloads(transaction, mock(SqlSessionTemplate.class));
        AtomicInteger audits = new AtomicInteger(); var invalid = new MockHttpServletResponse();
        assertThatThrownBy(() -> downloads.write(invalid, () -> "导出.xlsx", () -> Excel.exportBook()
                        .sheet(schema(), sink -> sink.row("x".repeat(32768))), audits::incrementAndGet))
                .isInstanceOf(BizException.class);
        assertThat(audits).hasValue(0); assertThat(invalid.isCommitted()).isFalse();
        assertThat(invalid.getHeader("Content-Disposition")).isNull(); assertThat(invalid.getContentAsByteArray()).isEmpty();
        var failedAudit = new MockHttpServletResponse();
        assertThatThrownBy(() -> downloads.write(failedAudit, () -> "导出.xlsx", () -> Excel.exportBook()
                        .sheet(schema(), sink -> sink.row("validated")), () -> { throw new IllegalStateException("audit unavailable"); }))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(ExcelDownloads.InterruptedDownload.class);
        assertThat(failedAudit.isCommitted()).isFalse(); assertThat(failedAudit.getHeader("Content-Disposition")).isNull();
        assertThat(failedAudit.getContentAsByteArray()).isEmpty(); verify(transaction, times(2)).rollback(any());
    }
    @Test void transactionCompletionFailureCannotAppendJsonToACommittedWorkbook() throws Exception {
        var transaction = manager(); doThrow(new IllegalStateException("completion failed")).when(transaction).commit(any());
        var downloads = new ExcelDownloads(transaction, mock(SqlSessionTemplate.class));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> downloads.write(response, "导出.xlsx", () -> Excel.exportBook().sheet(schema(), sink -> sink.row("value"))))
                .isInstanceOf(ExcelDownloads.InterruptedDownload.class).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(response.isCommitted()).isTrue();
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("value");
        }
    }
}
