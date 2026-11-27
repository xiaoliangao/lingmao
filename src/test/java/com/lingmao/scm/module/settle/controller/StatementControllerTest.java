package com.lingmao.scm.module.settle.controller;

import com.lingmao.scm.config.SecurityConfig;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.service.DocCommonService;
import com.lingmao.scm.module.settle.service.StatementService;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 对账单接口（RFC 019 §5.1）：确认单独一个权限；生成要主体、供应商、期间 */
@WebMvcTest(StatementController.class)
@Import(SecurityConfig.class)
class StatementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StatementService statementService;

    @MockitoBean
    private DocCommonService docCommonService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    /** 规则：只有"生成、改、发送"的权限不能确认（403）；有"确认"的才走到 Service */
    @Test
    void confirm_needsConfirmPermission() throws Exception {
        String body = "{\"version\": 3, \"reason\": \"对上了\"}";
        mockMvc.perform(post("/api/statements/5/confirm").with(loginAs("settle:statement:edit"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(403));
        verify(statementService, never()).act(anyLong(), anyString(), any());

        mockMvc.perform(post("/api/statements/5/confirm").with(loginAs("settle:statement:confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0));
        verify(statementService).act(5L, "confirm", new DocActionReq(3, "对上了"));
    }

    /** 规则：生成少了供应商 → 400，说缺什么 */
    @Test
    void create_validates() throws Exception {
        mockMvc.perform(post("/api/statements").with(loginAs("settle:statement:edit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\": 1, \"periodStart\": \"2026-10-01\", \"periodEnd\": \"2026-10-31\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("供应商必填")));
        verify(statementService, never()).create(any());
    }

    private static RequestPostProcessor loginAs(String... permissions) {
        return authentication(new UsernamePasswordAuthenticationToken(new LoginUser(2L, "jti-2"), null,
                Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }
}
