package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.system.dto.AiSwitchVO;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.SuperAdminCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 带上真的安全配置（SecurityConfig + @PreAuthorize），测的就是"谁能开关" */
@WebMvcTest(AiSwitchController.class)
@Import({SecurityConfig.class, SuperAdminCheck.class})
class AiSwitchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：超级管理员能开，记下是谁开的；时间按东八区带偏移输出 */
    @Test
    void set_superAdmin_switchesOn() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(aiSwitchService.set(true, 1L)).thenReturn(
                new AiSwitchVO(true, "admin", OffsetDateTime.parse("2026-11-21T15:00:00+08:00")));

        mockMvc.perform(put("/api/admin/ai-switch").with(loginAs(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.updatedBy").value("admin"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-21T15:00:00+08:00"));
    }

    /** 规则：不是超级管理员 → 403，看也不行、改也不行，根本不去动开关 */
    @Test
    void notSuperAdmin_returns403() throws Exception {
        when(permissionService.loadRoleCodes(2L)).thenReturn(List.of("manager"));

        mockMvc.perform(get("/api/admin/ai-switch").with(loginAs(2L)))
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(put("/api/admin/ai-switch").with(loginAs(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .andExpect(jsonPath("$.code").value(403));

        verify(aiSwitchService, never()).get();
        verify(aiSwitchService, never()).set(anyBoolean(), anyLong());
    }

    /** 规则：超级管理员能看；没登录 → 401，不去读 */
    @Test
    void get_superAdminSees_anonymous401() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(aiSwitchService.get()).thenReturn(new AiSwitchVO(false, null, null));

        mockMvc.perform(get("/api/admin/ai-switch").with(loginAs(1L)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
        mockMvc.perform(get("/api/admin/ai-switch"))
                .andExpect(jsonPath("$.code").value(401));

        verify(aiSwitchService, org.mockito.Mockito.times(1)).get();
    }

    /** 规则：没说开还是关 → 400，不改 */
    @Test
    void set_missingEnabled_returns400() throws Exception {
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));

        mockMvc.perform(put("/api/admin/ai-switch").with(loginAs(1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400));

        verify(aiSwitchService, never()).set(anyBoolean(), anyLong());
    }

    private static RequestPostProcessor loginAs(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "jti-" + userId), null, List.of()));
    }
}
