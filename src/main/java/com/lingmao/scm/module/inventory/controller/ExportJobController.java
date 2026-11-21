package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.inventory.dto.ExportJobView;
import com.lingmao.scm.module.inventory.dto.StockLogExportReq;
import com.lingmao.scm.module.inventory.service.ExportJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 库存日志的后台导出（docs/api/04-库存.md §3.6.5）：行多，先建任务、后台生成文件，查到做完了再下载 */
@Tag(name = "后台导出")
@RestController
@RequestMapping("/api/export-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:stock:export')")
public class ExportJobController extends IdempotencyReplayGuard {
    private final ExportJobService jobs;

    @Operation(summary = "建库存日志的导出任务：请求体是日志的筛选条件，要带 Idempotency-Key；每人同时最多 2 个没做完的，多了回 429")
    @PostMapping("/stock-logs") @ResponseStatus(HttpStatus.ACCEPTED) @IdempotentCreate("export-stock-logs")
    public R<ExportJobView> create(@Valid @RequestBody StockLogExportReq filters) {
        return R.ok(jobs.create(filters, org.slf4j.MDC.get("traceId")));
    }
    @Operation(summary = "导出任务的状态：排队、生成中、做完了、失败、取消")
    @GetMapping("/{id}") public R<ExportJobView> get(@PathVariable long id) { return R.ok(jobs.get(id)); }
    @Operation(summary = "取消还没做完的导出任务")
    @PostMapping("/{id}/cancel") @IdempotentCreate("cancel-export-job")
    public R<ExportJobView> cancel(@PathVariable long id) { return R.ok(jobs.cancel(id)); }
    @Operation(summary = "失败或取消的导出任务重新做")
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED) @IdempotentCreate("retry-export-job")
    public R<ExportJobView> retry(@PathVariable long id) { return R.ok(jobs.retry(id)); }
    @Operation(summary = "下载做完的导出文件（.xlsx）")
    @GetMapping("/{id}/file") public void download(@PathVariable long id, HttpServletResponse response) throws IOException {
        var file = jobs.download(id);
        response.setContentType(Excel.CONTENT_TYPE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString());
        try (var input = Files.newInputStream(file.path())) { input.transferTo(response.getOutputStream()); }
    }
    @Override public void authorizeReplay(String operation, Object[] arguments, JsonNode result) {
        jobs.get(result.path("id").longValue());
    }
}
