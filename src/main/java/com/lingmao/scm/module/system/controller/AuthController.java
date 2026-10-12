package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.service.AuthService;
import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录，返回 token")
    @PostMapping("/login")
    @OperLog(module = "认证", action = "登录")
    public R<String> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req));
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public R<UserInfoVO> me(@AuthenticationPrincipal LoginUser loginUser){
        return R.ok(authService.me(loginUser.userId()));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @OperLog(module = "认证", action = "退出")
    public R<Void> logout(@AuthenticationPrincipal LoginUser loginUser){
        authService.logout(loginUser.jti());

        return R.ok();
    }
}