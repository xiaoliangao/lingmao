package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.BoundaryViolation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 同步请求线程完成预检和写流，保留 SecurityContext/CompanyContext；不排队占用连接。 */
@Component
public final class ExcelDownloads {
    private static final int TIMEOUT_SECONDS = 120;
    private final Semaphore permits = new Semaphore(2);
    private final TransactionTemplate snapshot;
    private final org.mybatis.spring.SqlSessionTemplate sessions;

    public ExcelDownloads(PlatformTransactionManager transactions, org.mybatis.spring.SqlSessionTemplate sessions) {
        this.sessions = sessions;
        snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setTimeout(TIMEOUT_SECONDS);
    }

    public void write(HttpServletResponse response, String filename, Supplier<ExcelExport> planFactory) throws IOException {
        write(response, () -> filename, planFactory);
    }

    /** 单据文件名也在获得并发许可后的同一快照读取。 */
    public void write(HttpServletResponse response, Supplier<String> filename, Supplier<ExcelExport> planFactory) throws IOException {
        write(response, filename, planFactory, () -> { });
    }

    /** 生成准备完成后的审计须由调用方在独立写事务提交；失败时尚未发送下载头。 */
    public void write(HttpServletResponse response, Supplier<String> filename, Supplier<ExcelExport> planFactory,
                      Runnable beforeResponse) throws IOException {
        if (!permits.tryAcquire()) throw new BoundaryViolation(429, "同时导出的文件过多，请稍后重试");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        StreamingExcel.Checkpoint checkpoint = new StreamingExcel.Checkpoint() {
            public void run() {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new BizException("导出超过时间预算，请缩小筛选范围再导出");
            }
            public void flush() {
                run();
                // MyBatis SESSION 一级缓存否则会在长快照内保留每个分页结果及其参数。
                sessions.clearCache();
            }
        };
        try {
            snapshot.executeWithoutResult(status -> {
                ExcelExport plan = planFactory.get();
                try {
                    plan.validate(checkpoint);
                } catch (IOException e) { throw new UncheckedIOException(e); }
                checkpoint.run();
                String name = filename.get();
                beforeResponse.run();
                checkpoint.run();
                response.setContentType(Excel.CONTENT_TYPE);
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Accel-Buffering", "no");
                response.setHeader("Content-Disposition", ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString());
                try {
                    response.flushBuffer();
                    plan.writeTo(response.getOutputStream(), checkpoint);
                    response.flushBuffer();
                } catch (IOException | RuntimeException e) {
                    status.setRollbackOnly();
                    // 不能把 JSON 错误追加在已经提交的 XLSX 后，更不能 finish 为有效的残缺工作簿。
                    throw new InterruptedDownload(e);
                }
            });
        } catch (UncheckedIOException e) {
            if (response.isCommitted()) throw new InterruptedDownload(e);
            throw e.getCause();
        } catch (RuntimeException e) {
            // 事务提交/清理发生在 execute 回调返回后，同样不能再向已提交的 XLSX 写 JSON。
            if (response.isCommitted() && !(e instanceof InterruptedDownload)) throw new InterruptedDownload(e);
            throw e;
        } finally { permits.release(); }
    }

    /** 已开始响应的失败由专门异常处理器结束，不重试数据库查询。 */
    public static final class InterruptedDownload extends RuntimeException {
        InterruptedDownload(Throwable cause) { super("Excel download interrupted", cause); }
    }
}
