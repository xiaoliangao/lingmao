package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.module.system.service.AuthService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /** 规则：密码为空在边界就拦下，返回 400，Service 不会被调用 */
    @Test
    void login_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": ""}
                                """))
                .andExpect(jsonPath("$.code").value(400));

        verify(authService, never()).login(any());
    }

    /** 规则：登录成功，token 放在 data 里 */
    @Test
    void login_ok_returnsTokenInData() throws Exception {
        when(authService.login(any())).thenReturn("token-for-1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "admin", "password": "right"}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("token-for-1"));
    }

    /** 规则：改密码时新密码不到 8 位，在入口就返回 400，Service 不会被调用 */
    @Test
    void changePassword_newPasswordTooShort_returns400() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword": "old-pass-123", "newPassword": "1234567"}
                                """))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("新密码长度 8-32 位"));

        verify(authService, never()).changePassword(any(), any(), any());
    }

    /** 规则：改的是当前登录的人自己的密码（用户 id 从登录信息里取，不从参数里取） */
    @Test
    void changePassword_ok_changesCurrentUsersPassword() throws Exception {
        // addFilters = false 时安全过滤器不跑，.with(authentication(..)) 放进去的登录信息没人取出来，
        // 所以直接放进 SecurityContextHolder；Spring Security 的测试监听器在每个测试后会清掉
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(1L, "jti-1"), null, List.of()));

        mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword": "old-pass-123", "newPassword": "new-pass-123"}
                                """))
                .andExpect(jsonPath("$.code").value(0));

        verify(authService).changePassword(1L, "old-pass-123", "new-pass-123");
    }
}