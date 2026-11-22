package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
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
        boolean cookie = cookieMode(request);
        var tokens = cookie ? authService.login(req) : authService.login(req, false);
        if (cookie) cookies.write(request, response, tokens);
        response.setHeader("Cache-Control", "no-store");
        return R.ok(tokens.accessToken());
    }

    @Operation(summary = "使用 HttpOnly Cookie 原子刷新会话")
    @PostMapping("/refresh")
    public R<String> refresh(HttpServletRequest request, HttpServletResponse response) {
        if (!cookieMode(request)) throw new com.lingmao.scm.common.exception.BizException(400, "仅访问令牌会话不支持刷新，请重新登录");
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

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    @OperLog(module = "认证", action = "退出")
    public R<Void> logout(@AuthenticationPrincipal LoginUser loginUser, HttpServletRequest request, HttpServletResponse response){
        boolean cookie = cookieMode(request);
        if (cookie) {
            cookies.requireRefreshHeader(request);
            authService.revokeRefresh(cookies.read(request, SessionCookies.REFRESH), cookies.read(request, SessionCookies.BROWSER));
        }
        if (loginUser != null) authService.logout(loginUser.jti());
        if (cookie) cookies.clear(request, response);
        response.setHeader("Cache-Control", "no-store");
        return R.ok();
    }

    @Operation(summary = "改自己的密码（登录了就能改，不要额外的权限）")
    @PutMapping("/password")
    @OperLog(module = "认证", action = "改密码")
    public R<Void> changePassword(@AuthenticationPrincipal LoginUser loginUser,
                                  @Valid @RequestBody PasswordChangeReq req, HttpServletRequest request, HttpServletResponse response) {
        boolean cookie = cookieMode(request);
        authService.changePassword(loginUser.userId(), req.getOldPassword(), req.getNewPassword());
        if (cookie) cookies.clear(request, response);
        response.setHeader("Cache-Control", "no-store");
        return R.ok();
    }
    /** 缺省保留既有 Cookie 协议；未知值必须在任何会话写操作之前拒绝。 */
    private static boolean cookieMode(HttpServletRequest request) {
        String mode = request.getHeader("X-Session-Mode");
        if (mode == null || "cookie".equals(mode)) return true;
        if ("access-only".equals(mode)) return false;
        throw new com.lingmao.scm.common.exception.BizException(400, "X-Session-Mode 仅支持 cookie 或 access-only");
    }
}
