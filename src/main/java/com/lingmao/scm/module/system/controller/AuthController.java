package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.AssistantTokenVO;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.PasswordChangeReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.service.AuthService;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.SessionCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionCookies cookies;

    @Operation(summary = "登录，返回 token")
    @PostMapping("/login")
    @OperLog(module = "认证", action = "登录")
    public R<String> login(@Valid @RequestBody LoginReq req, HttpServletRequest request, HttpServletResponse response) {
        var tokens = authService.login(req);
        cookies.write(request, response, tokens);
        return R.ok(tokens.accessToken());
    }

    @Operation(summary = "使用 HttpOnly Cookie 原子刷新会话")
    @PostMapping("/refresh")
    public R<String> refresh(HttpServletRequest request, HttpServletResponse response) {
        cookies.requireRefreshHeader(request);
        var tokens = authService.refresh(cookies.read(request, SessionCookies.REFRESH), cookies.read(request, SessionCookies.BROWSER));
        cookies.write(request, response, tokens);
        return R.ok(tokens.accessToken());
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public R<UserInfoVO> me(@AuthenticationPrincipal LoginUser loginUser){
        return R.ok(authService.me(loginUser.userId()));
    }

    @Operation(summary = "换一张给智能助手用的短期只读令牌（30 分钟，不能刷新；AI 功能关着回 403）")
    @PostMapping("/assistant-token")
    @PreAuthorize("hasAuthority('assistant:chat:use')")
    public R<AssistantTokenVO> assistantToken(@AuthenticationPrincipal LoginUser loginUser) {
        return R.ok(authService.assistantToken(loginUser));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @OperLog(module = "认证", action = "退出")
    public R<Void> logout(@AuthenticationPrincipal LoginUser loginUser, HttpServletRequest request, HttpServletResponse response){
        cookies.requireRefreshHeader(request);
        authService.revokeRefresh(cookies.read(request, SessionCookies.REFRESH), cookies.read(request, SessionCookies.BROWSER));
        if (loginUser != null) authService.logout(loginUser.jti());
        cookies.clear(request, response);
        return R.ok();
    }

    @Operation(summary = "改自己的密码（登录了就能改，不要额外的权限）")
    @PutMapping("/password")
    @OperLog(module = "认证", action = "改密码")
    public R<Void> changePassword(@AuthenticationPrincipal LoginUser loginUser,
                                  @Valid @RequestBody PasswordChangeReq req, HttpServletRequest request, HttpServletResponse response) {
        authService.changePassword(loginUser.userId(), req.getOldPassword(), req.getNewPassword());
        cookies.clear(request, response);
        return R.ok();
    }
}
