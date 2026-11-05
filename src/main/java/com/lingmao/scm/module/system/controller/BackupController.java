package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.BackupStatusVO;
import com.lingmao.scm.module.system.service.BackupStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "备份")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class BackupController {

    private final BackupStatusService backupStatusService;

    @Operation(summary = "最近一次每日备份（只给超级管理员）；超过 36 小时没有新备份时 stale 为 true")
    @GetMapping("/backup-status")
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<BackupStatusVO> backupStatus() {
        return R.ok(backupStatusService.status());
    }
}
