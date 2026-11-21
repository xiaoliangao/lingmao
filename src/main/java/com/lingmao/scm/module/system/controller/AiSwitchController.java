package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.AiSwitchReq;
import com.lingmao.scm.module.system.dto.AiSwitchVO;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理后台 › AI 功能（RFC 017 §3.7）：只给超级管理员 */
@Tag(name = "AI 功能")
@RestController
@RequestMapping("/api/admin/ai-switch")
@RequiredArgsConstructor
public class AiSwitchController {

    private final AiSwitchService aiSwitchService;

    @Operation(summary = "AI 功能统一开关现在开着没有、上次谁改的（只给超级管理员）")
    @GetMapping
    @PreAuthorize("@superAdmin.check(authentication)")
    public R<AiSwitchVO> get() {
        return R.ok(aiSwitchService.get());
    }

    @Operation(summary = "打开或关闭 AI 功能（只给超级管理员）：立刻生效，操作日志里记改前、改后")
    @PutMapping
    @PreAuthorize("@superAdmin.check(authentication)")
    @OperLog(module = "AI 功能", action = "开关")
    public R<AiSwitchVO> set(@AuthenticationPrincipal LoginUser loginUser, @Valid @RequestBody AiSwitchReq req) {
        return R.ok(aiSwitchService.set(req.enabled(), loginUser.userId()));
    }
}
