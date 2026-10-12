package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.OperLogQuery;
import com.lingmao.scm.module.system.entity.SysOperLog;
import com.lingmao.scm.module.system.service.OperLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "操作日志")
@RestController
@RequestMapping("/api/oper-logs")
@RequiredArgsConstructor
public class OperLogController {

    private final OperLogService operLogService;

    @Operation(summary = "分页查询（新的在前）")
    @GetMapping
    @PreAuthorize("hasAuthority('system:log:list')")
    public R<PageResult<SysOperLog>> page(@Valid OperLogQuery query) {
        return R.ok(operLogService.page(query));
    }
}
