package com.lingmao.scm.module.inventory.controller;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.idempotency.IdempotencyReplayGuard;
import com.lingmao.scm.common.idempotency.IdempotentCreate;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.inventory.dto.ExportJobView;
import com.lingmao.scm.module.inventory.dto.StockLogExportReq;
import com.lingmao.scm.module.inventory.service.ExportJobService;
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

@RestController
@RequestMapping("/api/export-jobs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:stock:export')")
public class ExportJobController extends IdempotencyReplayGuard {
    private final ExportJobService jobs;

    @PostMapping("/stock-logs") @ResponseStatus(HttpStatus.ACCEPTED) @IdempotentCreate("export-stock-logs")
    public R<ExportJobView> create(@Valid @RequestBody StockLogExportReq filters) {
        return R.ok(jobs.create(filters, org.slf4j.MDC.get("traceId")));
    }
    @GetMapping("/{id}") public R<ExportJobView> get(@PathVariable long id) { return R.ok(jobs.get(id)); }
    @PostMapping("/{id}/cancel") @IdempotentCreate("cancel-export-job")
    public R<ExportJobView> cancel(@PathVariable long id) { return R.ok(jobs.cancel(id)); }
    @PostMapping("/{id}/retry") @ResponseStatus(HttpStatus.ACCEPTED) @IdempotentCreate("retry-export-job")
    public R<ExportJobView> retry(@PathVariable long id) { return R.ok(jobs.retry(id)); }
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
