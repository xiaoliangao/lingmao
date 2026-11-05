package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.dto.BackupStatusVO;
import com.lingmao.scm.module.system.service.BackupStatusService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.SuperAdminCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 带上真的安全配置（SecurityConfig + @PreAuthorize），测的就是"谁能看" */
@WebMvcTest(BackupController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupStatusService backupStatusService;

    /** 判断是不是超级管理员要查角色 */
    @MockitoBean
    private PermissionService permissionService;

    /** SecurityConfig 要用，这里不走 token，给假的 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：超级管理员能看；时间按东八区 yyyy-MM-dd HH:mm:ss 输出 */
    @Test
    void backupStatus_superAdmin_returnsStatus() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(backupStatusService.status()).thenReturn(new BackupStatusVO(true, "scm-20261121-023000.dump",
                OffsetDateTime.parse("2026-11-21T02:30:00+08:00"), 2048L, false));

        mockMvc.perform(get("/api/admin/backup-status").with(loginAs(1L)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.latestFile").value("scm-20261121-023000.dump"))
                .andExpect(jsonPath("$.data.latestTime").value("2026-11-21 02:30:00"))
                .andExpect(jsonPath("$.data.sizeBytes").value(2048))
                .andExpect(jsonPath("$.data.stale").value(false));
    }

    /** 规则：登录了但不是超级管理员 → 403，根本不去读备份目录 */
    @Test
    void backupStatus_notSuperAdmin_returns403() throws Exception {
        when(permissionService.loadRoleCodes(2L)).thenReturn(List.of("sales"));

        mockMvc.perform(get("/api/admin/backup-status").with(loginAs(2L)))
                .andExpect(jsonPath("$.code").value(403));

        verify(backupStatusService, never()).status();
    }

    /** 规则：没登录 → 401 */
    @Test
    void backupStatus_notLoggedIn_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/backup-status"))
                .andExpect(jsonPath("$.code").value(401));

        verify(backupStatusService, never()).status();
    }

    /** 不走 token：直接把"登录用户"放进这次请求的安全上下文，效果和 JwtAuthFilter 认出 token 以后一样 */
    private static RequestPostProcessor loginAs(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti-" + userId), null, List.of()));
    }
}
